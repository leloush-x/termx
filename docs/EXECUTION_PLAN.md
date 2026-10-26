# TermX execution plan

Status: `[ ]` todo · `[~]` in progress · `[x]` done with green CI.
The AI build prompt (`docs/AI_BUILD_PROMPT.md`) drives this plan. Design details are in `docs/ARCHITECTURE.md`. Choices are in `docs/DECISIONS.md`.

## Owner actions (blocking)

- [x] **GitHub access:** private repo `leloush-x/termx` created and `main` pushed (D20).
- [x] Add the four release-signing secrets listed in README.md. A PKCS12 key was generated outside the repo; owner should make an independent backup of `/root/.config/termx-signing/termx-release.keystore` and its password files.
- [ ] Make an owner-controlled backup of `/root/.config/termx-manifest/manifest_ed25519`; future signed manifest updates require it (`docs/MANIFEST_SIGNING.md`).
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

**Gate:** Build/test CI and signed APK artifact passed. Hosted emulator checks were not reliable here: AOSP ATD produced identical black screenshots, while the standard API 30 image failed APK installation with `Failure calling service package: Broken pipe (32)` (D29). On 2026-10-26, the owner directed progression with the device check unverified (D30); this authorization permits merging and tagging the candidate, but it does not establish that `echo hi` or smooth `seq 10000` scrolling passed on a device.

## Phase 2: Distro runtime (0.3.0) `[~]`

- [x] `core/proot` native build from wolfi (NDK `29.0.13846066`), `arm64-v8a` and `x86_64` (CI run `40256209637`; all four ELF files pass 16 KB alignment)
- [~] Rootfs install adapted from wolfi `rootfs/RootfsExtractor.kt`. Java extraction, header and payload validation, SHA-256 while streaming, Range resume, staged switch and rollback copy
- [~] `ProotCommand` and `BindTable` (T0 binds only)
- [~] Wolfi default. Alpine, Debian and Void from a signed manifest (Ed25519 check before parsing)
- [~] PRoot licenses, source link and copied-source NOTICE records prepared for release packaging

**Gate:** CI builds both ABIs. The 16 KB ELF check passes. On arm64, Wolfi installs and `uname -a` works in a session.

The arm64 install/session check is pending. GitHub's ARM64 runner image does not include the Android SDK, and software-emulating the ARM64 Android image on x86_64 did not boot within 12 minutes (D34). The owner directed progression to Phase 3 with this check unverified (D36). Run the install and `uname -a` check on a physical ARM64 Android device or a supported ARM64-host emulator before claiming the runtime gate passed (D35).

## Phase 3: Storage tiers (0.4.0) `[~]`

- [x] `StorageAccess` and `BindTable` by tier: T0 (no permission), T1 (`direct` only), T2 (SAF workspaces)
- [x] Storage Health screen: tier status, access actions, workspace copies, and the session's exact binds
- [x] One-line hint for permission failures in storage actions
- [x] CI guard: reject storage binds outside the tier-aware `BindTable`
- [x] Unit tests for `BindTable` and `StorageAccess`
- [x] Feed Android's active DNS servers into the guest resolver and keep terminal/install UI inside safe drawing insets
- [x] Start Wolfi with interactive Bash and an editable colored prompt when the distro has no existing shell startup files

**Gate:** unit tests are green. The manual S1–S10 checks on Android 8 and the newest available Android device remain unverified. The owner directed progression to Phase 4 (D41); this does not establish that the device gate passed.

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
| S8 | Android 8 device, legacy storage | T1 works after `READ_EXTERNAL_STORAGE` and `WRITE_EXTERNAL_STORAGE` are granted in the direct flavor (maxSdk 28) |
| S9 | Newest Android device, all tiers | Works. No deprecated prompts |
| S10 | Deny the grant twice | No prompt loop. A Settings deep link is offered |

## Phase 4: Sessions and background (0.5.0) `[~]`

- [x] `SessionService`: foreground, type `specialUse`, subtype property, permission
- [x] Notification actions: Open, New session, Stop all
- [x] Start from a visible screen. Handle `ForegroundServiceStartNotAllowedException`
- [x] Wake policy: partial wake lock only while output arrives, 30 s grace, off by default
- [x] Process-death reconnect (same cwd and environment)

**Gate:** 30 minutes in the background on a mid-range device and reconnect after a process kill remain unverified. The owner directed progression to Phase 5 (D44); this does not establish the device gate passed.

## Phase 5: Productivity (0.6.0)

- [~] Bash and zsh history hooks, private TSV spool, Room importer, bundled SQLite FTS5 index, default and editable redaction rules, 90-day retention
- [~] Material 3 command palette with stable action IDs and fuzzy matching
- [~] Variable snippets insert by default; run shows a confirmation with the resolved command
- [~] App-private profiles for distro, working directory and environment; existing SAF workspaces remain explicit copies
- [~] File browser for shared app storage, SAF workspaces and MediaStore Downloads; non-exported FileProvider sharing and incoming share intents
- [x] Compact distro picker with distro-specific color accents, a single glass selection rail, selected-distro persistence, and install progress
- [x] Saved-session reconnect state prevents the landing screen flashing before the terminal attaches
- [x] Minimal terminal chrome and animated session drawer with the current terminal, a new-terminal action, installed-distro selection, and Settings; Back returns to the drawer instead of closing TermX

**Gate:** CI compilation, 100,000-row search under 100 ms, palette under 100 ms, and file/share behavior remain pending. The owner directed promotion to `main` and continuation to Phase 6 (D47, D50); this does not claim the Phase 5 gate passed, and `v0.6.0` remains untagged until verification.

## Phase 6: Customization (0.7.0) `[~]`

- [x] Theme JSON with 16 ANSI colors and field-named validation errors. AMOLED toggle
- [x] Material 3 Material You dynamic light/dark palette on Android 12+, following system appearance
- [x] Fonts: bundled JetBrains Mono (OFL), TTF import, loaded off the main thread
- [x] Keymap and extra-key layouts as JSON (ARCHITECTURE §20.4)
- [x] DataStore settings. Export and import with secrets excluded. Keystore-backed secrets
- [x] Backup rules, then enable `allowBackup` (D12)

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
- Storage behavior on Android 17 (Phase 3, device test)
- `specialUse` is not subject to the 6-hour limit of `dataSync` (Phase 4 check)
- OEM kill behavior (Phase 4 and the device lab)
