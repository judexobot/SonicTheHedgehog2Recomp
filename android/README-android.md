# Sonic 2 Recomp — Android build

Sideload-oriented Android port of the Sonic 2 native runner (arm64 only).
The gradle project wraps SDL 2.28.5's `android-project` template; the game
ships as `libmain.so` loaded by `org.libsdl.app.SDLActivity`.

## Exact Kura 2 v2 compatibility (experimental)

The first-launch picker accepts your **Kura 2 v2** raw Genesis ROM in
addition to the original **Sonic 2 (World, Rev A)**. It recognizes the hack
strictly by **2,097,152 bytes**, CRC32 **`14412B40`**. No ROM or ROM-derived
data from the hack is embedded in the APK or committed to the fork.

**How it works:** the precompiled 68000 code is tied to the original 1 MiB
Sonic 2 map, so it is unsafe to run against a 2 MiB hack with different
code or resources. Only for the exact Kura 2 v2 image, the Android binary
switches to the clean-room 68000 interpreter already included in the Genesis
runtime (`GENESIS_FORCE_INTERP=1`), and disables the original game's
fixed-address Sonic 2 runtime hooks and experimental widescreen renderer.
The original Sonic 2 remains natively recompiled.

This compatibility path is **experimental**: successful APK compilation does
not establish that the hack boots, progresses through levels, or runs at
full speed on a phone. More optimized native support would require
recompiling from a compatible disassembly of the *modified* program and
separately reviewing its changed addresses.

## Android fullscreen, ultrawide fill and touch controls

- **Immersive landscape:** Status and navigation bars are hidden both
  on the first-launch ROM selector and in SDL gameplay. The SDL surface
  fills the available screen, including Android 9+ display-cutout edges.
  Transient system bars may be brought back with system gestures.
- **Phone aspect ratio:** The SDL presentation automatically uses the
  current actual renderer output aspect ratio, including 19:9 and 20:9
  Galaxy displays. The native 68000/VDP image is stretched to that
  screen width without cropping the frame. **This is presentation fill,
  not true expanded-view widescreen**: it does not show additional
  game world to the left/right and may slightly distort proportions.
  It is deliberately independent of ROM-specific widescreen code hooks.
- **Simple translucent touch controls:** Small four-way D-pad at the
  left, three separate **A / B / C** action buttons at the right, and
  **Start/Pause** in the upper-right. Multi-touch supports diagonals
  and held direction plus A/B/C. Physical controllers are unaffected.
- **Keyboard codes:** The overlay sends SDL's standard input map:
  arrows, Z (A), X (B), C (C) and Enter (Start).

Kura 2's special 68000 interpreter compatibility remains enabled only
for the exact tested Kura 2 v2 ROM identity; Sonic 2 Rev A still uses
native-recompiled 68000 code. The ROM is selected on the phone only,
never required as an Actions secret or embedded in the APK.

## Build an ARM64 APK without supplying your ROM to GitHub

The first-launch Android ROM picker remains the only user ROM prompt.
The APK is built from the pinned `game/s2disasm` source and **does not
bundle a full ROM file**. Its build-time assembly output is discarded.

1. Open **Actions → Android ARM64 (touch)** in this fork.
2. Select **Run workflow** on `master`. Pushes affecting the Android
   build or workflow also request a new build.
3. If the build succeeds, download the
   `Sonic2Recomp-Android-ARM64-Touch` artifact and extract `app-debug.apk`.
4. Install on an ARM64 device. At the **first launch**, choose your own raw
   `Sonic the Hedgehog 2 (World, Rev A)` **or Kura 2 v2** ROM from device storage.
   Later launches reuse it without another picker.

Why this is nontrivial: static recompilation traditionally needs ROM bytes
before compilation to generate the native 68000 C. Here CI assembles the
pinned source disassembly temporarily, verifies CRC32 `7B905383`, and
generates C from it. Only the user's own ROM supplies runtime cartridge
data, and **none of the generated temporary ROMs are uploaded as artifacts**.
The build is experimental and must still be checked on a real phone.


## Dependencies

Run this once from `android/`:

```sh
python tools/setup_deps.py
```

The script SHA-256-verifies SDL 2.28.5 and checks out libucontext at the
reviewed commit `49e671dd52ff6791295d8161ad3b6da7dc5f6f9d`. Both land under
gitignored `external/`; no symlinks or platform-specific checkout settings are
required. libucontext's required ISC notice is packaged in the APK at
`app/src/main/assets/THIRD-PARTY-LICENSES.txt`.

## Layout

- `generated/sonic2/` — host-emitted generated C. The recompiler is a host
  tool and cannot run inside the NDK cross-build, so regenerate by hand after
  ROM/toml/recompiler changes:

  ```sh
  cd ../game
  ../build/GenesisRecomp sonic2.bin --game game.toml \
      --output-dir ../android/generated/sonic2
  ```

  (No `--reverse-debug` — the device build is a stripped native build.)
- `app/jni/src/CMakeLists.txt` — the real target; mirrors the desktop source
  set minus launcher UI, netplay, cosim, reverse-debug.

## Build + install

```sh
export JAVA_HOME="$HOME/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home"
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`assembleRelease` intentionally produces an unsigned APK. Supply a real
release signing configuration before distributing it; local sideload testing
should use `assembleDebug`.

## Device files

Everything lives in the app's external files dir (created on first launch):
`/sdcard/Android/data/tech.clyde.sonic2recomp/files/`

```sh
PKG=tech.clyde.sonic2recomp
DST=/sdcard/Android/data/$PKG/files
adb push sonic2.bin $DST/
adb push annotations_from_disasm.csv $DST/        # crash-report symbols
adb push settings.ini $DST/                       # widescreen = 1 lives here
adb push debug.ini $DST/                          # opens TCP cmd server :4380
adb forward tcp:4380 tcp:4380                     # then probes work from the host
```

The runner (under `__ANDROID__`) anchors all config/saves there and `chdir`s to
it. The launcher writes an absolute, game-specific `rom-Sonic2.cfg`, so other
Genesis images in the same directory cannot win an arbitrary directory scan.

The `adb push` is optional: the launcher activity (`RomGateActivity`) is a
ROM gate. If no ROM is in the files dir it shows an in-app picker (Storage
Access Framework — no storage permissions) and copies the chosen file to
`sonic2.bin` using a rollback-safe replacement, then pins that exact path.
The copy is CRC32-verified against Sonic 2 (World, Rev A) `7B905383`.
A mismatch gets a one-time "use anyway?" warning; approval is remembered only
for that filename and CRC. Changed, unapproved, or unrelated ROMs return to the
picker, while the exact Rev A dump always wins if several raw ROMs are present.
Only raw `.bin`/`.md`/`.gen` images are supported—interleaved SMD data is not
accepted because the runner does not deinterleave it.

Widescreen is not compiled in as a fork: it is the same build with
`widescreen = 1` in `settings.ini` (or `GENESIS_WIDESCREEN=1` on desktop).
Delete the line to get the authentic 4:3 build.

`settings.ini` must also carry `fullscreen = 1`: SDL's Android backend only
enters immersive mode (status bar hidden, true 1920x1080) when the window is
created fullscreen — with `fullscreen = 0` Android keeps the status bar and
hands the app a 1920x1025 surface.

## Dev warp panel (dual-screen devices)

On devices with a presentation-capable second display (AYN Thor: displayId 4
"Screen-2", 1240x1080 touch), the app shows a developer panel there with
two pages, switched by the 🗺/⚙ buttons inline with the header:

- **Status** (default): live HUD polled every ~300 ms — rings, lives,
  emeralds, score, timer, player/camera hex — plus a real minimap: the
  level layout RAM at `$FF8000` ($1000 bytes; even 128-byte half-rows are
  the FG plane, one byte per 128×128 chunk) is fetched in a single 4 KB
  `read_memory` and drawn as a chunk-occupancy silhouette, overlaid with
  the camera viewport, the player dot, and the boss camera-lock line on
  boss acts. Map extent comes from occupancy, not camera bounds (bounds
  read unclamped 0x3FFF in some acts). Tap the map to toggle between
  fit (whole act, letterboxed) and zoom (fills the view height and
  auto-pans to keep the player centered, clamped at the level edges).

  **Full-art minimap (optional, user-supplied):** if
  `<files dir>/maps/<slug>.png` exists for the current act, the panel
  draws that instead of the silhouette (same overlays — sonicgalaxy.net's
  renders are 1:1 with the level pixel grid, so no calibration needed).
  The images are SEGA level art: never committed or shipped, fetch them
  yourself:

  ```sh
  mkdir -p maps
  for m in ehz-1 ehz-2 cpz-1 cpz-2 arz-1 arz-2 cnz-1 cnz-2 htz-1 htz-2 \
           mcz-1 mcz-2 ooz-1 ooz-2 mz-1 mz-2 mz-3 scz-1 wfz-1 dez-1; do
    curl -sf -o maps/$m.png \
      "https://www.sonicgalaxy.net/wp-content/img/maps/gen/sonic2/$m.png"
  done
  adb push maps /sdcard/Android/data/tech.clyde.sonic2recomp/files/
  ```

  Note the site's naming: Metropolis is `mz` (mz-3 = MTZ act 3) and the
  single-act zones are `scz-1`/`wfz-1`/`dez-1`. HPZ has no retail map —
  it (and any missing file) falls back to the silhouette.
- **Warp**: zone/act warp grid, boss warps, live 16:9⇄4:3 toggle,
  save/load slots 1-4 (files interop with the desktop F-keys and the
  LB/RB gamepad quicksave).
- **Cheats** (✨): +1 life / +rings / 7 emeralds (with the HUD-redraw
  flags at $FFFE1C/1D), Super Sonic (seeds the real transformation —
  flag+palette to 1, the game runs the fade and advances the flag
  itself; tops rings up to 50 since super drains 1/s), a permanent
  invincibility toggle (status_secondary bit 1, timer left 0 so the
  monitor countdown never clears it — INV/SHOES badges show on the
  status page), the authentic 20 s speed shoes burst, and character
  swap (Sonic & Tails / Sonic / Tails — sets Player_option at $FFFF72
  and restarts the act through the warp flow, since player art loads
  at level init).

Java only, in `app/src/main/java/tech/clyde/sonic2recomp/`:
`MainActivity` (SDLActivity subclass, Presentation lifecycle) →
`DevPanelPresentation` (programmatic UI, two pages) + `MinimapView` →
`DebugClient` (one owned socket to the runner's TCP cmd server on
127.0.0.1:4380, HandlerThread-serialized, snapshot poller)
→ `WarpEngine` (Java port of `tools/warp.py` — that script stays the
reference; keep them in sync). Boss spots in `Zones.BOSS_SPOTS` were
extracted from the ROM's LevEvents camera-lock writes and verified live.

The panel needs `debug.ini` on the device (it IS the debug server gate);
without it the panel shows "debug server off" and the game runs normally.
The cmd server accepts ONE client: tap **Release** on the panel before
using host-side probes over `adb forward`, and reconnect after. On
single-screen devices nothing changes — the panel simply never shows.

Two hard-won window details: the panel window is `FLAG_NOT_FOCUSABLE` —
Presentation dialogs are focusable by default, and a focusable window on
the bottom screen steals top-display focus on touch, which deprioritizes
the game's window (frame/audio stutter until the top screen is tapped).
And Presentation cancels ITSELF on display config changes (the widescreen
resize used to trip this), so `MainActivity` re-shows the panel on any
dismissal it didn't initiate.

## Credits

Launcher icon: ["Sonic The Hedgehog 2 Dock Icon"](https://www.deviantart.com/lexiloo826/art/Sonic-The-Hedgehog-2-Dock-Icon-1018993923)
by **LexiLoo826** (DeviantArt), used with attribution under
CC BY-NC-ND 3.0 (resized only, per the artist's "feel free to use, just
please credit me"). The mipmap PNGs in `app/src/main/res/` are generated
from it; `tools/make_icon.py` holds the previous hand-drawn fallback icon.
