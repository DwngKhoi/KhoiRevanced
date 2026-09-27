cmake_minimum_required(VERSION 3.22.1)
project(khoirevanced_runtime LANGUAGES C CXX)

# The agent no longer contains a hook engine. It starts a bootstrap thread,
# stages the payload and hands over to the Kotlin side, which starts Pine from
# third_party/pine-libs and installs the hook backend. libpine.so is not linked
# here: it is dlopened by Pine itself through PineConfig.libLoader, which is what
# lets its JNI_OnLoad resolve top/canyie/pine/Pine from the agent's class loader.
add_library(khoirevanced_agent SHARED agent.cpp)

target_compile_definitions(khoirevanced_agent PRIVATE
    KHOIREVANCED_VERSION="0.1.0-dev"
)

target_link_libraries(khoirevanced_agent PRIVATE log dl)
