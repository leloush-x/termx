# Pinned references

Every source below was checked during planning (2026-10-26). Pin these commits when you adapt code, and record each adaptation in NOTICE.

| Source | Pinned commit | License | Use it for | Do NOT take |
|---|---|---|---|---|
| termux/termux-app | `8629e63` | GPLv3, with an Apache-2.0 exception for `terminal-view` and `terminal-emulator` (LICENSE.md) | `terminal-view/src/main/java/com/termux/view/` (TerminalView.java, TerminalRenderer.java, GestureAndScaleRecognizer.java, TerminalViewClient.java, textselection/). `terminal-emulator/src/main/java/com/termux/terminal/` (TerminalSession.java, TerminalEmulator.java, JNI.java, KeyHandler.java, TerminalBuffer.java, and others) | `termux-shared/` (GPLv3). Anything outside the two Apache modules |
| jackpal/Android-Terminal-Emulator | `35188f8` | Apache-2.0 (LICENSE, MODULE_LICENSE_APACHE2, NOTICE) | `emulatorview/` as a second reference for emulator behavior and older quirks | Nothing required. Use it when termux's code is unclear |
| RohitKushvaha01/ReTerminal | `839950d` | MIT (Copyright 2024 Rohit Kushvaha) | Terminal screens and rootfs logic: `core/main/src/main/java/com/rk/terminal/ui/screens/terminal/` (Rootfs.kt, TerminalBackEnd.kt, TerminalViewModel.kt, TerminalScreen.kt, TerminalViewLayout.kt, TerminalDrawer.kt, TerminalTopBar.kt). `core/proot/src/main/cpp/` (proot sources, GPL) | The bundled Alpine rootfs assets (`core/main/src/main/assets/*.rootfs`). They add to APK size. Download rootfs at runtime instead |
| leloush-x/wolfi-terminal (the user's project) | `d4cc391` | MIT | Known-good toolchain (libs.versions.toml, Gradle wrapper, CI actions). `core/main/src/main/java/com/rk/terminal/rootfs/Rootfs.kt` (Java extraction, ExecMode). `core/main/src/main/java/com/rk/terminal/ui/screens/terminal/MkSession.kt` (proot env). `core/proot/` CMake. `docs/team/VOICE_HANDOFF.md` (IME fix: input type with TYPE_TEXT_VARIATION_VISIBLE_PASSWORD hid the mic). Extra-keys row. Storage intent in `SettingsScreen.kt` (about line 522) | `core/main/src/main/assets/init-*.sh`: **the unconditional `-b /sdcard` and `-b /storage` binds are the bug** (ARCHITECTURE §2.1). Shizuku, Shevery, rish, `SheveryManager.kt`, `ExecMode.CHROOT` and `SHEVERY`. Its signing with a committed testkey |
| hanxinhao000/ZeroTermux | `27749a7` | GPL (per its README) | Feature ideas only: backup with integrity check, crash history, scheduled jobs, share receivers, mirror switching | Any code. Its AI debug HTTP server, exported timer service, remote menu and script downloads, and committed keys are on the do-not-copy list (ARCHITECTURE §10) |

## Verified facts used in the plan

- termux-app's Gradle build publishes `terminal-emulator` through JitPack as `com.github.termux.termux-app:terminal-emulator:v0.118.3`. The wolfi-terminal project already depends on it.
- wolfi-terminal targets `compileSdk 37` and `targetSdk 37` with AGP 9.2.1, Kotlin 2.3.20, Gradle 9.4.1, Compose BOM 2025.11.01, and NDK `29.0.13846066`.
- wolfi-terminal v1.3.1 shrank with R8 and crashed on launch. Keep R8 off until a release smoke test exists.

## Not verified yet

- Termux RUN_COMMAND intent names and the permission string (the Termux wiki was not reachable during planning). Verify before Phase 7.
- Exact proot GPL version (check the headers in `core/proot/src/main/cpp/` at the pinned commit).
- Android docs on storage and foreground-service types. Verify on device during Phases 3 and 4.
