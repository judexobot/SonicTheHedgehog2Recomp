cmake_minimum_required(VERSION 3.16)

project(Sonic2RecompAndroid C CXX ASM)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# ---------------------------------------------------------------------------
# Sonic 2 Android target — arm64 libmain.so consumed by org.libsdl.app.
#
# Mirrors the desktop CMakeLists source set (native target) with the
# desktop-only pieces dropped: no recomp-ui launcher, no netplay, no cosim,
# reverse-debug OFF. Generated C is NOT produced here — the host recompiler
# cannot run under an NDK cross-build, so android/generated/sonic2/ is
# emitted beforehand on the host (see android/README-android.md).
# ---------------------------------------------------------------------------

get_filename_component(ANDROID_PROJ_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../.." ABSOLUTE)
get_filename_component(GAME_REPO_ROOT    "${ANDROID_PROJ_ROOT}/.." ABSOLUTE)
set(RECOMP_ROOT   "${GAME_REPO_ROOT}/segagenesisrecomp")
set(RUNNER_ROOT   "${RECOMP_ROOT}/runner")
set(GEN_DIR       "${ANDROID_PROJ_ROOT}/generated/sonic2")

# The desktop CMake target generates this stamp; Android is configured
# independently and must provide it too (needed by sonic2_spec.c).
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/sonic2_state_build.h"
    [=[#define SONIC2_STATE_BUILD_ID "sonic2-android-arm64-v1"
]=])

# SDL's Android source tree exposes <SDL.h>, while the shared Genesis runner
# includes <SDL2/SDL.h>. Build a private, disposable include layout.
set(SDL_INCLUDE_SHIM "${CMAKE_CURRENT_BINARY_DIR}/sdl-include")
file(MAKE_DIRECTORY "${SDL_INCLUDE_SHIM}/SDL2")
file(COPY "${SDL_ROOT}/include/" DESTINATION "${SDL_INCLUDE_SHIM}/SDL2")


if(NOT EXISTS "${GEN_DIR}/sonic2_dispatch.c")
    message(FATAL_ERROR "Pre-generated C missing at ${GEN_DIR} — run the host "
        "GenesisRecomp into that directory first (see android/README-android.md)")
endif()

file(GLOB SONIC2_GENERATED_SOURCES "${GEN_DIR}/sonic2_part*.c")
list(APPEND SONIC2_GENERATED_SOURCES
    "${GEN_DIR}/sonic2_dispatch.c"
    "${GEN_DIR}/sonic2_layout.c")

# fiber_compat.c runs on the vendored minicoro in assembly mode (aarch64
# supported): libucontext is no longer needed (engine netplay-4p work).
set(FM_SOURCES
    "${RUNNER_ROOT}/audio/ym2612_ymfm.cpp"
    "${RUNNER_ROOT}/external/ymfm/src/ymfm_opn.cpp"
    "${RUNNER_ROOT}/external/ymfm/src/ymfm_ssg.cpp"
    "${RUNNER_ROOT}/external/ymfm/src/ymfm_adpcm.cpp"
    "${RUNNER_ROOT}/external/ymfm/src/ymfm_pcm.cpp")

add_library(main SHARED
    "${RUNNER_ROOT}/main.c"
    "${RUNNER_ROOT}/sim_step.c"
    "${RUNNER_ROOT}/rb_state.c"
    "${RUNNER_ROOT}/rb_probe.c"
    "${RUNNER_ROOT}/cosim_state.c"
    "${RUNNER_ROOT}/audio.c"
    "${RUNNER_ROOT}/cmd_server.c"
    "${RUNNER_ROOT}/frame_snapshots.c"
    "${RUNNER_ROOT}/glue.c"
    "${RUNNER_ROOT}/fiber_compat.c"
    "${RUNNER_ROOT}/crash_report.c"
    "${RUNNER_ROOT}/input_script.c"
    "${RUNNER_ROOT}/gamepad.c"
    "${RUNNER_ROOT}/input_map.c"
    "${RUNNER_ROOT}/app_config.c"
    "${RUNNER_ROOT}/chip_trace.c"
    "${RUNNER_ROOT}/audio/event_queue.c"
    ${FM_SOURCES}
    "${RUNNER_ROOT}/audio/sn76489.c"
    "${RUNNER_ROOT}/audio/mixer.c"
    "${RUNNER_ROOT}/audio/observability.c"
    "${RUNNER_ROOT}/audio/audio_shadow.c"
    "${RUNNER_ROOT}/audio/fm_shadow.cpp"
    "${RUNNER_ROOT}/video/color_lut.c"
    # clean-room own backend
    "${RUNNER_ROOT}/video/genesis_vdp.c"
    "${RUNNER_ROOT}/video/genesis_bus.c"
    "${RUNNER_ROOT}/video/genesis_machine.c"
    "${RUNNER_ROOT}/external/superzazu/z80.c"
    # Tier-3 floor (glue.c references m68k_interp_run_framed unconditionally)
    "${RUNNER_ROOT}/m68k_interp.c"
    "${RECOMP_ROOT}/recompiler/src/m68k_decoder.c"
    "${RECOMP_ROOT}/recompiler/src/rom_parser.c"
    ${SONIC2_GENERATED_SOURCES}
    # Keep Android's Sonic 2 hooks in sync with the desktop native target.
    "${GAME_REPO_ROOT}/game/sonic2_spec.c"
    "${GAME_REPO_ROOT}/game/sonic2_video.c"
    "${GAME_REPO_ROOT}/game/sonic2_party.c"
    "${GAME_REPO_ROOT}/game/sonic2_options.c"
    "${GAME_REPO_ROOT}/game/sonic2_sound_driver.c"
    "${GAME_REPO_ROOT}/game/sonic2_sound_mods.c"
    "${GAME_REPO_ROOT}/game/sonic2_sound_assets.c"
    "${GAME_REPO_ROOT}/game/sonic2_donor_assets.c"
    "${GAME_REPO_ROOT}/game/sonic2_resources.c"
    "${GAME_REPO_ROOT}/game/sonic2_save_assets.c"
    "${GAME_REPO_ROOT}/game/sonic2_save_draw.c"
    "${GAME_REPO_ROOT}/game/sonic2_save_menu.c"
    "${GAME_REPO_ROOT}/game/sonic2_campaign_file.c"
    "${GAME_REPO_ROOT}/game/sonic2_campaign.c"
    "${GAME_REPO_ROOT}/game/sonic2_campaign_store.c"
    "${GAME_REPO_ROOT}/game/sonic2_mods.c"
    "${GAME_REPO_ROOT}/game/sonic2_runtime.c"
    "${GAME_REPO_ROOT}/game/sonic2_super.c"
    "${GAME_REPO_ROOT}/game/sonic2_character.c"
    "${GAME_REPO_ROOT}/game/sonic2_shadow.c"
    "${GAME_REPO_ROOT}/game/sonic2_shadow_roster.c"
    "${GAME_REPO_ROOT}/game/sonic2_mighty.c")

target_compile_definitions(main PRIVATE
    INCLUDE_GENERATED_CODE=1
    ENABLE_RECOMPILED_CODE=1
    OWN_BACKEND=1
    GEN_ENABLE_TRACE=1
    DEFAULT_DEBUG_PORT=4380)

target_include_directories(main PRIVATE
    "${RUNNER_ROOT}"
    "${RUNNER_ROOT}/include"
    "${RUNNER_ROOT}/video"
    "${RUNNER_ROOT}/external/ymfm/src"
    "${RUNNER_ROOT}/external/superzazu"
    "${RUNNER_ROOT}/external/clowncommon"
    "${GAME_REPO_ROOT}/game"
    "${GAME_REPO_ROOT}/recomp-ui/src/common"
    "${CMAKE_CURRENT_BINARY_DIR}"
    "${SDL_INCLUDE_SHIM}"
    "${SDL_ROOT}/include"
    "${RECOMP_ROOT}/recompiler/src"
    "${GEN_DIR}"
    # The compatibility include shim above provides the SDL2/SDL.h layout.
    )

target_link_libraries(main SDL2 SDL2main log GLESv2)
