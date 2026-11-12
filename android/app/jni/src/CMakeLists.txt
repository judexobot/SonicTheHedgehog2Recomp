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

# The desktop rollback image probe assumes glibc ELF linker symbols such
# as __data_start. Android/Bionic does not export them. Keep rollback and
# offline save logic while disabling only that Linux-specific diagnostic.
file(READ "${RUNNER_ROOT}/rb_probe.c" RB_PROBE_ANDROID_SOURCE)
string(REPLACE
    "#if defined(__linux__) && defined(__GNUC__)"
    "#if defined(__linux__) && defined(__GNUC__) && !defined(__ANDROID__)"
    RB_PROBE_ANDROID_SOURCE "${RB_PROBE_ANDROID_SOURCE}")
set(RB_PROBE_ANDROID_PATH "${CMAKE_CURRENT_BINARY_DIR}/rb_probe_android.c")
file(WRITE "${RB_PROBE_ANDROID_PATH}" "${RB_PROBE_ANDROID_SOURCE}")


# Android-only safe ROM identity gate: canonical recompilation stays unchanged.
# Exact Kura 2 v2 image switches to the existing 68000 interpreter; the
# original compiled 68K offsets must NEVER be executed against a hack.
file(READ "${RUNNER_ROOT}/main.c" ANDROID_MAIN_SOURCE)
set(_loader_anchor "    if (!rom_buf) return 1;")
string(FIND "${ANDROID_MAIN_SOURCE}" "${_loader_anchor}" _loader_pos)
if(_loader_pos EQUAL -1)
    message(FATAL_ERROR "Android: missing engine ROM loader anchor")
endif()
string(REPLACE "${_loader_anchor}"
    "${_loader_anchor}
    const int android_kura2 = rom_raw_len == 0x200000u &&
        rom_crc32(rom_raw, rom_raw_len) == 0x14412B40u;
    if (android_kura2) {
        setenv(\"GENESIS_FORCE_INTERP\", \"1\", 1);
        fprintf(stderr, \"[ANDROID] Kura 2 v2: experimental 68000 interpreter enabled\\n\");
    }"
    ANDROID_MAIN_SOURCE "${ANDROID_MAIN_SOURCE}")
set(_size_orig "rom_raw_len != g_game_spec.expected_rom_size) {")
set(_crc_orig "int accepted = actual_crc == g_game_spec.expected_rom_crc32;")
foreach(_test IN ITEMS "${_size_orig}" "${_crc_orig}")
    string(FIND "${ANDROID_MAIN_SOURCE}" "${_test}" _test_pos)
    if(_test_pos EQUAL -1)
        message(FATAL_ERROR "Android: ROM verification code changed upstream")
    endif()
endforeach()
string(REPLACE "${_size_orig}"
    "rom_raw_len != g_game_spec.expected_rom_size && !android_kura2) {"
    ANDROID_MAIN_SOURCE "${ANDROID_MAIN_SOURCE}")
string(REPLACE "${_crc_orig}"
    "int accepted = actual_crc == g_game_spec.expected_rom_crc32 || android_kura2;"
    ANDROID_MAIN_SOURCE "${ANDROID_MAIN_SOURCE}")
# Keep the pinned Genesis runner's logical-size policy unchanged.
# The ROM-aware Sonic 2 GameVideo renderer expands the *scene* to the
# physical SDL output aspect via video_default="fit". Never stretch a native
# 320x224 frame to make it look ultrawide.
set(ANDROID_MAIN_PATH "${CMAKE_CURRENT_BINARY_DIR}/main_android_compat.c")
file(WRITE "${ANDROID_MAIN_PATH}" "${ANDROID_MAIN_SOURCE}")

# Disable the original game's fixed-address hooks when the ROM is a hack.
file(READ "${GAME_REPO_ROOT}/game/sonic2_spec.c" ANDROID_SPEC_SOURCE)
set(_hook "static int s2_instruction_hook(uint32_t pc)\n{")
string(FIND "${ANDROID_SPEC_SOURCE}" "${_hook}" _hook_pos)
if(_hook_pos EQUAL -1)
    message(FATAL_ERROR "Android: game-spec instruction hook changed upstream")
endif()
string(REPLACE "${_hook}"
    "${_hook}\n    if (getenv(\"GENESIS_FORCE_INTERP\")) return 0;"
    ANDROID_SPEC_SOURCE "${ANDROID_SPEC_SOURCE}")
set(_reset "    .on_post_reset          = s2_sound_post_reset,")
string(FIND "${ANDROID_SPEC_SOURCE}" "${_reset}" _reset_pos)
if(_reset_pos EQUAL -1)
    message(FATAL_ERROR "Android: game-spec reset callback changed upstream")
endif()
string(REPLACE "${_reset}"
    "    .on_post_reset          = s2_android_on_post_reset,"
    ANDROID_SPEC_SOURCE "${ANDROID_SPEC_SOURCE}")
string(REPLACE "const GameSpec g_game_spec = {"
    "static void s2_android_on_post_reset(void) {\n    if (!getenv(\"GENESIS_FORCE_INTERP\")) s2_sound_post_reset();\n}\n\nconst GameSpec g_game_spec = {"
    ANDROID_SPEC_SOURCE "${ANDROID_SPEC_SOURCE}")
set(ANDROID_SPEC_PATH "${CMAKE_CURRENT_BINARY_DIR}/sonic2_spec_android.c")
file(WRITE "${ANDROID_SPEC_PATH}" "${ANDROID_SPEC_SOURCE}")

# Kura 2 uses the same Sonic 2 expanded-world renderer as the stock game.
# Do NOT force it off in interpreter mode. Its guest-specific fixed-address
# instruction hooks remain disabled for Kura, avoiding custom-art DPLC crashes.
add_library(main SHARED
    "${ANDROID_MAIN_PATH}"
    "${RUNNER_ROOT}/diagnostic_replay.c"
    "${RUNNER_ROOT}/engine_worker.c"
    "${RUNNER_ROOT}/host_atomic_file.c"
    "${RUNNER_ROOT}/host_stream.c"
    "${RUNNER_ROOT}/host_wait.c"
    "${RUNNER_ROOT}/load_hold.c"
    "${RUNNER_ROOT}/local_states.c"
    "${RUNNER_ROOT}/overlay_draw.c"
    "${RUNNER_ROOT}/persist_writer.c"
    "${RUNNER_ROOT}/rb_capture.c"
    "${RUNNER_ROOT}/replay_writer.c"
    "${RUNNER_ROOT}/runtime_evidence.c"
    "${RUNNER_ROOT}/video/genesis_hd_gpu.c"
    "${RUNNER_ROOT}/video/genesis_raster_jobs.c"
    "${RUNNER_ROOT}/sim_step.c"
    "${RUNNER_ROOT}/rb_state.c"
    "${RB_PROBE_ANDROID_PATH}"
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
    "${GAME_REPO_ROOT}/recomp-ui/src/common/sha256.c"
    "${ANDROID_SPEC_PATH}"
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

# Local single-player rewind/save-state UI needs the small, dependency-free
# snapshot ring. Netplay remains disabled in this Android build.
set(RBE_BUILD_TESTS OFF CACHE BOOL "" FORCE)
add_subdirectory("${RECOMP_ROOT}/external/rbengine"
                 "${CMAKE_CURRENT_BINARY_DIR}/rbengine" EXCLUDE_FROM_ALL)
target_link_libraries(main SDL2 SDL2main log GLESv2 retcomm_rbengine)
target_link_options(main PRIVATE "-Wl,--error-limit=0")
