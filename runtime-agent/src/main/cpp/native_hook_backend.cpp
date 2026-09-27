#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <jni.h>
#include <link.h>
#include <unistd.h>

#include <dobby.h>
#include <lsplant.hpp>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <iterator>
#include <mutex>
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>

namespace {
constexpr char kTag[] = "KhoiRevanced";
constexpr jint kMethodCapability = 0;
constexpr jint kInvokeOriginalCapability = 2;
constexpr jint kDeoptimizeCapability = 3;

struct HookRecord {
    jobject target_method;
    jobject dispatcher;
    jobject backup_method;
};

struct ArtImage {
    uintptr_t load_bias = 0;
    std::string path;
};

struct FileSymbolTable {
    std::vector<uint8_t> bytes;
    const Elf64_Sym* symbols = nullptr;
    size_t symbol_count = 0;
    const char* strings = nullptr;
    size_t string_size = 0;
    uintptr_t load_bias = 0;
};

std::mutex g_lock;
std::unordered_map<jlong, HookRecord> g_hooks;
jlong g_next_token = 1;
bool g_initialized = false;
void* g_art = nullptr;
ArtImage g_art_image;
FileSymbolTable g_file_symtab;

void* hook_function(void* target, void* replacement) {
    void* backup = nullptr;
    return DobbyHook(target, replacement, &backup) == RS_SUCCESS ? backup : nullptr;
}

bool unhook_function(void* target) {
    return DobbyDestroy(target) == RS_SUCCESS;
}

int find_art_image(struct dl_phdr_info* info, size_t, void* data) {
    auto* image = static_cast<ArtImage*>(data);
    const char* name = info->dlpi_name;
    if (name == nullptr || *name == '\0' || strstr(name, "/libart.so") == nullptr) return 0;
    image->load_bias = static_cast<uintptr_t>(info->dlpi_addr);
    image->path = name;
    return 1;
}

bool valid_range(const std::vector<uint8_t>& bytes, size_t offset, size_t length) {
    return offset <= bytes.size() && length <= bytes.size() - offset;
}

void load_file_symtab() {
    if (g_art_image.path.empty()) return;
    std::ifstream input(g_art_image.path, std::ios::binary);
    if (!input) return;
    g_file_symtab.bytes.assign(std::istreambuf_iterator<char>(input), {});
    const auto& bytes = g_file_symtab.bytes;
    if (!valid_range(bytes, 0, sizeof(Elf64_Ehdr))) return;
    const auto* header = reinterpret_cast<const Elf64_Ehdr*>(bytes.data());
    if (memcmp(header->e_ident, ELFMAG, SELFMAG) != 0 || header->e_ident[EI_CLASS] != ELFCLASS64 ||
        header->e_shentsize != sizeof(Elf64_Shdr) || header->e_shnum == 0 ||
        !valid_range(bytes, header->e_shoff, static_cast<size_t>(header->e_shnum) * sizeof(Elf64_Shdr))) return;
    const auto* sections = reinterpret_cast<const Elf64_Shdr*>(bytes.data() + header->e_shoff);
    for (size_t index = 0; index < header->e_shnum; ++index) {
        const auto& section = sections[index];
        if (section.sh_type != SHT_SYMTAB || section.sh_entsize != sizeof(Elf64_Sym) ||
            section.sh_link >= header->e_shnum ||
            !valid_range(bytes, section.sh_offset, section.sh_size)) continue;
        const auto& strings = sections[section.sh_link];
        if (!valid_range(bytes, strings.sh_offset, strings.sh_size)) continue;
        g_file_symtab.symbols = reinterpret_cast<const Elf64_Sym*>(bytes.data() + section.sh_offset);
        g_file_symtab.symbol_count = section.sh_size / sizeof(Elf64_Sym);
        g_file_symtab.strings = reinterpret_cast<const char*>(bytes.data() + strings.sh_offset);
        g_file_symtab.string_size = strings.sh_size;
        g_file_symtab.load_bias = g_art_image.load_bias;
        return;
    }
}

void* resolve_file_symbol(std::string_view requested, bool prefix) {
    if (g_file_symtab.symbols == nullptr) load_file_symtab();
    if (g_file_symtab.symbols == nullptr) return nullptr;
    for (size_t index = 0; index < g_file_symtab.symbol_count; ++index) {
        const auto& symbol = g_file_symtab.symbols[index];
        if (symbol.st_shndx == SHN_UNDEF || symbol.st_value == 0 || symbol.st_name >= g_file_symtab.string_size) continue;
        const std::string_view name(g_file_symtab.strings + symbol.st_name);
        if ((prefix && name.starts_with(requested)) || (!prefix && name == requested)) {
            return reinterpret_cast<void*>(g_file_symtab.load_bias + symbol.st_value);
        }
    }
    return nullptr;
}

void* resolve_art_symbol(std::string_view symbol) {
    if (g_art == nullptr) return nullptr;
    const std::string requested(symbol);
    if (void* address = dlsym(g_art, requested.c_str())) return address;
    return resolve_file_symbol(symbol, false);
}

void* resolve_art_symbol_prefix(std::string_view prefix) {
    if (g_art == nullptr) return nullptr;
    // Resolve hidden ART symbols from the ELF symbol table when dlsym cannot see them.
    return resolve_file_symbol(prefix, true);
}

HookRecord* find_record(JNIEnv* env, jobject member) {
    for (auto& [token, record] : g_hooks) {
        if (env->IsSameObject(record.target_method, member)) return &record;
    }
    return nullptr;
}

void log_error(const char* message) {
    __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", message);
}
}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_khoirevanced_runtime_agent_NativeHookBackend_nativeInitialize(
    JNIEnv* env, jobject, jstring) {
    std::scoped_lock lock(g_lock);
    if (g_initialized) return JNI_TRUE;

    g_art = dlopen("libart.so", RTLD_NOW | RTLD_NOLOAD);
    if (g_art == nullptr) g_art = dlopen("libart.so", RTLD_NOW);
    if (g_art == nullptr) {
        log_error("LSPlant initialization could not open libart.so");
        return JNI_FALSE;
    }
    dl_iterate_phdr(find_art_image, &g_art_image);
    if (g_art_image.path.empty()) {
        log_error("LSPlant could not locate libart.so image");
        return JNI_FALSE;
    }
    load_file_symtab();

    lsplant::InitInfo info{
        .inline_hooker = hook_function,
        .inline_unhooker = unhook_function,
        .art_symbol_resolver = resolve_art_symbol,
        .art_symbol_prefix_resolver = resolve_art_symbol_prefix,
        .generated_class_name = "KhoiRevancedHooker_",
        .generated_source_name = "KhoiRevanced",
        .generated_field_name = "dispatcher",
        .generated_method_name = "{target}",
    };
    g_initialized = lsplant::Init(env, info);
    __android_log_print(g_initialized ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR, kTag,
                        "LSPlant initialization %s", g_initialized ? "succeeded" : "failed");
    return g_initialized ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_dev_khoirevanced_runtime_agent_NativeHookBackend_nativeCapabilities(
    JNIEnv* env, jobject) {
    const jint values[] = {kMethodCapability, kInvokeOriginalCapability, kDeoptimizeCapability};
    const jsize count = g_initialized ? static_cast<jsize>(std::size(values)) : 0;
    jintArray result = env->NewIntArray(count);
    if (count != 0) env->SetIntArrayRegion(result, 0, count, values);
    return result;
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_khoirevanced_runtime_agent_NativeHookBackend_nativeHook(
    JNIEnv* env, jobject, jobject member, jobject dispatcher) {
    std::scoped_lock lock(g_lock);
    if (!g_initialized || member == nullptr || dispatcher == nullptr || find_record(env, member) != nullptr) {
        return 0;
    }
    jclass dispatcher_class = env->GetObjectClass(dispatcher);
    jclass class_class = env->FindClass("java/lang/Class");
    if (env->ExceptionCheck() || dispatcher_class == nullptr || class_class == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return 0;
    }
    jmethodID get_declared_method = env->GetMethodID(
        class_class, "getDeclaredMethod",
        "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;");
    jstring callback_name = env->NewStringUTF("callback");
    jclass object_array_class = env->FindClass("[Ljava/lang/Object;");
    if (env->ExceptionCheck() || get_declared_method == nullptr || callback_name == nullptr ||
        object_array_class == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return 0;
    }
    jobjectArray callback_parameters = env->NewObjectArray(1, class_class, object_array_class);
    if (callback_parameters == nullptr || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return 0;
    }
    jobject callback = env->CallObjectMethod(
        dispatcher_class, get_declared_method, callback_name, callback_parameters);
    const bool callback_failed = callback == nullptr || env->ExceptionCheck();
    env->DeleteLocalRef(callback_name);
    env->DeleteLocalRef(callback_parameters);
    env->DeleteLocalRef(dispatcher_class);
    env->DeleteLocalRef(class_class);
    env->DeleteLocalRef(object_array_class);
    if (callback_failed) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return 0;
    }

    jobject backup = lsplant::Hook(env, member, dispatcher, callback);
    env->DeleteLocalRef(callback);
    if (backup == nullptr || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        log_error("LSPlant failed to install method hook");
        return 0;
    }
    const jlong token = g_next_token++;
    g_hooks.emplace(token, HookRecord{
        .target_method = env->NewGlobalRef(member),
        .dispatcher = env->NewGlobalRef(dispatcher),
        .backup_method = env->NewGlobalRef(backup),
    });
    env->DeleteLocalRef(backup);
    return token;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_khoirevanced_runtime_agent_NativeHookBackend_nativeUnhook(
    JNIEnv* env, jobject, jlong token) {
    std::scoped_lock lock(g_lock);
    const auto it = g_hooks.find(token);
    if (it == g_hooks.end()) return;
    const bool unhooked = lsplant::UnHook(env, it->second.target_method);
    if (!unhooked) log_error("LSPlant failed to remove method hook");
    env->DeleteGlobalRef(it->second.target_method);
    env->DeleteGlobalRef(it->second.dispatcher);
    env->DeleteGlobalRef(it->second.backup_method);
    g_hooks.erase(it);
}

extern "C" JNIEXPORT jobject JNICALL
Java_dev_khoirevanced_runtime_agent_NativeHookBackend_nativeInvokeOriginal(
    JNIEnv* env, jobject, jobject member, jobject receiver, jobjectArray args) {
    std::scoped_lock lock(g_lock);
    HookRecord* record = find_record(env, member);
    if (record == nullptr) {
        jclass unsupported = env->FindClass("java/lang/IllegalStateException");
        env->ThrowNew(unsupported, "No LSPlant backup exists for member");
        return nullptr;
    }
    jclass method_class = env->FindClass("java/lang/reflect/Method");
    jmethodID invoke = env->GetMethodID(method_class, "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
    return env->CallObjectMethod(record->backup_method, invoke, receiver, args);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_khoirevanced_runtime_agent_NativeHookBackend_nativeDeoptimize(
    JNIEnv* env, jobject, jobject member) {
    std::scoped_lock lock(g_lock);
    if (!g_initialized || member == nullptr) return JNI_FALSE;
    return lsplant::Deoptimize(env, member) ? JNI_TRUE : JNI_FALSE;
}
