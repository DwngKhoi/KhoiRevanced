cmake_minimum_required(VERSION 3.22.1)
project(khoirevanced_runtime LANGUAGES C CXX)

add_library(khoirevanced_agent SHARED
    agent.cpp
    native_hook_backend.cpp
)

target_compile_definitions(khoirevanced_agent PRIVATE
    KHOIREVANCED_VERSION="0.1.0-dev"
)

target_link_libraries(khoirevanced_agent PRIVATE log dl)
