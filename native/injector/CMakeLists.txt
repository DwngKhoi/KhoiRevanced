cmake_minimum_required(VERSION 3.22.1)
project(khoirevanced_injector LANGUAGES CXX)

add_executable(khoirevanced-injector injector.cpp)
target_compile_features(khoirevanced-injector PRIVATE cxx_std_20)
target_compile_options(khoirevanced-injector PRIVATE -Wall -Wextra -Werror=return-type)
target_link_libraries(khoirevanced-injector PRIVATE dl log)
