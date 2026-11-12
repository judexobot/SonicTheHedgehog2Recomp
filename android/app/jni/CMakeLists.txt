cmake_minimum_required(VERSION 3.16)

project(GAME)

# Android 15/16 devices can have 16 KiB memory pages. NDK r25 does
# not enable this ELF segment alignment by default, so apply it to both
# the main game library and SDL2 (which is built below as a subdirectory).
# This is harmless on conventional 4 KiB devices.
set(CMAKE_SHARED_LINKER_FLAGS
    "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

get_filename_component(ANDROID_PROJ_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../.." ABSOLUTE)
set(SDL_ROOT "${ANDROID_PROJ_ROOT}/external/SDL2-2.28.5")

if(NOT EXISTS "${SDL_ROOT}/CMakeLists.txt")
    message(FATAL_ERROR
        "SDL2 source missing at ${SDL_ROOT}. Run android/tools/setup_deps.py.")
endif()

# Use explicit source/binary directories instead of committed symlinks. This
# works with default Git settings on Windows as well as macOS/Linux.
add_subdirectory("${SDL_ROOT}" "${CMAKE_CURRENT_BINARY_DIR}/SDL")
add_subdirectory(src)
