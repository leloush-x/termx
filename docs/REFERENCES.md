# Pinned references

Every source below was checked during planning (2026-10-26). Pin these commits when you adapt code, and record each adaptation in NOTICE.

| Source | Pinned commit | License | Use it for | Do NOT take |
|---|---|---|---|---|
| termux/termux-app | `8629e63` | GPLv3, with an Apache-2.0 exception for `terminal-view` and `terminal-emulator` (LICENSE.md) | `terminal-view/src/main/java/com/termux/view/` (TerminalView.java, TerminalRenderer.java, GestureAndScaleRecognizer.java, TerminalViewClient.java, textselection/). `terminal-emulator/src/main/java/com/termux/terminal/` (TerminalSession.java, TerminalEmulator.java, JNI.java, KeyHandler.java, TerminalBuffer.java, and others) | `termux-shared/` (GPLv3). Anything outside the two Apache modules |
| jackpal/Android-Terminal-Emulator | `35188f8` | Apache-2.0 (LICENSE, MODULE_LICENSE_APACHE2, NOTICE) | `emulatorview/` as a second reference for emulator behavior and older quirks | Nothing required. Use it when termux's code is unclear |
| RohitKushvaha01/ReTerminal | `839950d` | MIT (Copyright 2024 Rohit Kushvaha) | Terminal screens and rootfs logic: `core/main/src/main/java/com/rk/terminal/ui/screens/terminal/` (Rootfs.kt, TerminalBackEnd.kt, TerminalViewModel.kt, TerminalScreen.kt, TerminalViewLayout.kt, TerminalDrawer.kt, TerminalTopBar.kt). `core/proot/src/main/cpp/` (proot sources, GPL) | The bundled Alpine rootfs assets (`core/main/src/main/assets/*.rootfs`). They add to APK size. Download rootfs at runtime instead |
| leloush-x/wolfi-terminal (the user's project) | `d4cc391` | MIT (project); PRoot CMake subtree GPL-2.0-or-later; embedded talloc LGPL-3.0-or-later | Known-good toolchain (libs.versions.toml, Gradle wrapper, CI actions). `core/main/src/main/java/com/rk/terminal/rootfs/RootfsExtractor.kt` (Java extraction). `core/main/src/main/java/com/rk/terminal/ui/screens/terminal/MkSession.kt` (proot env). `core/proot/src/main/cpp/` (native build). `docs/team/VOICE_HANDOFF.md` (IME fix: input type with TYPE_TEXT_VARIATION_VISIBLE_PASSWORD hid the mic). Extra-keys row. Storage intent in `SettingsScreen.kt` (about line 522) | `core/main/src/main/assets/init-*.sh`: **the unconditional `-b /sdcard` and `-b /storage` binds are the bug** (ARCHITECTURE §2.1). Shizuku, Shevery, rish, `SheveryManager.kt`, `ExecMode.CHROOT` and `SHEVERY`. Its signing with a committed testkey |
| hanxinhao000/ZeroTermux | `27749a7` | GPL (per its README) | Feature ideas only: backup with integrity check, crash history, scheduled jobs, share receivers, mirror switching | Any code. Its AI debug HTTP server, exported timer service, remote menu and script downloads, and committed keys are on the do-not-copy list (ARCHITECTURE §10) |
| bloxbean/ed25519-java | `v0.3.1` | CC0-1.0 | Ed25519 verification on API 26–32 for the signed distro manifest | The older `net.i2p.crypto:eddsa:0.3.0` artifact, which has a signature-malleability flaw |
| Kyant0/AndroidLiquidGlass | `65ab177e90e5c1d8c62e70cf7755841982da65f6` (`io.github.kyant0:backdrop:2.0.1`) | Apache-2.0 | Native Compose backdrop blur, vibrancy, and lens effect for distro landing and terminal side-panel glass | Demo app and unrelated catalog components; consume the library API instead of copying its implementation |
| JetBrains/JetBrainsMono | `19371302b95d218af43299bce79ddbddd0bc364d` | SIL Open Font License 1.1 | `fonts/archives/ttf/JetBrainsMono-Regular.ttf` and `OFL.txt` | Build scripts and unneeded font variants |

## Verified facts used in the plan

- termux-app's Gradle build publishes `terminal-emulator` through JitPack as `com.github.termux.termux-app:terminal-emulator:v0.118.3`. The wolfi-terminal project already depends on it.
- wolfi-terminal targets `compileSdk 37` and `targetSdk 37` with AGP 9.2.1, Kotlin 2.3.20, Gradle 9.4.1, Compose BOM 2025.11.01, and NDK `29.0.13846066`.
- Android's platform `Signature` provider supports Ed25519 starting at API 33; TermX supports API 26, so the pinned strict-verification Java provider is used for manifest checks on every supported API level.
- Android's storage guidance was checked for Phase 3: All-files access is an API 30+ special grant, SAF tree grants can be persisted, and app-owned Downloads writes use MediaStore without a broad storage permission. API 29 T1 is treated as unavailable for this target; this behavior still needs device-matrix validation.
- Android's `ConnectivityManager` and `LinkProperties` provide the active network's DNS server list; TermX uses it to prepare guest `/etc/resolv.conf` without making a network request. Chainguard documents `https://apk.cgr.dev/chainguard` as its public Wolfi APK repository; an HTTP 200 check succeeded on 2026-10-26, so the reported warning is consistent with a transient device/network failure rather than a bad repository URL.
- Compose's `WindowInsets.safeDrawing` and display-cutout guidance is used to keep terminal and installer content clear of cutouts and system UI, including edge-to-edge Android 15+ layouts: [Android window insets](https://developer.android.com/develop/ui/compose/system/insets) and [display cutouts](https://developer.android.com/develop/ui/compose/system/cutouts).
- The installer and storage screens use Material 3 dynamic light/dark color schemes from the system wallpaper on Android 12+, with a palette fallback for older versions, following [Android's Material 3 dynamic color guidance](https://developer.android.com/develop/ui/compose/designsystems/material3).
- Android's [Room guidance](https://developer.android.com/training/data-storage/room) and [KSP setup](https://developer.android.com/build/migrate-to-ksp) inform the local history store. FTS5 needs the [AndroidX bundled SQLite driver](https://developer.android.com/jetpack/androidx/releases/sqlite); Android framework SQLite does not include FTS5.
- The Phase 4 service lifecycle and notification structure adapts wolfi-terminal's `core/main/src/main/java/com/rk/terminal/service/SessionService.kt` at the pinned MIT commit `d4cc391`; the original wake-lock behavior is not reused.
- Phase 4's service type, declaration, subtype property, launch restrictions, and exception handling were checked against [Android foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types), [service launch](https://developer.android.com/develop/background-work/services/fgs/launch), and [background start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).
- wolfi-terminal v1.3.1 shrank with R8 and crashed on launch. Keep R8 off until a release smoke test exists.
- JetBrains Mono's regular TTF is distributed under OFL-1.1; DataStore Preferences 1.2.1 is the stable AndroidX release selected for this phase: [JetBrains Mono license](https://github.com/JetBrains/JetBrainsMono) and [AndroidX DataStore releases](https://developer.android.com/jetpack/androidx/releases/datastore).

## Not verified yet

- Termux RUN_COMMAND intent names and the permission string (the Termux wiki was not reachable during planning). Verify before Phase 7.
- Runtime foreground-service behavior, notification actions, and process-death reconnect on Android 8–17 (Phase 4 device gate).

## Phase 2 rootfs data

- Wolfi, Debian, and Void archives are pinned to the `rootfs-procps-20261023` release of `leloush-x/wolfi-os-rootfs`; architecture-specific sizes and SHA-256 values are in the signed app asset.
- Alpine minirootfs archives are pinned to Alpine 3.24.3 on the official `dl-cdn.alpinelinux.org` mirror; sizes came from HTTPS response headers and SHA-256 values from matching official `.sha256` sidecars.
