#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <cstdio>
#include <fstream>
#include <string>
#include <thread>

// Implemented in native_hook_backend.cpp.
extern "C" jboolean dev_khoirevanced_initialize_hook_backend(JNIEnv* env, const char* log_path);

namespace {
constexpr char kTag[] = "KhoiRevanced";

// Set once JNI_OnLoad has run, which is the only point at which a JNIEnv without
// hidden-API restrictions is available for LSPlant.
std::atomic<bool> g_jni_onload_complete{false};

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
    __android_log_print(ANDROID_LOG_INFO, kTag, "bootstrap thread started in pid %d", getpid());
    // This thread is started from an ELF constructor, which the dynamic linker
    // runs before it calls JNI_OnLoad. Wait for JNI_OnLoad so LSPlant is
    // initialised from the environment it requires before any Java code runs,
    // rather than racing it and reporting a failure from the wrong JNIEnv.
    for (int attempt = 0; attempt < 500 && !g_jni_onload_complete.load(); ++attempt) {
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
    if (!g_jni_onload_complete.load()) {
        __android_log_print(ANDROID_LOG_ERROR, kTag,
                            "JNI_OnLoad did not run; continuing without it");
    }
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
    __android_log_print(ANDROID_LOG_INFO, kTag, "using config %s", config.c_str());
    FILE* fp = fopen(config.c_str(), "r");
    if (fp == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kTag,
                            "per-process config file is missing: %s", config.c_str());
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
    __android_log_print(ANDROID_LOG_INFO, kTag, "config parsed dex=%s cache=%s",
                        dex_path, cache_path);

    jclass activity_thread = env->FindClass("android/app/ActivityThread");
    if (clear_exception(env, "ActivityThread lookup") || activity_thread == nullptr) {
        log_error("ActivityThread is unavailable");
        if (attached) vm->DetachCurrentThread();
        return;
    }
    jmethodID current_app = env->GetStaticMethodID(
        activity_thread, "currentApplication", "()Landroid/app/Application;");
    if (clear_exception(env, "ActivityThread.currentApplication lookup") ||
        current_app == nullptr) {
        log_error("ActivityThread.currentApplication is unavailable");
        if (attached) vm->DetachCurrentThread();
        return;
    }

    // The controller deliberately attaches as soon as the zygote child exists
    // so lifecycle-sensitive hooks are installed before the first Activity is
    // inflated. At that point the VM can already exist while the Application
    // has not been published yet. Poll here instead of aborting the bootstrap;
    // otherwise a fast Manager launch randomly reports an injected library but
    // never loads the compatibility patch set.
    jobject app = nullptr;
    for (int attempt = 0; attempt < 600 && app == nullptr; ++attempt) {
        app = env->CallStaticObjectMethod(activity_thread, current_app);
        if (clear_exception(env, "ActivityThread.currentApplication")) {
            app = nullptr;
        }
        if (app == nullptr) {
            std::this_thread::sleep_for(std::chrono::milliseconds(25));
        }
    }
    if (app == nullptr) {
        log_error("Application was not created within 15000ms");
        if (attached) vm->DetachCurrentThread();
        return;
    }
    __android_log_print(ANDROID_LOG_INFO, kTag, "Application is ready in pid %d", getpid());

    jclass context = env->FindClass("android/content/Context");
    jmethodID get_loader = env->GetMethodID(context, "getClassLoader", "()Ljava/lang/ClassLoader;");
    jobject parent = env->CallObjectMethod(app, get_loader);

    jclass dex_loader = env->FindClass("dalvik/system/DexClassLoader");
    jmethodID ctor = env->GetMethodID(
        dex_loader, "<init>",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/ClassLoader;)V");
    jstring dex = env->NewStringUTF(dex_path);
    jstring cache = env->NewStringUTF(cache_path);
    // All runtime native libraries are placed next to classes.dex.  Supplying
    // this search path lets the payload class loader resolve its native bridge without
    // relying on the host APK's native-library directory.
    const std::string dex_file(dex_path);
    const size_t slash = dex_file.rfind('/');
    const std::string native_dir = slash == std::string::npos ? "." : dex_file.substr(0, slash);
    jstring native_path = env->NewStringUTF(native_dir.c_str());
    jobject loader = env->NewObject(dex_loader, ctor, dex, cache, native_path, parent);
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
    __android_log_print(ANDROID_LOG_INFO, kTag, "AgentBootstrap class loaded");

    jmethodID start = env->GetStaticMethodID(
        bootstrap_class, "start", "(Ljava/lang/String;)V");
    jstring config_string = env->NewStringUTF(config.c_str());
    env->CallStaticVoidMethod(bootstrap_class, start, config_string);
    if (clear_exception(env, "AgentBootstrap.start")) {
        log_error("AgentBootstrap.start threw; Java diagnostics should contain the cause");
    } else {
        __android_log_print(ANDROID_LOG_INFO, kTag, "AgentBootstrap.start returned");
    }

    if (attached) vm->DetachCurrentThread();
}
}  // namespace

// Where native_hook_backend writes its diagnostics. Derived from the layout
// inject.sh creates rather than read from the config, so it is available before
// anything has parsed that config: <runtime>/run/<pid>.conf next to
// <runtime>/cache/.
std::string native_log_path() {
    const std::string config = config_path();
    const size_t last_slash = config.rfind('/');
    if (last_slash == std::string::npos) return {};
    const size_t run_slash = config.rfind('/', last_slash - 1);
    if (run_slash == std::string::npos) return {};
    return config.substr(0, run_slash) + "/cache/native-hook.log";
}

// LSPlant requires a JNIEnv that carries no hidden-API restriction and states
// that such an environment is the one handed to JNI_OnLoad. Initialising from
// the bootstrap thread instead, as this did, made Init return false before it
// even asked for a single ART symbol. dlopen calls this on every load of the
// agent, and initialise_hook_backend is idempotent, so the later System.load
// from the Kotlin side is harmless.
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm == nullptr || vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "JNI_OnLoad: could not obtain a JNIEnv");
    } else {
        const std::string log_path = native_log_path();
        const jboolean ok = dev_khoirevanced_initialize_hook_backend(
            env, log_path.empty() ? nullptr : log_path.c_str());
        __android_log_print(ok == JNI_TRUE ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR, kTag,
                            "LSPlant init from JNI_OnLoad: %s",
                            ok == JNI_TRUE ? "succeeded" : "failed");
    }
    g_jni_onload_complete.store(true);
    return JNI_VERSION_1_6;
}

__attribute__((constructor)) static void khoirevanced_load() {
    __android_log_print(ANDROID_LOG_INFO, kTag, "agent %s loaded in pid %d",
                        KHOIREVANCED_VERSION, getpid());
    std::thread(bootstrap).detach();
}
