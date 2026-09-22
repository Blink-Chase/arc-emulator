cmake_minimum_required(VERSION 3.22.1)

project("arc_native")

add_library(
        arc_native
        SHARED
        native-lib.cpp
        arc_common.cpp
        environment.cpp
        video.cpp
        audio.cpp
        input.cpp
        core.cpp
        vulkan_bridge.cpp
        libretro-common/vulkan/vulkan_symbol_wrapper.c
        # Headers (Listing them fixes IDE analysis errors)
        arc_common.h
        environment.h
        video.h
        audio.h
        input.h
        core.h
        vulkan_bridge.h)

# Include libretro-common headers
target_include_directories(arc_native PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/libretro-common/include)

# Define Android Vulkan platform
add_definitions(-DVK_USE_PLATFORM_ANDROID_KHR)

find_library(
        log-lib
        log)

find_library(android-lib android)
find_library(egl-lib EGL)
find_library(glesv2-lib GLESv2)
find_library(vulkan-lib vulkan)

target_link_libraries(
        arc_native
        ${log-lib}
        ${android-lib}
        ${egl-lib}
        ${glesv2-lib}
        ${vulkan-lib})
