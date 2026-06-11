# 3Beans for Android

An Android frontend for the 3Beans low-level 3DS emulator core, modeled on
[Azahar](https://github.com/azahar-emu/azahar)'s Android project structure with
a custom Material 3 interface.

## Features

- Game library scanned from a folder you pick (`.3ds` / `.cci` cart ROMs)
- Booting the home menu directly, or carts via auto-boot
- Software-rendered video on a GLES surface with portrait and landscape layouts
- Audio output paced against the core's FPS limiter
- Custom-drawn touch controls (circle pad, D-pad, A/B/X/Y, L/R, Start/Select,
  Home) with scale, opacity, and haptics options, plus bottom-screen touch
- Physical gamepad and keyboard support
- Core settings (DSP backend, threaded GPU, console type, …) exposed in a
  Material settings screen
- `vanilla` and `googlePlay` product flavors, mirroring Azahar's release setup

The desktop OpenGL renderer is not available on Android (it targets desktop
GL 3.3); the software renderer is always used. Features the core does not
implement (save states, cheats, etc.) are likewise unavailable.

## System files

3Beans is a low-level emulator and boots from real console files dumped with
[GodMode9](https://github.com/d0k3/GodMode9):

| File | Purpose | Required |
| --- | --- | --- |
| `boot11.bin` | ARM11 boot ROM | Yes |
| `boot9.bin` | ARM9 boot ROM | Yes |
| `nand.bin` | GodMode9 NAND dump (with embedded CID/OTP) | For booting the home menu |
| `sd.img` | FAT-formatted SD card image | Optional |

Pick each file in the app under **System files** (any storage location works;
access uses the Storage Access Framework), or place them manually in the app's
external files directory (`Android/data/com.hydra.threebeans/files`).

Cart saves (`.sav`) are written to the app's private storage next to a symlink
of the ROM, so picked ROMs never need write access.

## Building locally

```bash
cd src/android
./gradlew assembleVanillaRelease      # or assembleGooglePlayRelease
```

Requires JDK 17. The Android SDK manager supplies the NDK and CMake versions
declared in `app/build.gradle.kts` automatically.

## CI pipeline

`.github/workflows/android.yml` runs:

- weekly (Mondays 06:00 UTC) and on manual dispatch: merges the latest
  [upstream 3Beans](https://github.com/Hydr8gon/3Beans) `main` into the fork,
  pushes the merge, then builds both flavors
- on pushes touching the core or Android frontend: builds both flavors

Successful runs update the **Android Rolling Release** (`android` tag) with
`3beans-android-vanilla.apk` and `3beans-android-googleplay.apk`.

> GitHub disables cron triggers in repositories with no activity for 60 days;
> re-enable from the Actions tab if upstream goes quiet for that long.

### Release signing

Without secrets configured, CI produces debug-signed APKs (installable, but
each keystore change breaks in-place updates). For stable signing, create a
keystore once:

```bash
keytool -genkeypair -keystore 3beans.jks -alias 3beans \
    -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 3beans.jks   # value for ANDROID_KEYSTORE_B64
```

and add these repository secrets:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_B64` | base64 of the keystore file |
| `ANDROID_KEY_ALIAS` | key alias (e.g. `3beans`) |
| `ANDROID_KEYSTORE_PASS` | keystore/key password |

## Upstream merges

Everything Android-specific lives in `src/android/` and
`.github/workflows/android.yml`, so weekly merges from upstream cannot
conflict with the port. The emulator core in `src/core` is compiled
unmodified; `app/src/main/cpp/shim/epoxy/gl.h` satisfies the desktop GL
renderer's includes with inert stubs (it is never instantiated on Android).
If upstream adds new GL calls, the Android build fails loudly and the shim
needs the matching stub added.
