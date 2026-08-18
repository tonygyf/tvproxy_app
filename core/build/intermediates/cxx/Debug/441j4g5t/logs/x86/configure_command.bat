@echo off
"C:\\Users\\15634\\AppData\\Local\\Android\\Sdk\\cmake\\3.22.1\\bin\\cmake.exe" ^
  "-HD:\\typer\\cursor project\\tvproxy_app\\core\\src\\main\\cpp" ^
  "-DCMAKE_SYSTEM_NAME=Android" ^
  "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON" ^
  "-DCMAKE_SYSTEM_VERSION=24" ^
  "-DANDROID_PLATFORM=android-24" ^
  "-DANDROID_ABI=x86" ^
  "-DCMAKE_ANDROID_ARCH_ABI=x86" ^
  "-DANDROID_NDK=C:\\Users\\15634\\AppData\\Local\\Android\\Sdk\\ndk\\27.0.12077973" ^
  "-DCMAKE_ANDROID_NDK=C:\\Users\\15634\\AppData\\Local\\Android\\Sdk\\ndk\\27.0.12077973" ^
  "-DCMAKE_TOOLCHAIN_FILE=C:\\Users\\15634\\AppData\\Local\\Android\\Sdk\\ndk\\27.0.12077973\\build\\cmake\\android.toolchain.cmake" ^
  "-DCMAKE_MAKE_PROGRAM=C:\\Users\\15634\\AppData\\Local\\Android\\Sdk\\cmake\\3.22.1\\bin\\ninja.exe" ^
  "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=D:\\typer\\cursor project\\tvproxy_app\\core\\build\\intermediates\\cxx\\Debug\\441j4g5t\\obj\\x86" ^
  "-DCMAKE_RUNTIME_OUTPUT_DIRECTORY=D:\\typer\\cursor project\\tvproxy_app\\core\\build\\intermediates\\cxx\\Debug\\441j4g5t\\obj\\x86" ^
  "-DCMAKE_BUILD_TYPE=Debug" ^
  "-BD:\\typer\\cursor project\\tvproxy_app\\core\\.cxx\\Debug\\441j4g5t\\x86" ^
  -GNinja ^
  "-DGO_SOURCE:STRING=D:\\typer\\cursor project\\tvproxy_app\\core\\src\\main\\golang\\native" ^
  "-DGO_OUTPUT:STRING=D:\\typer\\cursor project\\tvproxy_app\\core\\build\\outputs\\golang" ^
  "-DFLAVOR_NAME:STRING=alpha"
