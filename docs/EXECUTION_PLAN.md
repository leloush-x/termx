# TermX execution plan

Status: `[ ]` todo · `[~]` in progress · `[x]` done with green CI.
The AI build prompt (`docs/AI_BUILD_PROMPT.md`) drives this plan. Design details are in `docs/ARCHITECTURE.md`. Choices are in `docs/DECISIONS.md`.

## Owner actions (blocking)

- [x] **GitHub access:** private repo `leloush-x/termx` created and `main` pushed (D20).
- [x] Add the four release-signing secrets listed in README.md. A PKCS12 key was generated outside the repo; owner should make an independent backup of `/root/.config/termx-signing/termx-release.keystore` and its password files.
- [ ] Decide D1 (name and package) and D11 (copyright holder) before the first public distribution. Current values remain placeholders.
- [ ] Optional: decide whether to apply the storage fix to wolfi-terminal now (D19, ARCHITECTURE §2.6).

## Release rule

Every phase ends with a green CI run, a version bump (`0.<n+1>.0`), a merge into `main`, and a `v<version>` tag. The release job publishes the signed APK. `1.0.0` ships with Phase 8.

---

## Phase 0: Skeleton and CI (0.1.0)

- [x] Gradle skeleton on the wolfi-terminal toolchain (AGP 9.2.1, Kotlin 2.3.20, Gradle 9.4.1, compileSdk 37)
- [x] Flavors `play` and `direct`. Signing only from secrets. R8 off (D13)
- [x] CI: unit tests → decode keystore → `assembleDirectRelease` → zipalign `-P 16` → signature report → artifact → tag release with signature and tag checks
- [x] Repo created and pushed
- [x] `main` CI green (run `40231189150`)
- [x] Tag `v0.1.0`, signed release published with the APK (tag run `40231477259`)

**Gate:** green run. Artifact `termx-apk` holds `TermX-0.1.0-direct.apk` and `.sha256`. With secrets set, the release exists and `apksigner verify` passes.

## Phase 1: Terminal surface (0.2.0)

- [x] Vendor termux `terminal-view` (Apache-2.0) under `app.termx.terminal.view`. Keep license headers, add NOTICE entries
- [x] `TerminalSession` runs `/system/bin/sh` through the JitPack emulator
- [x] Soft keyboard input uses a voice-friendly text type (wolfi VOICE_HANDOFF fix)
- [x] Extra-keys row: ESC, TAB, CTRL, ALT, arrows (adapt from wolfi)
- [x] Hardware key handling for Ctrl, Alt, arrows, Home, End, PgUp, PgDn (provided by the vendored view)

**Gate:** Build CI and artifact passed. Emulator attempts on `ubuntu-latest` and `ubuntu-22.04` both timed out before Android reported `sys.boot_completed`; the second run's logs show KVM unavailable. The next attempt uses the resource-optimized `aosp_atd` image (D26). Review its screenshots for `echo hi`, `seq 10000`, and smooth scrolling before merge and tag.

## Phase 2: Distro runtime (0.3.0)

- [ ] `core/proot` native build from wolfi (NDK `29.0.13846066`), `arm64-v8a` and `x86_64`
- [ ] Rootfs install adapted from wolfi `rootfs/Rootfs.kt` and ReTerminal. Java extraction, SHA-256 while streaming, Range resume, atomic switch and rollback copy
- [ ] `ProotCommand` and `BindTable` (T0 binds only)
- [ ] Wolfi default. Alpine, Debian and Void from a signed manifest (Ed25519 check before parsing)
- [ ] proot license and source link shipped in the release and NOTICE

**Gate:** CI builds both ABIs. The 16 KB ELF check passes. On arm64, Wolfi installs and `uname -a` works in a session.

## Phase 3: Storage tiers (0.4.0)

- [ ] `StorageAccess` and `BindTable` by tier: T0 (no permission), T1 (`direct` only, All-files), T2 (SAF workspaces)
- [ ] Storage Health screen: tier status, fix buttons, the session's exact binds
- [ ] One-line hint for `EACCES` errors instead of raw output
- [ ] CI guard: fail on `-b /sdcard` or `-b /storage` outside the T1 path
- [ ] Unit tests for `BindTable` and `StorageAccess`

**Gate:** unit tests green. Manual checklist below passes.

### Storage checklist (manual, run on Android 8 and the newest device available)

| # | Scenario | Expected |
|---|---|---|
| S1 | Fresh install, no grants, open a session | Session starts. The shared folder works. `ls ~/storage` shows a hint, not an error |
| S2 | `direct`, grant All files, open a session | `~/storage/shared` lists `/storage/emulated/0` |
| S3 | Revoke All files with a session open | The open session keeps running. A new session shows the hint. A banner appears |
| S4 | Upgrade with the grant in place | Grant state is kept. Binds are recomputed |
| S5 | `play`: import a file through the system picker | The file lands in the shared folder |
| S6 | Export to Downloads through MediaStore | The file appears in Downloads with no prompt |
| S7 | SAF workspace: copy in and out, reboot | Copies work. The persisted grant survives the reboot |
| S8 | Android 8 device, legacy storage | Works with `WRITE_EXTERNAL_STORAGE` (maxSdk 29) |
| S9 | Newest Android device, all tiers | Works. No deprecated prompts |
| S10 | Deny the grant twice | No prompt loop. A Settings deep link is offered |

## Phase 4: Sessions and background (0.5.0)

- [ ] `SessionService`: foreground, type `specialUse`, subtype property, permission
- [ ] Notification actions: Open, New session, Stop all
- [ ] Start from a visible screen. Handle `ForegroundServiceStartNotAllowedException`
- [ ] Wake policy: partial wake lock only while busy, 30 s grace, off by default
- [ ] Process-death reconnect (same cwd and environment)

**Gate:** 30 minutes in the background on a mid-range device. Reconnect works after a kill.

## Phase 5: Productivity (0.6.0)

- [ ] History hooks for bash and zsh (ARCHITECTURE §8.2), importer, Room + FTS5, redaction, 90-day retention
- [ ] Command palette with stable action IDs
- [ ] Snippets with variables. Insert by default. Run needs a tap and shows the command first
- [ ] Profiles and workspaces
- [ ] File browser: app storage, SAF workspaces, MediaStore Downloads. Share receiver

**Gate:** search over 100,000 rows under 100 ms (benchmark test). Palette opens under 100 ms.

## Phase 6: Customization (0.7.0)

- [ ] Theme JSON with 16 ANSI colors and field-named validation errors. Monet on Android 12+. AMOLED toggle
- [ ] Fonts: bundled JetBrains Mono (OFL), TTF import, loaded off the main thread
- [ ] Keymap and extra-key layouts as JSON (ARCHITECTURE §20.4)
- [ ] DataStore settings. Export and import with secrets excluded. Keystore-backed secrets
- [ ] Backup rules, then enable `allowBackup` (D12)

**Gate:** invalid theme and layout tests pass. A settings export has no secrets.

## Phase 7: Termux options (0.8.0)

- [ ] `pkg` shim (apk, apt, xbps) installed into each distro
- [ ] `termx` helper and owner-only IPC socket: open, share, clip, notify, toast, wake
- [ ] RUN_COMMAND bridge (T1) only after verifying the names against the Termux wiki. Otherwise skip it and log why in DECISIONS

**Gate:** `pkg install` and `pkg search` work on Wolfi. IPC rejects connections from other UIDs (test).

## Phase 8: Hardening and release (1.0.0)

- [ ] R8 enabled with rules, after a release-device smoke test (D13)
- [ ] Macrobenchmark module (startup, frames, scroll). Baseline profile shipped
- [ ] `play` AAB build. F-Droid metadata. LeakCanary in debug only
- [ ] Budgets from ARCHITECTURE §11 measured. Misses explained in DECISIONS

**Gate:** signed APK and AAB, checksums, a clean install of the signed release APK.

---

## Device lab checklist (any phase that touches input or background)

- Keyboards: Gboard, Samsung Keyboard, the Xiaomi keyboard. Commit, compose, delete, voice dictation without Enter
- Hardware keyboard and mouse wheel
- OEM battery settings: HyperOS, ColorOS, One UI, Funtouch, Pixel. Open the battery screen from the guidance card (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`)
- Process death during a session, then reconnect
- Android 8 (API 26) and the newest Android (API 37) emulators, x86_64 and arm64 where available

## Unverified claims (check before relying on them)

- Termux RUN_COMMAND names and the foreground behavior of its service (Phase 7)
- proot GPL version at the pinned commit (Phase 2)
- Storage behavior on Android 17 (Phase 3, device test)
- `specialUse` is not subject to the 6-hour limit of `dataSync` (Phase 4 check)
- OEM kill behavior (Phase 4 and the device lab)
