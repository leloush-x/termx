# TermX

> A modern, no-root Android terminal for real Linux userlands.

TermX runs Wolfi, Alpine, Debian, and Void through **PRoot**—without root, Shizuku, Shevery, or telemetry. The interface is built with Jetpack Compose and Material 3, with a terminal surface designed to stay readable, fast, and comfortable on phones and hardware keyboards.

<p>
  <a href="https://github.com/leloush-x/termx/releases/latest"><img src="https://img.shields.io/github/v/release/leloush-x/termx?label=latest%20release" alt="Latest release"></a>
  <a href="https://github.com/leloush-x/termx/actions/workflows/android.yml"><img src="https://github.com/leloush-x/termx/actions/workflows/android.yml/badge.svg" alt="Android CI"></a>
  <img src="https://img.shields.io/badge/Android-8%2B-3DDC84" alt="Android 8 or newer">
  <img src="https://img.shields.io/badge/license-MIT-blue" alt="MIT license">
</p>

## Why TermX?

- **Linux on Android, no root required** — install and switch between multiple distros.
- **A calmer terminal experience** — wallpaper stays fixed while the keyboard opens, and settings/terminal surfaces use a subtle readable blend.
- **Reliable mobile input** — tap-hold Ctrl, Alt, and Shift; one-shot taps and normal held modifiers; physical and virtual Enter handling; IME composition support.
- **Useful background sessions** — foreground-service session notifications, reconnect support, grouped background jobs, individual kill, and force-kill for a whole command group.
- **Storage that explains itself** — private storage works by default; optional All-files access and SAF workspaces are shown through Storage Health instead of hidden failing binds.
- **A polished settings hub** — themes, AMOLED mode, fonts, keymaps, wallpapers, profiles, storage health, GitHub CLI login, and process controls.

## Current release: 0.7.15

The `v0.7.15` release is published with a signed direct APK and SHA-256 checksum. Release CI completed successfully, including unit tests, APK assembly, 16 KB ZIP/ELF alignment checks, signature checks, and artifact publishing.

**Download:** [GitHub Releases](https://github.com/leloush-x/termx/releases/tag/v0.7.15)

> Device-specific behavior still needs testing on physical Gboard, Samsung, Xiaomi/HyperOS, SwiftKey, and hardware-keyboard devices. CI proves the source build and tests; it cannot prove OEM keyboard or battery-management behavior.

## Features

### Terminal and input

- Wolfi default distro, with Alpine, Debian, and Void support.
- Extra keys for Esc, Tab, Ctrl, Alt, arrows, Home, End, Page Up, and Page Down.
- Ctrl/Alt/Shift tap-hold behavior: tap for a one-shot modifier, hold for a normal modifier.
- Finished-session Enter handling for both physical and on-screen keyboards.
- IME shadow-line state for suggestions, composition, autocorrect, multi-character commits, and safe reset behavior.
- Fixed wallpaper window background that does not move with IME resize or panning.

### Sessions and jobs

- Foreground session service with Open, New session, and Stop all notification actions.
- Session focus and keyboard opening when switching to or creating a session.
- Background jobs grouped by the basename of their first command token.
- Per-job **Kill**, per-group **Kill all** using SIGKILL, and Stop all from Settings.

### Storage and distro runtime

TermX uses three explicit storage tiers:

| Tier | Access | Purpose |
|---|---|---|
| **T0** | No permission | Private distro home and app-shared storage; available everywhere |
| **T1** | Direct flavor: legacy storage on Android 8–9, All-files on Android 11+ | Optional shared device storage bind |
| **T2** | User-selected SAF folder | Explicit workspace import/export |

The app only adds a bind when its access is active. On Android 11+, All-files access opens system Settings; on Android 8–10, the appropriate legacy permission flow is used. The direct flavor asks for storage access on first launch when it is needed. Notification permission is requested before starting a session on Android 13+ so session controls remain available.

TermX also generates a restricted fake `/proc` sysdata view for tools such as `htop`, while preserving the real process bind where appropriate.

### Settings and appearance

- Material 3 dynamic color and AMOLED mode.
- Wallpaper picker with readable dark blending on settings.
- Bundled JetBrains Mono and imported TTF fonts.
- JSON themes, keymaps, and extra-key layouts.
- Profiles with distro, working directory, and environment settings.
- Searchable settings and safe export/import with secrets excluded.
- Paste a GitHub token into the active terminal for `gh auth login --with-token`; logout is available from Settings.

## Install

1. Download `TermX-0.7.15-direct.apk` from the [release page](https://github.com/leloush-x/termx/releases/tag/v0.7.15).
2. Verify the accompanying `.sha256` file if desired.
3. Install the APK. Android may require allowing installs from the download source.
4. Open TermX, choose a distro, and start a session.

The **direct** APK is the build that can offer All-files access. The Play flavor does not request that permission and should use T0 or T2 storage.

## Build locally

Requirements: JDK 17, Android SDK 37, Android build tools, and the repository's pinned Gradle wrapper.

```bash
./gradlew testDirectDebugUnitTest
./gradlew assembleDirectDebug
```

CI additionally checks native ELF and ZIP alignment, signs release builds when signing secrets are available, and publishes the APK on a matching `v<versionName>` tag.

## Release signing

Configure these GitHub Actions secrets:

| Secret | Value |
|---|---|
| `TERMX_KEYSTORE_B64` | `base64 -w0 termx-release.keystore` |
| `TERMX_KEYSTORE_PASSWORD` | Keystore password |
| `TERMX_KEY_ALIAS` | Key alias |
| `TERMX_KEY_PASSWORD` | Key password |

Keep an independent backup of the keystore. Without it, existing installations cannot receive updates signed with the same key.

## Documentation

- [Architecture](docs/ARCHITECTURE.md) — runtime, storage, UI, and security model
- [Execution plan](docs/EXECUTION_PLAN.md) — delivered work, gates, and remaining device checks
- [Decisions](docs/DECISIONS.md) — recorded product and implementation choices
- [IME matrix](docs/IME_MATRIX.md) — keyboard test checklist and expected behavior
- [Manifest signing](docs/MANIFEST_SIGNING.md) — distro manifest signing notes
- [References](docs/REFERENCES.md) — pinned sources and licenses
- [NOTICE](NOTICE) — third-party notices

## Status and limitations

TermX is actively developed. CI is green for the `v0.7.15` release, but this environment has no physical Android device or reliable emulator. OEM keyboard behavior, process death/reconnect, long background runs, battery policies, and Android 8/37 device compatibility remain manual checks rather than claims of completion.

## License

TermX is licensed under the MIT License. See [LICENSE](LICENSE). Bundled and vendored components retain their respective licenses in [NOTICE](NOTICE).
