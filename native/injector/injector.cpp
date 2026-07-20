#include <asm/ptrace.h>
#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/ptrace.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <climits>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <optional>
#include <sstream>
#include <string>
#include <string_view>

#if !defined(__aarch64__)
#error "KhoiRevanced injector currently supports arm64-v8a only"
#endif

namespace {
struct Mapping {
    uintptr_t start{};
    uintptr_t end{};
    uintptr_t offset{};
    std::string path;
};

std::optional<Mapping> mapping_for_address(pid_t pid, uintptr_t address) {
    std::ifstream maps("/proc/" + std::to_string(pid) + "/maps");
    std::string line;
    while (std::getline(maps, line)) {
        Mapping map;
        char permissions[5]{};
        char path[1024]{};
        unsigned long start = 0, end = 0, offset = 0;
        int fields = sscanf(line.c_str(), "%lx-%lx %4s %lx %*s %*s %1023[^\n]",
                            &start, &end, permissions, &offset, path);
        if (fields < 4) continue;
        map.start = start;
        map.end = end;
        map.offset = offset;
        if (fields == 5) {
            map.path = path;
            while (!map.path.empty() && map.path.front() == ' ') map.path.erase(0, 1);
        }
        if (address >= map.start && address < map.end) return map;
    }
    return std::nullopt;
}

std::string basename(std::string_view path) {
    const size_t slash = path.find_last_of('/');
    return std::string(slash == std::string_view::npos ? path : path.substr(slash + 1));
}

std::optional<Mapping> executable_mapping(pid_t pid, const std::string& filename) {
    std::ifstream maps("/proc/" + std::to_string(pid) + "/maps");
    std::string line;
    while (std::getline(maps, line)) {
        unsigned long start = 0, end = 0, offset = 0;
        char permissions[5]{};
        char path[1024]{};
        int fields = sscanf(line.c_str(), "%lx-%lx %4s %lx %*s %*s %1023[^\n]",
                            &start, &end, permissions, &offset, path);
        if (fields != 5 || strchr(permissions, 'x') == nullptr) continue;
        std::string normalized(path);
        while (!normalized.empty() && normalized.front() == ' ') normalized.erase(0, 1);
        if (basename(normalized) == filename) {
            return Mapping{start, end, offset, normalized};
        }
    }
    return std::nullopt;
}

std::optional<uintptr_t> remote_symbol(pid_t target, const char* symbol) {
    void* local_symbol = dlsym(RTLD_DEFAULT, symbol);
    if (local_symbol == nullptr) return std::nullopt;

    auto local_map = mapping_for_address(getpid(), reinterpret_cast<uintptr_t>(local_symbol));
    if (!local_map || local_map->path.empty()) return std::nullopt;
    auto remote_map = executable_mapping(target, basename(local_map->path));
    if (!remote_map) return std::nullopt;

    // Include file offsets because a symbol and the selected executable segment
    // need not originate at offset zero.
    const uintptr_t file_address = local_map->offset +
        (reinterpret_cast<uintptr_t>(local_symbol) - local_map->start);
    if (file_address < remote_map->offset) return std::nullopt;
    return remote_map->start + (file_address - remote_map->offset);
}

bool get_registers(pid_t pid, user_pt_regs* registers) {
    iovec io{registers, sizeof(*registers)};
    return ptrace(PTRACE_GETREGSET, pid, NT_PRSTATUS, &io) == 0;
}

bool set_registers(pid_t pid, user_pt_regs* registers) {
    iovec io{registers, sizeof(*registers)};
    return ptrace(PTRACE_SETREGSET, pid, NT_PRSTATUS, &io) == 0;
}

std::optional<uintptr_t> remote_call(
    pid_t pid,
    uintptr_t function,
    const std::array<uintptr_t, 8>& arguments) {
    user_pt_regs original{};
    if (!get_registers(pid, &original)) return std::nullopt;
    user_pt_regs call = original;
    for (size_t i = 0; i < arguments.size(); ++i) call.regs[i] = arguments[i];
    call.pc = function;
    call.regs[30] = 0;  // Deliberate fault marks function return.
    if (!set_registers(pid, &call)) return std::nullopt;
    if (ptrace(PTRACE_CONT, pid, nullptr, nullptr) != 0) return std::nullopt;

    int status = 0;
    if (waitpid(pid, &status, __WALL) < 0 || !WIFSTOPPED(status)) {
        set_registers(pid, &original);
        return std::nullopt;
    }
    user_pt_regs result{};
    bool read = get_registers(pid, &result);
    bool restored = set_registers(pid, &original);
    if (!read || !restored) return std::nullopt;
    return result.regs[0];
}

bool write_remote(pid_t pid, uintptr_t destination, const void* source, size_t size) {
    iovec local{const_cast<void*>(source), size};
    iovec remote{reinterpret_cast<void*>(destination), size};
    if (process_vm_writev(pid, &local, 1, &remote, 1, 0) == static_cast<ssize_t>(size)) {
        return true;
    }

    const auto* bytes = static_cast<const uint8_t*>(source);
    for (size_t offset = 0; offset < size; offset += sizeof(long)) {
        long word = 0;
        const size_t chunk = std::min(sizeof(long), size - offset);
        if (chunk != sizeof(long)) {
            errno = 0;
            word = ptrace(PTRACE_PEEKDATA, pid, destination + offset, nullptr);
            if (errno != 0) return false;
        }
        memcpy(&word, bytes + offset, chunk);
        if (ptrace(PTRACE_POKEDATA, pid, destination + offset, word) != 0) return false;
    }
    return true;
}

class TraceSession {
public:
    explicit TraceSession(pid_t pid) : pid_(pid) {}
    bool attach() {
        if (ptrace(PTRACE_ATTACH, pid_, nullptr, nullptr) != 0) return false;
        int status = 0;
        attached_ = waitpid(pid_, &status, __WALL) >= 0 && WIFSTOPPED(status);
        return attached_;
    }
    ~TraceSession() {
        if (attached_) ptrace(PTRACE_DETACH, pid_, nullptr, nullptr);
    }
private:
    pid_t pid_;
    bool attached_ = false;
};
}  // namespace

int main(int argc, char** argv) {
    if (argc != 3) {
        fprintf(stderr, "usage: %s <pid> <absolute-agent-path>\n", argv[0]);
        return 64;
    }
    const pid_t pid = static_cast<pid_t>(strtol(argv[1], nullptr, 10));
    char resolved[PATH_MAX]{};
    if (pid <= 1 || realpath(argv[2], resolved) == nullptr) {
        fprintf(stderr, "invalid pid or agent path: %s\n", strerror(errno));
        return 65;
    }

    TraceSession trace(pid);
    if (!trace.attach()) {
        fprintf(stderr, "ptrace attach failed: %s\n", strerror(errno));
        return 66;
    }
    auto remote_mmap = remote_symbol(pid, "mmap");
    auto remote_dlopen = remote_symbol(pid, "dlopen");
    if (!remote_mmap || !remote_dlopen) {
        fprintf(stderr, "could not resolve remote mmap/dlopen\n");
        return 67;
    }

    const size_t path_size = strlen(resolved) + 1;
    const size_t allocation_size = (path_size + 4095u) & ~4095u;
    std::array<uintptr_t, 8> mmap_args{
        0, allocation_size, PROT_READ | PROT_WRITE,
        MAP_PRIVATE | MAP_ANONYMOUS, static_cast<uintptr_t>(-1), 0, 0, 0};
    auto remote_buffer = remote_call(pid, *remote_mmap, mmap_args);
    if (!remote_buffer || *remote_buffer == 0 || *remote_buffer == static_cast<uintptr_t>(-1)) {
        fprintf(stderr, "remote mmap failed\n");
        return 68;
    }
    if (!write_remote(pid, *remote_buffer, resolved, path_size)) {
        fprintf(stderr, "remote path write failed: %s\n", strerror(errno));
        return 69;
    }

    std::array<uintptr_t, 8> dlopen_args{*remote_buffer, RTLD_NOW | RTLD_GLOBAL, 0, 0, 0, 0, 0, 0};
    auto handle = remote_call(pid, *remote_dlopen, dlopen_args);
    if (!handle || *handle == 0) {
        fprintf(stderr, "remote dlopen returned null\n");
        return 70;
    }
    printf("injected %s into pid %d (handle=0x%lx)\n", resolved, pid, *handle);
    return 0;
}
