# Sets the minimum version of CMake required to build your native library.
# This ensures that a certain set of CMake features is available to
# your build.

cmake_minimum_required(VERSION 3.4.1)

# Specifies a library name, specifies whether the library is STATIC or
# SHARED, and provides relative paths to the source code. You can
# define multiple libraries by adding multiple add_library() commands,
# and CMake builds them for you. When you build your app, Gradle
# automatically packages shared libraries with your APK.

add_library( #设置本地库的名字
             native-yuv-to-buffer-lib
             #设置库的类型
             SHARED
             #设置编译路径
             src/main/cpp/native-yuv-to-buffer-lib.cpp)

include_directories(src/main/cpp/)

# 关联自己生成的库和一些第三方库或者系统库，链接多个库
target_link_libraries( # Specifies the target library.
                       native-yuv-to-buffer-lib
                       android
                       log
                     )