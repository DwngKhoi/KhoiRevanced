#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <jni.h>
#include <link.h>
#include <unistd.h>

#include <dobby.h>
#include <lsplant.hpp>

#include <algorithm>
#include <atomic>
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
    // Which ELF symbol table was used: SHT_SYMTAB or SHT_DYNSYM.
    const char* source = "none";
};

std::mutex g_lock;
std::unordered_map<jlong, HookRecord> g_hooks;
jlong g_next_token = 1;
bool g_initialized = false;
void* g_art = nullptr;
ArtImage g_art_image;
FileSymbolTable g_file_symtab;

void log_error(const char* message) {
    __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", message);
}

// The status file the Kotlin side writes is the one diagnostic channel proven to
// reach the controller. logcat output from an injected process is not reliably
// visible, so mirror the native decisions into the same cache directory: a bare
// "LSPlant ART backend initialization failed" says nothing about which of the
// several ways it can fail was taken, and LSPlant itself reports none of the
// symbols it could not resolve.
std::string g_log_path;

void log_line(const std::string& message) {
    if (g_log_path.empty()) return;
    std::ofstream out(g_log_path, std::ios::app);
    if (out) out << message << '\n';
}

void log_to_cache(JNIEnv* env, jstring directory, const std::string& message) {
    if (env == nullptr || directory == nullptr) return;
    if (g_log_path.empty()) {
        const char* chars = env->GetStringUTFChars(directory, nullptr);
        if (chars == nullptr) return;
        g_log_path = std::string(chars) + "/native-hook.log";
        env->ReleaseStringUTFChars(directory, chars);
    }
    log_line(message);
}

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

// Find a usable ELF symbol table in an already-parsed ELF image.
//
// SHT_SYMTAB is preferred but does not exist in a release libart.so: production
// builds are stripped and keep only the dynamic symbol table. On the target
// device libart.so has no SHT_SYMTAB at all, which is why ART symbol resolution
// found nothing and LSPlant refused to initialise. The dynamic table is enough:
// LSPlant asks for mangled names such as _ZN3art6mirror5Class11GetClassDefEv,
// and of the 46 it references, the core ones LSPlant needs to start
// (GetClassDef, ArtMethod::PrettyMethod, Thread::CurrentFromGdb,
// Runtime::instance_, ArtMethod::SetNotIntrinsic, GetMethodShorty) are all
// exported there. The names that are absent are alternative signatures for
// other ART versions, which LSPlant treats as optional.
bool adopt_symbol_table(const std::vector<uint8_t>& bytes, const Elf64_Shdr* sections,
                        size_t section_count, uint32_t wanted_type, const char* label) {
    for (size_t index = 0; index < section_count; ++index) {
        const auto& section = sections[index];
        if (section.sh_type != wanted_type || section.sh_entsize != sizeof(Elf64_Sym) ||
            section.sh_link >= section_count ||
            !valid_range(bytes, section.sh_offset, section.sh_size)) continue;
        const auto& strings = sections[section.sh_link];
        if (!valid_range(bytes, strings.sh_offset, strings.sh_size)) continue;
        g_file_symtab.symbols =
            reinterpret_cast<const Elf64_Sym*>(bytes.data() + section.sh_offset);
        g_file_symtab.symbol_count = section.sh_size / sizeof(Elf64_Sym);
        g_file_symtab.strings =
            reinterpret_cast<const char*>(bytes.data() + strings.sh_offset);
        g_file_symtab.string_size = strings.sh_size;
        g_file_symtab.load_bias = g_art_image.load_bias;
        g_file_symtab.source = label;
        return true;
    }
    return false;
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
        !valid_range(bytes, header->e_shoff,
                     static_cast<size_t>(header->e_shnum) * sizeof(Elf64_Shdr))) return;
    const auto* sections =
        reinterpret_cast<const Elf64_Shdr*>(bytes.data() + header->e_shoff);
    const size_t count = header->e_shnum;
    if (adopt_symbol_table(bytes, sections, count, SHT_SYMTAB, "SHT_SYMTAB")) return;
    (void)adopt_symbol_table(bytes, sections, count, SHT_DYNSYM, "SHT_DYNSYM");
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

// LSPlant resolves dozens of ART symbols through these two callbacks while it
// initialises, and it gives no indication which one it could not satisfy when
// Init returns false. Log each request and its answer, bounded so a failure
// cannot flood the file.
std::atomic<int> g_resolver_log_budget{160};

void report_resolution(const char* kind, const std::string& requested, void* result) {
    if (g_resolver_log_budget.fetch_sub(1) <= 0) return;
    char buffer[32];
    snprintf(buffer, sizeof(buffer), "%p", result);
    log_line(std::string(kind) + " \"" + requested + "\" -> " + buffer);
}

void* resolve_art_symbol(std::string_view symbol) {
    // No dlopen handle is required, and none is available: see the comment in
    // nativeInitialize. Symbols are located in the libart image that is already
    // mapped, which is what LSPlant has to hook.
    void* address = resolve_file_symbol(symbol, false);
    if (address == nullptr) {
        const std::string requested(symbol);
        address = dlsym(RTLD_DEFAULT, requested.c_str());
    }
    report_resolution("resolve", std::string(symbol), address);
    return address;
}

void* resolve_art_symbol_prefix(std::string_view prefix) {
    // Resolve hidden ART symbols from the ELF symbol table when dlsym cannot see them.
    void* address = resolve_file_symbol(prefix, true);
    report_resolution("prefix", std::string(prefix), address);
    return address;
}

HookRecord* find_record(JNIEnv* env, jobject member) {
    for (auto& [token, record] : g_hooks) {
        if (env->IsSameObject(record.target_method, member)) return &record;
    }
    return nullptr;
}

// Perform the one-time LSPlant initialisation.
//
// LSPlant documents that the JNIEnv passed to Init "should not have any
// restriction for accessing hidden APIs" and that "you can obtain such a JNIEnv
// in JNI_OnLoad()". Calling it from Kotlin, on the bootstrap thread, does not
// satisfy that. On the target device Init returned false from there without ever
// invoking the symbol resolver, so it was failing a precondition rather than on
// symbol lookup, and the resolver tracing confirmed not a single request arrived.
// The agent therefore initialises from JNI_OnLoad, before any Java code runs,
// and this function is shared by both entry points.
bool initialize_hook_backend(JNIEnv* env) {
    std::scoped_lock lock(g_lock);
    if (g_initialized) {
        log_line("initialize_hook_backend: already initialized");
        return true;
    }

    // Never dlopen libart.so. The agent is loaded into the app's classloader
    // namespace, which is not the namespace that already mapped libart, so
    // dlopen("libart.so") does not find it there and instead pulls a second
    // copy of the runtime into a process that is already running. That aborts
    // the process outright -- observed as a tombstone with frames in
    // libkhoirevanced_agent.so, right after the RTLD_NOLOAD probe reported that
    // libart was not in the loaded set.
    //
    // The image that is already mapped is located with dl_iterate_phdr instead,
    // which needs no handle, and the resolvers require none either: they
    // address symbols as load_bias + st_value from the file's own symbol table.
    // That is also the only way to reach ART's symbols on Android, where a
    // release libart.so is stripped of SHT_SYMTAB and LSPlant asks for mangled
    // names such as _ZN3art6mirror5Class11GetClassDefEv.
    dl_iterate_phdr(find_art_image, &g_art_image);
    log_line("libart image path: " +
        (g_art_image.path.empty() ? std::string("<not found>") : g_art_image.path));
    if (g_art_image.path.empty()) {
        log_error("LSPlant could not locate libart.so image");
        log_line("FAIL: dl_iterate_phdr did not locate libart.so");
        return false;
    }
    load_file_symtab();
    char image_bias[32];
    char table_bias[32];
    snprintf(image_bias, sizeof(image_bias), "0x%llx",
             static_cast<unsigned long long>(g_art_image.load_bias));
    snprintf(table_bias, sizeof(table_bias), "0x%llx",
             static_cast<unsigned long long>(g_file_symtab.load_bias));
    log_line(std::string("symbol table: ") + g_file_symtab.source +
        " entries=" + std::to_string(g_file_symtab.symbol_count) +
        " image_load_bias=" + image_bias + " table_load_bias=" + table_bias);
    if (g_file_symtab.symbols == nullptr) {
        log_line("FAIL: no SHT_SYMTAB and no SHT_DYNSYM usable in libart.so");
    }

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
    log_line(g_initialized ? "lsplant::Init succeeded" : "FAIL: lsplant::Init returned false");
    return g_initialized;
}

}  // namespace

// Entry point used from JNI_OnLoad, before any Java code runs. Exported so the
// agent's own translation unit can call it.
extern "C" __attribute__((visibility("default"))) jboolean
dev_khoirevanced_initialize_hook_backend(JNIEnv* env, const char* log_path) {
    if (env == nullptr) return JNI_FALSE;
    if (log_path != nullptr && *log_path != '\0') g_log_path = log_path;
    return initialize_hook_backend(env) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_khoirevanced_runtime_agent_NativeHookBackend_nativeInitialize(
    JNIEnv* env, jobject, jstring cache_dir) {
    if (env == nullptr || cache_dir == nullptr) return JNI_FALSE;
    if (g_log_path.empty()) {
        const char* chars = env->GetStringUTFChars(cache_dir, nullptr);
        if (chars == nullptr) return JNI_FALSE;
        g_log_path = std::string(chars) + "/native-hook.log";
        env->ReleaseStringUTFChars(cache_dir, chars);
    }
    // Normally JNI_OnLoad has already done this. Kept as a fallback so the
    // failure is still reported through the status file rather than skipped.
    return initialize_hook_backend(env) ? JNI_TRUE : JNI_FALSE;
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
