# Decision log

Format: ID · decision · why · status. Add a new row whenever a choice is made. Never rewrite an old row; add a superseding row.

| ID | Decision | Why | Status |
|---|---|---|---|
| D1 | Name `TermX`, application ID `app.termx` (placeholder) | Neutral name. Rename before any Play upload, because the ID is permanent once published | Open: owner may rename |
| D2 | Two flavors: `play` (no All-files permission) and `direct` (GitHub and F-Droid, adds All-files access in Phase 3). Releases come from `direct` first | The Play policy blocks broad storage permissions for most apps. Direct builds can ship now | Accepted |
| D3 | `minSdk 26`, `compileSdk 37`, `targetSdk 37` | Same as wolfi-terminal, which already ships on 37. Android 8 is the lowest version the plan supports | Accepted (supersedes the targetSdk 36 line in ARCHITECTURE §3.1) |
| D4 | Terminal engine: `terminal-emulator` from JitPack at v0.118.3. `terminal-view` vendored under Apache-2.0 in Phase 1 | Wolfi already consumes the emulator this way. The view needs IME changes (VOICE_HANDOFF.md) | Accepted |
| D5 | Distros: Wolfi default. Alpine, Debian and Void download on demand from a signed manifest. No rootfs in the APK | Keeps the APK small (budget ≤10 MB). ReTerminal's bundled Alpine assets are the size problem to avoid | Accepted |
| D6 | proot runs from `nativeLibraryDir` with `useLegacyPackaging = true`. Built from the wolfi `core/proot` CMake with NDK 29 | Only no-root way to run distros on targetSdk 37 (already proven in wolfi-terminal) | Accepted |
| D7 | Storage: tiers T0 (no permission), T1 (All-files, `direct` only), T2 (SAF workspaces). No unconditional `/sdcard` or `/storage` binds | Root cause of wolfi's storage permission errors (ARCHITECTURE §2.1) | Accepted |
| D8 | No root, su, chroot, Shizuku, Shevery, rish, or privileged bridge, ever | User requirement | Accepted (hard rule) |
| D9 | Termux options: T2 (`pkg` shim and `termx` helper) is the default. T1 (RUN_COMMAND bridge) is optional and ships only after its names are verified. T3 (own Termux package set) is not scheduled | T2 keeps targetSdk 37 and needs no package rebuilds. T1 depends on unverified details | Accepted |
| D10 | "Period history" means per-session command history with timestamps, FTS search, redaction of sensitive patterns, and 90-day retention. The user can clear it | Interpretation of the user's request. Owner may correct it | Accepted, owner may correct |
| D11 | License: MIT, copyright holder "TermX contributors" | Matches wolfi-terminal's license type. Holder is a placeholder | Open: owner may set a name |
| D12 | `allowBackup="false"` until Phase 6 defines exclusions and rules | Distros and secrets must not leak through backup. Safer default until rules exist | Accepted |
| D13 | R8 and resource shrinking stay off until a release-device smoke test passes (Phase 8) | wolfi-terminal v1.3.1 crashed on launch after shrinking | Accepted |
| D14 | Release signing only from GitHub secrets. Branch builds may be unsigned. Tag releases must be signed or the release job fails | No key in the repo (wolfi commits a testkey, which we are not repeating) | Accepted |
| D15 | Build and test only through GitHub Actions. No local Android SDK | The planning sandbox cannot reach dl.google.com, maven.google.com, services.gradle.org, repo1.maven.org, or jitpack.io. GitHub runners can | Accepted |
| D16 | Repository: a new private repo `termx` on the owner's GitHub account. Fallback: an orphan branch `termx` in `leloush-x/wolfi-terminal` | A separate repo keeps the wolfi releases page clean. The sandbox cannot create repos, so the owner links GitHub or creates the repo | Open: waiting for GitHub link |
| D17 | Branching: `main` is always releasable. Each phase runs on `phase/<n>-<name>`, merged after CI is green | Keeps each phase reviewable and each release reproducible | Accepted |
| D18 | Versioning: Phase 0 is `0.1.0`, and each phase adds one minor version. `1.0.0` ships with Phase 8 | Each phase produces a real release APK on GitHub | Accepted |
| D19 | Interim fix for wolfi-terminal: make the bind and permission changes in the current app now (ARCHITECTURE §2.6) | Users are hitting the storage errors today | Open: owner decides whether to patch the old app |
| D20 | Create private repository `leloush-x/termx` and use it as `origin` | Phase 0 needs a remote for CI and releases; the authenticated GitHub account has the required access | Accepted |
| D21 | Add AndroidX Annotation 1.9.0 as a direct dependency for the vendored terminal view | Matches the annotation dependency used by Termux terminal-view at the pinned source commit | Accepted |
| D22 | Keep emulator v0.118.3 and adapt the newer pinned terminal view to its API | CI showed the view source uses later SIXEL and emulator-owned scroll APIs. The view now owns scroll state and omits SIXEL rendering | Accepted |
| D23 | Confirm D16's private repository setup is complete | `leloush-x/termx` exists, `origin` is configured, and `main` was pushed | Accepted; supersedes D16's open status |
| D24 | Add an emulator smoke workflow for the Phase 1 shell interaction | No device is attached to the build environment; GitHub Actions captures the terminal after `echo hi`, `seq 10000`, and a scroll gesture | Accepted; visual scroll quality still needs human review |
