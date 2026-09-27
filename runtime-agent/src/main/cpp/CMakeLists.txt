cmake_minimum_required(VERSION 3.22.1)
project(khoirevanced_runtime LANGUAGES C CXX)

add_library(khoirevanced_agent SHARED
    agent.cpp
    native_hook_backend.cpp
)

add_library(lsplant SHARED IMPORTED)
set_target_properties(lsplant PROPERTIES
    IMPORTED_LOCATION
        "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/lsplant-libs/arm64-v8a/liblsplant.so"
    INTERFACE_INCLUDE_DIRECTORIES
        "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/lsplant-libs/include"
)

add_library(dobby STATIC IMPORTED)
set_target_properties(dobby PROPERTIES
    IMPORTED_LOCATION
        "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/dobby-libs/arm64-v8a/libdobby.a"
    INTERFACE_INCLUDE_DIRECTORIES
        "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/dobby-libs/include"
)

target_compile_definitions(khoirevanced_agent PRIVATE
    KHOIREVANCED_VERSION="0.1.0-dev"
)

target_include_directories(khoirevanced_agent PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/lsplant-libs/include"
    "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/dobby-libs/include"
)

target_link_libraries(khoirevanced_agent PRIVATE log dl lsplant dobby)

# Do NOT add a DT_RUNPATH of $ORIGIN to satisfy the liblsplant.so dependency.
#
# The obvious ways all fail here. target_link_options with CMake's LINKER: item
# parser emits "$$ORIGIN"; -Wl,-rpath written directly is escaped to
# "$$$$ORIGIN" in the generated ninja link line and the NDK toolchain then
# reduces that to a literal "$$ORIGIN" in DT_RUNPATH; INSTALL_RPATH with
# BUILD_WITH_INSTALL_RPATH and BUILD_RPATH are both stripped from shared
# libraries by the toolchain. A literal "$$ORIGIN" is worse than no runpath.
#
# The dependency is satisfied instead by the injector preloading liblsplant.so
# into the target's linker namespace before it loads the agent, which is the
# approach the Android linker actually resolves DT_NEEDED against. See the
# comment in native/injector/injector.cpp.
