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
