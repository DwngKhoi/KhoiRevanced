#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <unistd.h>

#include <chrono>
#include <cstdio>
#include <fstream>
#include <string>
#include <thread>

namespace {
constexpr char kTag[] = "KhoiRevanced";

using GetCreatedJavaVms = jint (*)(JavaVM**, jsize, jsize*);

void log_error(const char* message) {
    __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", message);
}

std::string config_path() {
    std::ifstream cmdline("/proc/self/cmdline");
    std::string process;
    std::getline(cmdline, process, '\0');
    const size_t separator = process.find(':');
    const std::string package_name = process.substr(0, separator);
    return "/data/user/0/" + package_name + "/code_cache/khoirevanced/run/" +
        std::to_string(getpid()) + ".conf";
}

JavaVM* wait_for_vm() {
    // Android versions vary where this JNI entry point is exported. Try the
    // process global scope first, then both runtime libraries. The handles are
    // intentionally retained: libnativehelper may provide a forwarding symbol.
    auto get_vms = reinterpret_cast<GetCreatedJavaVms>(
        dlsym(RTLD_DEFAULT, "JNI_GetCreatedJavaVMs"));
    void* native_helper = nullptr;
    void* art = nullptr;
    if (get_vms == nullptr) {
        native_helper = dlopen("libnativehelper.so", RTLD_NOW | RTLD_NOLOAD);
        if (native_helper == nullptr) native_helper = dlopen("libnativehelper.so", RTLD_NOW);
        if (native_helper != nullptr) {
            get_vms = reinterpret_cast<GetCreatedJavaVms>(
                dlsym(native_helper, "JNI_GetCreatedJavaVMs"));
        }
    }
    if (get_vms == nullptr) {
        art = dlopen("libart.so", RTLD_NOW | RTLD_NOLOAD);
        if (art == nullptr) art = dlopen("libart.so", RTLD_NOW);
        if (art != nullptr) {
            get_vms = reinterpret_cast<GetCreatedJavaVms>(
                dlsym(art, "JNI_GetCreatedJavaVMs"));
        }
    }
    if (get_vms == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kTag,
            "JNI_GetCreatedJavaVMs is not exported (nativehelper=%p art=%p)",
            native_helper, art);
        return nullptr;
    }

    for (int attempt = 0; attempt < 600; ++attempt) {
        JavaVM* vm = nullptr;
        jsize count = 0;
        if (get_vms(&vm, 1, &count) == JNI_OK && count == 1) return vm;
        std::this_thread::sleep_for(std::chrono::milliseconds(25));
    }
    return nullptr;
}

bool clear_exception(JNIEnv* env, const char* stage) {
    if (!env->ExceptionCheck()) return false;
    jthrowable throwable = env->ExceptionOccurred();
    env->ExceptionClear();
    jclass throwable_class = env->FindClass("java/lang/Throwable");
    jmethodID to_string = throwable_class == nullptr ? nullptr :
        env->GetMethodID(throwable_class, "toString", "()Ljava/lang/String;");
    jstring description = to_string == nullptr ? nullptr :
        static_cast<jstring>(env->CallObjectMethod(throwable, to_string));
    const char* text = description == nullptr ? nullptr : env->GetStringUTFChars(description, nullptr);
    __android_log_print(ANDROID_LOG_ERROR, kTag, "JNI exception during %s: %s",
                        stage, text == nullptr ? "<unavailable>" : text);
    if (text != nullptr) env->ReleaseStringUTFChars(description, text);
    return true;
}

void bootstrap() {
    JavaVM* vm = wait_for_vm();
    if (vm == nullptr) {
        log_error("JNI VM was not found");
        return;
    }

    JNIEnv* env = nullptr;
    bool attached = false;
    jint status = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            log_error("AttachCurrentThread failed");
            return;
        }
        attached = true;
    } else if (status != JNI_OK) {
        log_error("GetEnv failed");
        return;
    }

    // payload.dex is next to the injected library in the runtime directory.
    const std::string config = config_path();
    FILE* fp = fopen(config.c_str(), "r");
    if (fp == nullptr) {
        log_error("Per-process config file is missing");
        if (attached) vm->DetachCurrentThread();
        return;
    }
    char dex_path[1024]{};
    char cache_path[1024]{};
    if (fscanf(fp, "dex=%1023s\ncache=%1023s", dex_path, cache_path) != 2) {
        fclose(fp);
        log_error("Invalid native config header");
        if (attached) vm->DetachCurrentThread();
        return;
    }
    fclose(fp);

    jclass activity_thread = env->FindClass("android/app/ActivityThread");
    jmethodID current_app = env->GetStaticMethodID(
        activity_thread, "currentApplication", "()Landroid/app/Application;");
    jobject app = env->CallStaticObjectMethod(activity_thread, current_app);
    if (clear_exception(env, "ActivityThread.currentApplication") || app == nullptr) {
        log_error("Application is not ready; inject after process initialization");
        if (attached) vm->DetachCurrentThread();
        return;
    }

    jclass context = env->FindClass("android/content/Context");
    jmethodID get_loader = env->GetMethodID(context, "getClassLoader", "()Ljava/lang/ClassLoader;");
    jobject parent = env->CallObjectMethod(app, get_loader);

    jclass dex_loader = env->FindClass("dalvik/system/DexClassLoader");
    jmethodID ctor = env->GetMethodID(
        dex_loader, "<init>",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/ClassLoader;)V");
    jstring dex = env->NewStringUTF(dex_path);
    jstring cache = env->NewStringUTF(cache_path);
    jobject loader = env->NewObject(dex_loader, ctor, dex, cache, nullptr, parent);
    if (clear_exception(env, "DexClassLoader construction")) {
        if (attached) vm->DetachCurrentThread();
        return;
    }

    jclass class_loader = env->FindClass("java/lang/ClassLoader");
    jmethodID load_class = env->GetMethodID(
        class_loader, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    jstring bootstrap_name = env->NewStringUTF("dev.khoirevanced.runtime.agent.AgentBootstrap");
    auto bootstrap_class = static_cast<jclass>(env->CallObjectMethod(loader, load_class, bootstrap_name));
    if (clear_exception(env, "AgentBootstrap loading") || bootstrap_class == nullptr) {
        if (attached) vm->DetachCurrentThread();
        return;
    }

    jmethodID start = env->GetStaticMethodID(
        bootstrap_class, "start", "(Ljava/lang/String;)V");
    jstring config_string = env->NewStringUTF(config.c_str());
    env->CallStaticVoidMethod(bootstrap_class, start, config_string);
    clear_exception(env, "AgentBootstrap.start");

    if (attached) vm->DetachCurrentThread();
}
}  // namespace

__attribute__((constructor)) static void khoirevanced_load() {
    __android_log_print(ANDROID_LOG_INFO, kTag, "agent %s loaded in pid %d",
                        KHOIREVANCED_VERSION, getpid());
    std::thread(bootstrap).detach();
}
