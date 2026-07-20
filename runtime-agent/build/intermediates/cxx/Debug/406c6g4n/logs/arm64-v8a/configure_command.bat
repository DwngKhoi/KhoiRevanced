@echo off
"C:\\Users\\Khoi\\AppData\\Local\\Android\\Sdk\\cmake\\3.22.1\\bin\\cmake.exe" ^
  "-HD:\\khoirevanced\\KhoiRevanced\\runtime-agent\\src\\main\\cpp" ^
  "-DCMAKE_SYSTEM_NAME=Android" ^
  "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON" ^
  "-DCMAKE_SYSTEM_VERSION=27" ^
  "-DANDROID_PLATFORM=android-27" ^
  "-DANDROID_ABI=arm64-v8a" ^
  "-DCMAKE_ANDROID_ARCH_ABI=arm64-v8a" ^
  "-DANDROID_NDK=C:\\Users\\Khoi\\AppData\\Local\\Android\\Sdk\\ndk\\25.2.9519653" ^
  "-DCMAKE_ANDROID_NDK=C:\\Users\\Khoi\\AppData\\Local\\Android\\Sdk\\ndk\\25.2.9519653" ^
  "-DCMAKE_TOOLCHAIN_FILE=C:\\Users\\Khoi\\AppData\\Local\\Android\\Sdk\\ndk\\25.2.9519653\\build\\cmake\\android.toolchain.cmake" ^
  "-DCMAKE_MAKE_PROGRAM=C:\\Users\\Khoi\\AppData\\Local\\Android\\Sdk\\cmake\\3.22.1\\bin\\ninja.exe" ^
  "-DCMAKE_CXX_FLAGS=-std=c++20 -Wall -Wextra -Werror=return-type" ^
  "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=D:\\khoirevanced\\KhoiRevanced\\runtime-agent\\build\\intermediates\\cxx\\Debug\\406c6g4n\\obj\\arm64-v8a" ^
  "-DCMAKE_RUNTIME_OUTPUT_DIRECTORY=D:\\khoirevanced\\KhoiRevanced\\runtime-agent\\build\\intermediates\\cxx\\Debug\\406c6g4n\\obj\\arm64-v8a" ^
  "-DCMAKE_BUILD_TYPE=Debug" ^
  "-BD:\\khoirevanced\\KhoiRevanced\\runtime-agent\\.cxx\\Debug\\406c6g4n\\arm64-v8a" ^
  -GNinja ^
  "-DANDROID_STL=c++_static"
