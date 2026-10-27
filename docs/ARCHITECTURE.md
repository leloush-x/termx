> **Repo copy of the design (2026-10-26).** Where this file and `DECISIONS.md` differ, `DECISIONS.md` wins. The code blocks are illustrations of the intended shape. Don't paste them. Build each component by adapting the sources listed in `REFERENCES.md`.

# TermX: New Android Terminal, Full Plan

Working name: **TermX** (placeholder, rename freely). Package placeholder: `app.termx`.
Date: 2026-10-26
Status: implemented incrementally through **v0.7.15**. This document remains the design reference; the repository and green CI are authoritative for current behavior.

---

## 0. Summary

Build a new terminal app from scratch that keeps the distro runtime (Wolfi, Alpine, Debian, Void on proot) and drops root, Shizuku and Shevery. It adds a storage model that doesn't produce permission errors, productivity features (history, command palette, profiles, snippets, session recording), a real customization engine, and an optional Termux mode.

Core targets:

- **Ultra light:** base APK around 10 MB per ABI, no bundled rootfs by default. Targets to validate after a baseline build.
- **Smooth and fast:** cold start under 700 ms on a mid-range device, input-to-echo p95 under 30 ms, 60 fps scrolling.
- **Compatible:** Android 8.0 (API 26) to Android 17 (API 37), arm64 and x86_64 primary, with a test matrix.
- **Modern:** Jetpack Compose shell, Material 3 with dynamic color, predictive back, edge-to-edge.
- **Advanced:** profiles, workspaces, searchable history, palette, prompt navigation, session recording, background jobs.
- **Termux option:** three tiers (bridge, compatibility shim, full Termux package set), with a recommendation.

---

## 1. Scope

### 1.1 Goals

1. Distro sessions (Wolfi default, Alpine, Debian, Void) with no root.
2. Storage that works on every supported Android version without surprise permission errors.
3. Long-running sessions and jobs that survive app switching, within Android's background limits.
4. Fast, small, and smooth, measured by budgets (section 11), not by feel.
5. Customization by file: themes, keymaps, extra-key layouts, and profiles are JSON with versioned schemas, exportable and importable.
6. Termux compatibility as an option, not a requirement.

### 1.2 Non-goals (explicitly out)

- Root, chroot, Shizuku, Shevery, rish, or any privileged bridge.
- SMS, contacts, call log, or phone-state access.
- An HTTP remote-control server or any exported control API by default.
- Auto-running remote scripts. Remote content is shown first and never run without a tap and a visible command.
- Embedded language servers, a full IDE, or VNC/X11/QEMU windows in v1.
- Analytics or telemetry. Crash data stays local unless the user shares it.

### 1.3 Carried over from wolfi-terminal

| Keep | Why |
|---|---|
| proot runtime and `libproot.so` / `libloader.so` packaging | Only no-root way to run distros on targetSdk 37 (see 2.7) |
| Rootfs downloader with SHA-256 manifests and HTTP Range resume | Already works |
| Java-side rootfs extraction instead of tar under proot | Your `RootfsExtractor` comment is right: proot traps every syscall, so this is much faster |
| Extra keys row, voice input (with IME fixes), GitHub clone, settings search | Already built and documented |

### 1.4 Dropped from wolfi-terminal

| Drop | Reason |
|---|---|
| chroot modes, `su` detection, Shevery/Shizuku, `rish`, auto-login via rish | Out of scope per your request |
| `MANAGE_EXTERNAL_STORAGE` by default | Causes the errors (2.1). Moves to the `direct` flavor only |
| Unconditional `-b /sdcard` and `-b /storage` in init scripts | Root cause of storage errors (2.1) |
| `allowBackup` with no exclusions | Secrets in backup (section 12) |

---

## 2. Storage and permissions

### 2.1 Why you get permission errors today (root causes, verified in source)

1. **Unconditional binds.** `init-host.sh` (and related distro variants) always run `-b /sdcard` and `-b /storage`. Without All-files access, those paths come up empty or fail, so commands inside the distro report permission denied.
2. **Permission was only reachable from a settings row in the original app.** `SettingsScreen.kt` launches `ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION`, but nothing asks at the point of need. TermX shows the reason in Storage Health before opening the grant screen.
3. **Legacy permissions do nothing on new Android.** `READ_EXTERNAL_STORAGE` and `WRITE_EXTERNAL_STORAGE` have no effect on API 30 and above. `requestLegacyExternalStorage` is ignored once targetSdk is 30 or higher, and your app targets 37.
4. **A hidden flag.** `ignore_storage_permission` hides the prompt but does not change the binds, so the errors continue.

### 2.2 The new model: three tiers, chosen by flavor and grant

| Tier | Permission | Available in | What the user gets |
|---|---|---|---|
| **T0 (default, everywhere)** | No user-granted permission | `play` and `direct` | Private distro home (`noBackupFilesDir`). App shared folder (`getExternalFilesDir`). TermX writes the active Android DNS servers to a private resolver file and binds it at `/etc/resolv.conf` for Linux package/network tools. Import via the system file picker. Export to `Downloads/TermX` through MediaStore. Share sheet. |
| **T1 (opt-in)** | API 26–28: `READ_EXTERNAL_STORAGE` + `WRITE_EXTERNAL_STORAGE`; API 30+: All-files (`MANAGE_EXTERNAL_STORAGE`) | `direct` only (F-Droid, GitHub); unavailable on API 29 | Bind shared device storage into `~/storage/shared` only after the matching grant is active. |
| **T2 (SAF folders)** | Persisted SAF grant, per folder | `play` and `direct` | Pick folders as "workspaces". Copy-in and copy-out are explicit actions. SAF folders cannot be bind-mounted, so this is an honest limitation. |

Rules:

- **Never request a permission at launch.** Ask at the point of need, with a sentence that says why.
- **Bind only what exists.** A bind is added only when its tier is active. No unconditional `/sdcard`.
- **Errors are translated.** Storage actions that receive `EACCES` or a permission denial show one line pointing to Storage Health or a SAF workspace.
- **Storage Health screen.** Shows each tier as active, inactive, or blocked, with a button for each fix. Also shows the exact binds in the current session.

### 2.3 Why this is compatible from Android 8 to 17

| API | Behavior that matters | TermX handling |
|---|---|---|
| 26–28 | Legacy shared storage requires read/write runtime permissions | Direct flavor declares both with `maxSdkVersion="28"`; request only when the user enables T1 |
| 29 | Scoped storage; All-files access is not available | T1 is unavailable for this target. Use T0 or a user-picked T2 workspace |
| 30–32 | Scoped storage enforced. All-files access is granted in Settings, not a dialog | `Environment.isExternalStorageManager()` check; `ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION` intent with fallback |
| 33+ | Media permissions split | Not needed. Import uses the system picker |
| 30+ | `getExternalFilesDir` needs no permission | Used for the shared folder |
| 29+ | MediaStore writes to `Downloads` need no permission for files you create | Used for export |
| 37 | Targets stricter storage and network checks | Re-test T1 and T2 on 37 before release |

Storage policy shape (reference; Android checks are adapted to the API level and flavor):

```kotlin
// core/storage/StorageAccess.kt
package app.termx.core.storage

enum class StorageTier { T0, T1, T2 }

fun canUseT1(state: StorageSnapshot): Boolean = when {
    !state.allowsAllFiles -> false
    state.apiLevel in 26..28 -> state.legacyReadGranted && state.legacyWriteGranted
    state.apiLevel == 29 -> false // No All-files access; scoped storage is enforced.
    state.apiLevel >= 30 -> state.allFilesGranted
    else -> false
}
```

### 2.4 Manifest permissions (minimal)

```xml
<!-- app/src/main/AndroidManifest.xml (shared by all flavors) -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />

    <!-- Only if the user enables Termux tier 1 (RUN_COMMAND bridge). -->
    <queries>
        <package android:name="com.termux" />
    </queries>

    <application
        android:name=".TermxApp"
        android:allowBackup="false"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:networkSecurityConfig="@xml/network_security_config"
        android:enableOnBackInvokedCallback="true"
        android:supportsRtl="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:theme="@style/Theme.TermX">

        <activity
            android:name=".ui.MainActivity"
            android:exported="true"
            android:launchMode="singleTask"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <!-- Share sheet: receive files and text into a chosen distro home. -->
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <action android:name="android.intent.action.SEND_MULTIPLE" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="*/*" />
            </intent-filter>
        </activity>

        <service
            android:name=".session.SessionService"
            android:exported="false"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Keeps interactive Linux terminal sessions running while the user works in other apps." />
        </service>

        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.files"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>
    </application>
</manifest>
```

Flavor manifests:

```xml
<!-- app/src/direct/AndroidManifest.xml (F-Droid, GitHub). Adds T1 permissions. -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
        android:maxSdkVersion="28" />
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
        android:maxSdkVersion="28" />
</manifest>
```

Notes on the manifest:

- `play` flavor has no `MANAGE_EXTERNAL_STORAGE`. Google Play restricts that permission to approved use cases, so the Play build uses T0 and T2.
- `specialUse` needs the subtype property (verified in the Android 14 foreground service docs). Without it the app is likely to be flagged in Play review.
- Foreground services can't be started from the background on Android 12 and above. Start them from a visible activity and handle `ForegroundServiceStartNotAllowedException`.
- Request `POST_NOTIFICATIONS` only when the user starts a session, with the reason that the notification exposes session status and controls. If denied, keep the foreground session running and explain that Android may hide its notification controls.
- `<queries>` for `com.termux` is required on Android 11+ to detect Termux (package visibility).

### 2.5 Backup and data locations

- Distros live in `noBackupFilesDir`. Auto-backup and device transfer never include them. This avoids multi-gigabyte backups and secret leaks.
- Settings and small data live in `filesDir` with `dataExtractionRules` excluding secret stores.

```xml
<!-- res/xml/data_extraction_rules.xml -->
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="sharedpref" path="secrets.xml" />
        <exclude domain="database" path="history.db" />
    </cloud-backup>
    <device-transfer>
        <exclude domain="sharedpref" path="secrets.xml" />
        <exclude domain="database" path="history.db" />
    </device-transfer>
</data-extraction-rules>
```

```xml
<!-- res/xml/backup_rules.xml (Android 11 and below) -->
<full-backup-content>
    <exclude domain="sharedpref" path="secrets.xml" />
    <exclude domain="database" path="history.db" />
</full-backup-content>
```

### 2.6 Quick fix for the current wolfi-terminal app (ship this before the rewrite)

1. In `MkSession.kt` and each `init-*.sh`, add `-b /storage/emulated/0:/root/storage/shared` only when `Environment.isExternalStorageManager()` is true. Remove the bare `-b /sdcard` and `-b /storage` binds.
2. Replace the settings-only request with a banner in the terminal screen when the grant is missing. The banner links to the grant screen.
3. Stop requesting `READ_EXTERNAL_STORAGE` and `WRITE_EXTERNAL_STORAGE` on API 30 and above.

### 2.7 Why proot-with-bundled-binaries is the right runtime

`useLegacyPackaging = true` is required, so that `libproot.so` and `libloader.so` exist as files on disk and can be executed from `nativeLibraryDir`. Running Termux-style binaries directly from app data is the pattern that runs into the targetSdk 29+ execution restrictions. That is one likely reason ZeroTermux pins targetSdk 28 (an inference from its manifest and the restriction reports, to verify on device; section 10). The distro path avoids the problem because the bundled proot does the exec. Your Wolfi app already runs this way on targetSdk 37, so the design is proven in your own code. Verify on a targetSdk 37 build before release.

---

## 3. Compatibility matrix

### 3.1 Android versions

- **minSdk 26** (Android 8.0). Matches wolfi-terminal. Raise to 28 only if usage data justifies it.
- **targetSdk 37 (matches wolfi-terminal; DECISIONS D3).** Android 17 changes that apply: background audio hardening (audio work in the background needs a qualifying foreground service), background activity start restrictions for `IntentSender`, default Certificate Transparency, and the `ACCESS_LOCAL_NETWORK` permission if you add LAN features.
- **16 KB page size:** required for targetSdk 35 and above on Google Play. Non-compliant updates can't be released from February 1, 2027. The CI check in the appendix covers this.

### 3.2 ABIs and distros

| ABI | Distros | Priority |
|---|---|---|
| arm64-v8a | Wolfi, Alpine, Debian, Void | Primary |
| x86_64 | Wolfi, Alpine, Debian, Void (emulators, Chromebooks) | Required for tests |
| armeabi-v7a | Alpine only (Wolfi, Debian and Void don't ship 32-bit builds, per your README) | Optional, decide by usage |

### 3.3 OEM and keyboard matrix

- **OEM background limits:** HyperOS (Xiaomi), ColorOS (OPPO and Realme), One UI (Samsung), Funtouch (vivo), and Pixel. Each has its own battery and background kill behavior. Add a per-OEM guidance card that opens the battery settings screen, using `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`. That intent needs no permission.
- **Keyboards:** Gboard, Samsung Keyboard, SwiftKey, and the Xiaomi keyboard. Voice input and the mic button behave differently on each (your VOICE_HANDOFF.md documents a case where the default input type hid the mic).
- **Hardware keyboards and mice:** Ctrl, Alt, Fn, arrows, Home, End, PgUp, PgDn, and mouse wheel scroll.

### 3.4 Device tiers for testing

| Tier | Example | Purpose |
|---|---|---|
| Low | Android 8–9 device, 3 GB RAM | Cold start, memory ceiling |
| Mid | Android 12–13 device, 6 GB RAM | Main performance budget |
| High | Android 15–17 device | Modern APIs, 16 KB, FGS types |
| Foldable or tablet | Any | Adaptive layout, multi-window |
| Emulators | API 26, 29, 34, 36, 37 (x86_64) | CI and regression |

---

## 4. Architecture

### 4.1 Modules

```
termx/
  app/                       Compose shell, navigation, flavors (play, direct)
  core/common/               AppPaths, Result, dispatchers, logging
  core/storage/              StorageAccess, bind table, SAF and MediaStore helpers
  core/runtime/              ProotCommand, distro launch, PTY via emulator JNI
  core/distro/               Install, update, snapshot, rollback, manifest verification
  core/session/              SessionService, registry, notifications, wake policy
  core/terminal/             Vendored emulator and view (Apache-2.0), input, keymap, extra keys
  core/theme/                Theme engine, JSON schemas, font loader
  core/settings/             DataStore, migrations, export and import
  core/history/              Shell hooks, history importer, Room and FTS5
  feature/files/             File browser over app storage, SAF and MediaStore
  feature/palette/           Command palette, snippets, search
  feature/profiles/          Profiles and workspaces
  feature/recording/         Session recording (asciicast v2)
  feature/termux/            Bridge (RUN_COMMAND), compatibility shim IPC
  tools/benchmark/           Macrobenchmark and baseline profile generation
  tools/ci/                  16 KB check, APK size gate, dependency guard
```

Dependency rule: `feature/*` may depend on `core/*`. `core/*` never depends on `feature/*` or `app`.

### 4.2 Layers and state

- **UI:** Compose for chrome (tabs, sheets, settings, palette, file browser). The terminal surface stays a native `View` (vendored `TerminalView`), wrapped in `AndroidView`. Compose doesn't redraw the terminal grid.
- **State:** `ViewModel` + `StateFlow` per screen. No global mutable singletons. Session state lives in `SessionRegistry`, owned by the foreground service and exposed through a bound interface.
- **Threading:** Dispatchers injected, never hard-coded. PTY reads on a dedicated thread. Disk and network on `Dispatchers.IO`. No blocking calls on the main thread (StrictMode in debug).
- **Persistence:** DataStore for settings. Room for history and session metadata (`history.db`). Profiles and themes as versioned JSON files.
- **Events:** Kotlin `Flow`. No `GlobalScope`. Every coroutine owned by a lifecycle or the service scope.
- **Errors:** A sealed `Result` type. Errors shown as actionable messages, never raw stack traces in the UI. Crash records go to the local crash history (feature in section 8).

### 4.3 Session lifecycle

```
ProfileChosen -> SessionCreated -> Starting -> Running -> (Exited | Killed | Backgrounded)
                                        |                        |
                                        +-> StartFailed          +-> Restored (new PTY, same cwd and env)
```

- **Killed:** the process died with the app. Show "Reconnect" and create a new PTY in the same working directory with the same environment. You can't reattach a dead proot process.
- **Backgrounded:** the session is alive under the foreground service. Notification shows status.

---

## 5. Runtime: distros

### 5.1 Install and update

1. Manifest (signed) lists each distro: id, version, arch, url, size, sha256, minAppVersion.
2. Download with HTTP Range resume into `cacheDir`. Verify SHA-256 while streaming.
3. Extract into `distros/<id>.tmp-<timestamp>` using the existing Java extractor. Create the bind mount points (`/root/shared`, `/root/storage`) inside the rootfs.
4. Atomic switch: rename the temp directory to `distros/<id>`. Keep the previous version as `distros/<id>.prev` for rollback.
5. Write `.termx-distro.json` with version, sha256, and install time. Used by Storage Health and the update check.

Manifest example (signed; the signature is an Ed25519 signature over the canonical JSON, verified with a key embedded in the app):

```json
{
  "schema": 1,
  "generated": "2026-10-26T00:00:00Z",
  "distros": [
    {
      "id": "wolfi",
      "name": "Wolfi",
      "version": "2026.10.20",
      "arch": "aarch64",
      "url": "https://github.com/leloush-x/wolfi-os-rootfs/releases/download/2026.10.20/wolfi-rootfs-aarch64.tar.gz",
      "size": 0,
      "sha256": "<fill from release manifest>",
      "minAppVersion": 1
    }
  ]
}
```

Replace the zero size and placeholder hash with real values from the release. Don't ship placeholder values.

### 5.2 Launch

Reference code (ProotCommand):

```kotlin
// core/runtime/ProotCommand.kt
package app.termx.core.runtime

import java.io.File

data class Bind(val host: String, val guest: String)

data class LaunchSpec(
    val prootBinary: File,      // nativeLibraryDir/libproot.so
    val loaderBinary: File,     // nativeLibraryDir/libloader.so
    val tmpDir: File,           // per-session tmp
    val rootfs: File,           // distros/<id>
    val cwd: String,            // guest path, e.g. "/root"
    val binds: List<Bind>,
    val command: List<String>,  // e.g. listOf("/bin/bash", "-l")
)

object ProotCommand {
    /** Returns argv and the extra environment for the PTY launch. */
    fun build(spec: LaunchSpec): Pair<List<String>, Map<String, String>> {
        val argv = buildList {
            add(spec.prootBinary.absolutePath)
            add("--kill-on-exit")
            add("--link2symlink")
            add("-0")                       // fake root id inside the guest; no real root
            add("-r"); add(spec.rootfs.absolutePath)
            add("-w"); add(spec.cwd)
            spec.binds.forEach { add("-b"); add("${it.host}:${it.guest}") }
            addAll(spec.command)
        }
        val env = mapOf(
            "PROOT_LOADER" to spec.loaderBinary.absolutePath,
            "PROOT_TMP_DIR" to spec.tmpDir.absolutePath,
            "TERM" to "xterm-256color",
            "COLORTERM" to "truecolor",
            "LANG" to "C.UTF-8",
            "HOME" to "/root",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        )
        return argv to env
    }
}
```

Bind table, gated by storage tier:

```kotlin
// core/storage/BindTable.kt
package app.termx.core.storage

import app.termx.core.runtime.Bind
import java.io.File

object BindTable {
    fun build(level: StorageLevel, shared: File): List<Bind> = buildList {
        add(Bind("/dev", "/dev"))
        add(Bind("/proc", "/proc"))
        add(Bind("/sys", "/sys"))
        add(Bind(shared.absolutePath, "/root/shared"))        // always: app-owned, no permission
        if (level == StorageLevel.ALL_FILES) {
            add(Bind("/storage/emulated/0", "/root/storage/shared"))  // only when granted
        }
    }
}
```

Extra binds such as `/system` and `/apex` needed by some guest tools are added by the distro manifest, not hard-coded.

### 5.3 Performance notes

- proot intercepts every syscall, so heavy I/O inside a guest is slow. Mitigations:
  - Extract outside proot (already done).
  - Keep the `--link2symlink` flag. It prevents costly hard-link issues on Android storage.
  - Suggest `apk`, `apt` and `xbps` caches live inside the rootfs, not on shared storage.
- A future runtime backend (plugin point, section 10): Android's built-in Linux VM on supported Pixel devices. It's opt-in and early (per The Register's report on Android 15), so don't depend on it.

---

## 6. Sessions and background behavior

### 6.1 Foreground service

- One `SessionService` hosts all PTYs. Type `specialUse` with the subtype property (section 2.4).
- Start from a visible activity. Handle `ForegroundServiceStartNotAllowedException` by showing a notification action that opens the app.
- Notification shows: session count, active session name, and actions **Open**, **New session**, and **Stop all**.
- `specialUse` is not limited by the 6-hour budget that applies to `dataSync` and `mediaProcessing` (verified in the Android 15 timeout docs). Don't switch to `dataSync` to fix something else.

### 6.2 Wake policy

- Default: keep the CPU awake (PARTIAL_WAKE_LOCK) only while output is streaming or a job is running. Release after 30 seconds of silence.
- Optional "Keep awake" toggle per session (off by default).
- No Wi-Fi lock by default. Offer it only for long downloads, with an explicit toggle.

### 6.3 Background jobs

- A job is a headless session with a timeout, a log file, and a completion notification.
- Scheduled jobs use WorkManager. Work survives reboot without `RECEIVE_BOOT_COMPLETED`.
- Run-time cap: 10 minutes by default, configurable. The ZeroTermux idea of a cap is worth keeping.
- No exported service. No root.

### 6.4 Doze and app standby

- Don't fight Doze. Explain it. Show the battery guidance card on first long session.
- Measure: background battery use per hour with an idle session (target in section 11).

---

## 7. Terminal engine, input and rendering

### 7.1 Engine

- **Phase 1:** vendor the Apache-2.0 parts of Termux's `terminal-emulator` and `terminal-view` (the license exception noted in your Wolfi review). Keep the NOTICE file.
- **Phase 3:** evaluate a custom renderer (glyph atlas, damage tracking) only if the performance budget is missed. Don't start there.

### 7.2 Escape sequences and features

| Feature | Plan |
|---|---|
| 256 colors and true color | Required |
| Alternate screen (vim, less, htop) | Required |
| Mouse reporting (SGR) | Required |
| Bracketed paste | Required |
| OSC 8 hyperlinks (tap to open, with confirmation) | Phase 3 |
| OSC 52 clipboard write | Opt-in per profile. Off by default |
| OSC 133 semantic prompts (prompt navigation, output selection) | Phase 3, used by the shell hooks |
| Unicode width and emoji | Required, test with CJK and emoji ZWJ sequences |
| Ligatures | Optional toggle |
| Scrollback | Default 5,000 lines, configurable. Ring buffer. Full history search lives in the history store, not the buffer |

### 7.3 Input

- **IME:** text input type without suggestions as the default. Mic-friendly mode when voice is enabled. Test each keyboard in the matrix.
  > Superseded by D20: one text input path, TYPE_CLASS_TEXT with suggestions on and IME_FLAG_NO_PERSONALIZED_LEARNING. See docs/IME_MATRIX.md.
- **Extra keys row:** JSON layout (appendix), with long-press alternates, sticky modifiers, and a collapsible second layer.
- **Gestures:** two-finger swipe to switch sessions. Pinch to zoom font size (live, persisted). Long-press to select.
- **Hardware keyboard:** remappable shortcuts (new session, close, next and previous, palette, copy, paste).

### 7.4 Selection, copy, paste

- Selection by long-press with handles. Copy keeps line breaks and strips trailing spaces by default.
- Paste uses bracketed paste when the app requests it. Multi-line paste asks for confirmation when it contains a newline and the target is a shell prompt. This prevents accidental command execution.

### 7.5 Rendering budget

- Redraw only damaged rows. No full-screen invalidate on each output chunk.
- Batch PTY reads. Cap UI updates at the display refresh rate.

---

## 8. Productivity features (advanced)

Ordered by value to a power user. Phases in section 16.

### 8.1 Command palette (Ctrl+Shift+P or the palette button)

Actions: new session, switch profile, switch session, open file browser, run snippet, search history, search scrollback, open settings, toggle extra keys, start recording, stop recording, export transcript, run job, and open Storage Health.

Fuzzy search with recent-first ranking. Every action has a stable id, so keyboard shortcuts and profiles can reference it.

### 8.2 Command history (searchable, private)

**Capture: shell hooks, file based.** The init script sources a small hook. The hook appends one line per command to a file in the distro home. The app imports the file into Room. This avoids patching the emulator and works in every shell.

Hook for bash (`~/.bashrc.d/termx-history.sh`, sourced by the init script):

```bash
# TermX command history hook (bash). Do not edit: managed by TermX.
__termx_b64() { printf '%s' "$1" | base64 | tr -d '\n'; }

__termx_log() {
  local ec=$?
  local cmd
  cmd=$(HISTTIMEFORMAT= history 1 | sed -e 's/^[ ]*[0-9]*[ ]*//')
  # Skip empty, internal, and leading-space commands (leading space = private, like HISTCONTROL).
  [ -z "$cmd" ] && return $ec
  case "$cmd" in " "*|__termx_*) return $ec ;; esac
  local dir="$HOME/.local/state/termx"
  mkdir -p "$dir"
  printf '%s\t%s\t%s\t%s\n' "$(date +%s)" "$ec" "$(__termx_b64 "$PWD")" "$(__termx_b64 "$cmd")" \
    >> "$dir/history.tsv"
  return $ec
}

case ";${PROMPT_COMMAND:-};" in
  *";__termx_log;"*) ;;
  *) PROMPT_COMMAND="__termx_log${PROMPT_COMMAND:+;$PROMPT_COMMAND}" ;;
esac
```

Hook for zsh (`~/.zshrc.d/termx-history.zsh`):

```zsh
# TermX command history hook (zsh). Managed by TermX.
__termx_b64() { printf '%s' "$1" | base64 | tr -d '\n' }

__termx_precmd() {
  local ec=$?
  local cmd
  cmd=$(fc -ln -1 2>/dev/null)
  [[ -z "$cmd" ]] && return $ec
  [[ "$cmd" == " "* || "$cmd" == __termx_* ]] && return $ec
  local dir="$HOME/.local/state/termx"
  mkdir -p "$dir"
  printf '%s\t%s\t%s\t%s\n' "$(date +%s)" "$ec" "$(__termx_b64 "$PWD")" "$(__termx_b64 "$cmd")" \
    >> "$dir/history.tsv"
  return $ec
}
precmd_functions+=(__termx_precmd)
```

Import rules:

- Read `history.tsv` when the palette or history screen opens, and on a file observer event. Import into Room, then truncate the file.
- Rotate at 5 MB. Never grow without bound.
- Redaction: skip commands matching configurable patterns (default: `password=`, `token=`, `secret=`, `apikey=`, `Authorization:`). Keep the patterns in settings so the user can edit them.
- Retention: 90 days by default. The user can clear history per profile or globally.

Storage schema (Room migration SQL, FTS5 with external content):

```sql
CREATE TABLE IF NOT EXISTS command_history (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  profile_id  TEXT    NOT NULL,
  session_id  TEXT,
  cwd         TEXT    NOT NULL,
  command     TEXT    NOT NULL,
  started_at  INTEGER NOT NULL,
  exit_code   INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_history_started ON command_history(started_at);

CREATE VIRTUAL TABLE IF NOT EXISTS command_fts USING fts5(
  command, cwd, content='command_history', content_rowid='id'
);

CREATE TRIGGER IF NOT EXISTS history_ai AFTER INSERT ON command_history BEGIN
  INSERT INTO command_fts(rowid, command, cwd) VALUES (new.id, new.command, new.cwd);
END;
CREATE TRIGGER IF NOT EXISTS history_ad AFTER DELETE ON command_history BEGIN
  INSERT INTO command_fts(command_fts, rowid, command, cwd)
    VALUES ('delete', old.id, old.command, old.cwd);
END;
```

Search uses `@RawQuery` in Room, because Room's annotation processor can't verify FTS5 tables at compile time. Quote each user term before the MATCH query to avoid FTS syntax errors.

### 8.3 Prompt navigation and output selection (OSC 133)

With the OSC 133 marks emitted by the shell hook, the app can:

- Jump to the previous or next prompt (volume-key or gesture).
- Select the output of the last command with one tap.
- Re-run the last command from the palette.

Add OSC 133 emission to the hooks above in Phase 3. It needs parser support in the vendored emulator.

### 8.4 Snippets and variables

- Snippets: a named command with optional variables (`{{path}}`). Insert by default. Run needs an explicit tap, and the final command is shown first.
- Variables fill from the profile, the current working directory, and clipboard.
- Snippets are JSON, exportable.

### 8.5 Profiles and workspaces

Profile (JSON):

```json
{
  "schema": 1,
  "id": "dev-wolfi",
  "name": "Wolfi dev",
  "runtime": "distro:wolfi",
  "shell": "/bin/bash",
  "cwd": "/root/projects",
  "env": { "EDITOR": "vim" },
  "startup": ["source ~/.venv/bin/activate"],
  "theme": "default-dark",
  "font": "jetbrains-mono",
  "historyEnabled": true,
  "osc52Clipboard": false
}
```

Workspaces: a folder (SAF, tier T2, or app-owned) linked to a profile. Opening the workspace starts the profile with the cwd set. In the Play flavor, workspace folders are copied in and out explicitly, because they can't be bind-mounted.

### 8.6 Session recording (asciicast v2)

- Record PTY output and input (input is off by default and can contain secrets, so it needs a clear toggle).
- Export `.cast` files, which replay with the asciinema player. Recording writes to the app's shared folder, and the user exports to Downloads through MediaStore.

### 8.7 File browser

- Browse app home, shared folder, workspaces (T2), and Downloads (via MediaStore).
- Operations: copy, move, rename, delete (with confirmation), new file, new folder, share, open in session (`cd` into the folder), and import or export.
- Text preview and a plain editor. Syntax highlighting can come later (tree-sitter).

### 8.8 Crash history and diagnostics

- Local crash records, list view, share and clear (ZeroTermux's idea, built with no network).
- "Copy diagnostics" button: app version, device, Android version, storage tier, runtime, distro version. No personal data.

### 8.9 Background jobs UI

- A jobs list with status, elapsed time, and logs. Cancel and re-run actions.
- Completion notification with an Open action.

### 8.10 Termux bridge and shim (section 10)

- Optional panel: "Run in Termux" (tier 1 bridge, section 10.2).
- The `termx` helper command inside distros (section 10.3).

---

## 9. Customization

### 9.1 Theme engine

Theme file (JSON, schema version 1):

```json
{
  "schema": 1,
  "name": "default-dark",
  "mode": "dark",
  "colors": {
    "background": "#0B0D12",
    "foreground": "#D6DAE3",
    "cursor": "#7AA2F7",
    "selection": "#2A3550",
    "bold": "#FFFFFF"
  },
  "ansi": [
    "#15161E", "#F7768E", "#9ECE6A", "#E0AF68",
    "#7AA2F7", "#BB9AF7", "#7DCFFF", "#A9B1D6",
    "#414868", "#F7768E", "#9ECE6A", "#E0AF68",
    "#7AA2F7", "#BB9AF7", "#7DCFFF", "#C0CAF5"
  ],
  "background": { "image": null, "blur": 0, "dim": 0.4 }
}
```

- Sixteen ANSI colors required. Validation rejects anything else with a message that names the field.
- Dynamic color (Monet) on Android 12 and above, as a theme source. AMOLED black as a toggle.
- Live preview on the active session. Import and export. Themes bundled and user-made are treated the same.
- Per-profile theme override.

### 9.2 Fonts

- Bundled: JetBrains Mono (OFL). User TTF import. Nerd Font variants allowed after checking each font's license.
- Size (live, pinch to zoom), line height, weight, and ligatures toggle. Font loads on a background thread (your wolfi-terminal TODO already notes this).

### 9.3 Keymap and extra keys

Extra keys layout (JSON):

```json
{
  "schema": 1,
  "layers": {
    "base": [
      [
        { "label": "ESC", "send": "\u001b" },
        { "label": "TAB", "send": "\t" },
        { "label": "CTRL", "modifier": "ctrl", "sticky": true },
        { "label": "ALT", "modifier": "alt", "sticky": true },
        { "label": "-", "send": "-", "long": ["_", "="] },
        { "label": "/", "send": "/", "long": ["\\", "|"] },
        { "label": "↑", "send": "\u001b[A" },
        { "label": "↓", "send": "\u001b[B" },
        { "label": "←", "send": "\u001b[D" },
        { "label": "→", "send": "\u001b[C" }
      ]
    ],
    "more": [
      [
        { "label": "PgUp", "send": "\u001b[5~" },
        { "label": "PgDn", "send": "\u001b[6~" },
        { "label": "Home", "send": "\u001b[H" },
        { "label": "End", "send": "\u001b[F" }
      ]
    ]
  }
}
```

- Keymap: a JSON map from action id to key combo. The palette lists every bound key.
- Gestures are configurable: swipe on the extra keys row, two-finger swipe, and volume keys for scrolling.

### 9.4 Settings

- Settings search and collapsible groups (already in wolfi-terminal).
- Groups: Look, Terminal, Keys, Runtime, Storage, Background, History, Termux, Advanced, About.
- Export and import all settings as JSON, excluding secrets (section 12).

---

## 10. Termux option (full plan)

### 10.1 Background (verified)

- Termux packages are built for a prefix tied to the Termux app's identity. Changing the package name means every package has to be rebuilt for the new name, and packages can't be mixed with the stock repository (termux-packages build guide).
- ZeroTermux keeps `com.termux`, uses the official bootstrap with a pinned SHA-256, and shares a UID with Termux. It stays on targetSdk 28. This is the trade-off in one app.

### 10.2 Three tiers

| Tier | What it is | Cost | Ships | Recommendation |
|---|---|---|---|---|
| **T1 Bridge** | Send commands to an installed Termux through its RUN_COMMAND intent | Low. Needs Termux installed, and `allow-external-apps=true` in `~/.termux/termux.properties` | Phase 4 | Ship as optional |
| **T2 Compatibility shim** | A `pkg` command and `termux-*` helpers inside distros, mapped to the distro's own package manager and TermX IPC. Same muscle memory, no Termux packages | Low to medium | Phase 5 | **Ship by default in Termux mode** |
| **T3 Own Termux package set** | Build termux-packages under the TermX identity, host the repo, pin SHA-256 | High. Large CI, ongoing rebuilds, and binaries run from app data, which pins targetSdk | Only on demand | Not now |

Why T2 beats T3 for now: distros already give you real apt, apk and xbps repositories. The shim gives Termux-like commands on top, targetSdk stays at 37, and nothing needs rebuilding.

### 10.3 T2 in detail: `pkg` shim and `termx` helper

`pkg` (installed as `/usr/local/bin/pkg` in each distro):

```sh
#!/bin/sh
# pkg: Termux-style frontend over the distro's native package manager.
# Installed by TermX into each distro. Runs as the guest root (fake root, no real root).
set -eu

detect_pm() {
  if command -v apk >/dev/null 2>&1; then echo apk
  elif command -v apt-get >/dev/null 2>&1; then echo apt
  elif command -v xbps-install >/dev/null 2>&1; then echo xbps
  else echo none
  fi
}

usage() {
  cat <<'EOF'
Usage: pkg <command> [args]

  update              refresh package index
  upgrade             upgrade installed packages
  install <pkg...>    install packages
  remove <pkg...>     remove packages
  search <term>       search for packages
  info <pkg>          show package details
  list                list installed packages
  clean               clear package caches
EOF
}

PM=$(detect_pm)
[ "$PM" = none ] && { echo "pkg: no supported package manager found" >&2; exit 1; }

cmd=${1:-help}
[ $# -gt 0 ] && shift

case "$cmd" in
  update|up)
    case $PM in
      apk)  apk update ;;
      apt)  apt-get update ;;
      xbps) xbps-install -S ;;
    esac ;;
  upgrade|ug)
    case $PM in
      apk)  apk upgrade ;;
      apt)  apt-get upgrade -y ;;
      xbps) xbps-install -Su ;;
    esac ;;
  install|i)
    [ $# -gt 0 ] || { echo "pkg install: give at least one package" >&2; exit 2; }
    case $PM in
      apk)  apk add "$@" ;;
      apt)  apt-get install -y "$@" ;;
      xbps) xbps-install -y "$@" ;;
    esac ;;
  remove|rm|uninstall)
    [ $# -gt 0 ] || { echo "pkg remove: give at least one package" >&2; exit 2; }
    case $PM in
      apk)  apk del "$@" ;;
      apt)  apt-get remove -y "$@" ;;
      xbps) xbps-remove -R "$@" ;;
    esac ;;
  search|s)
    case $PM in
      apk)  apk search "$@" ;;
      apt)  apt-cache search "$@" ;;
      xbps) xbps-query -Rs "$@" ;;
    esac ;;
  info|show)
    case $PM in
      apk)  apk info -a "$@" ;;
      apt)  apt-cache show "$@" ;;
      xbps) xbps-query -R "$@" ;;
    esac ;;
  list)
    case $PM in
      apk)  apk info ;;
      apt)  dpkg --get-selections ;;
      xbps) xbps-query -l ;;
    esac ;;
  clean)
    case $PM in
      apk)  rm -rf /var/cache/apk/* ;;
      apt)  apt-get clean ;;
      xbps) xbps-remove -O ;;
    esac ;;
  help|-h|--help) usage ;;
  *) echo "pkg: unknown command '$cmd'" >&2; usage >&2; exit 2 ;;
esac
```

Mapping of Termux helper commands to TermX (T2):

| Termux command | TermX equivalent | Mechanism |
|---|---|---|
| `pkg`, `apt`, `dpkg` | `pkg` shim plus native tools | Shell |
| `termux-setup-storage` | `termx storage` | Opens the grant flow for the current tier |
| `termux-open <file>` | `termx open <file>` | IPC: ACTION_VIEW chooser |
| `termux-share <file>` | `termx share <file>` | IPC: ACTION_SEND chooser |
| `termux-clipboard-get` / `-set` | `termx clip get` / `set` | IPC: clipboard |
| `termux-wake-lock` / `unlock` | `termx wake on` / `off` | IPC: session wake policy |
| `termux-notification` | `termx notify "<text>"` | IPC: notification |
| `termux-toast` | `termx toast "<text>"` | IPC: toast |
| `termux-vibrate` | `termx vibrate <ms>` | IPC: vibrator (needs VIBRATE, normal permission) |

IPC design: a Unix domain socket at `noBackupFilesDir/ctl.sock`, bound into the guest. It's a filesystem socket, not an abstract one, so other apps can't connect to it. Permissions on the socket are owner-only. The `termx` helper is a small shell wrapper that writes a JSON request to the socket and prints the reply.

### 10.4 T1 in detail: RUN_COMMAND bridge

Intent details, from my knowledge of the Termux RUN_COMMAND documentation. I couldn't fetch the wiki page during research, so **confirm every name below against the current Termux wiki before shipping**:

- Permission: `com.termux.permission.RUN_COMMAND`
- Action: `com.termux.RUN_COMMAND`
- Extras: `com.termux.RUN_COMMAND_PATH` (full path to the executable), `com.termux.RUN_COMMAND_ARGUMENTS` (String array), `com.termux.RUN_COMMAND_WORKDIR`, `com.termux.RUN_COMMAND_BACKGROUND` (boolean)
- Termux must have `allow-external-apps=true` in `~/.termux/termux.properties`.

```kotlin
// feature/termux/TermuxBridge.kt
package app.termx.feature.termux

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

object TermuxBridge {
    private const val TERMUX = "com.termux"
    private const val PERM = "com.termux.permission.RUN_COMMAND"
    private const val ACTION = "com.termux.RUN_COMMAND"

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(TERMUX, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    /** Starts a command inside Termux. Output appears in Termux, not in TermX. */
    fun run(context: Context, path: String, args: Array<String>, background: Boolean = true) {
        val intent = Intent(ACTION).apply {
            setClassName(TERMUX, "$TERMUX.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", path)
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", args)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", background)
        }
        context.startForegroundService(intent)
    }
}
```

Caveat: `startForegroundService` on Android 8 and above requires that the target service calls `startForeground`. Confirm this with Termux's current service behavior, or use `startService` if Termux's service is not foreground. Test on Android 14 and above.

The app needs the `com.termux.permission.RUN_COMMAND` permission in its manifest to call the service. Add it only when the user enables the bridge, and declare it in the manifest like any other permission.

### 10.5 T3 (kept as a plan, not scheduled)

1. Fork termux-packages. Change the package name in `properties.sh` (check `TERMUX_APP__PACKAGE_NAME` and the prefix variables).
2. Build the bootstrap and the package subset you need (bash, coreutils, git, openssh, python, nodejs, vim, curl, wget). Use `build-package.sh` per package and check its README for current flags.
3. Publish the bootstrap zip, pin its SHA-256 in the build, and verify it at install time, as ZeroTermux does.
4. Install into the app's prefix. Keep T2 as the default experience.
5. Costs: CI time, storage for the repo, and rebuilds on every upstream security fix. Binaries run from app data, which is what pins targetSdk (section 10.1).

### 10.6 Recommendation

- Ship **T2** in Termux mode from the start. It gives Termux-style commands and keeps targetSdk 37.
- Ship **T1** as an optional bridge in Phase 4.
- Keep **T3** as a documented option. Start it only if users require Termux's exact package set.

---

## 11. Performance budgets and measurement

### 11.1 Budgets (targets, to validate against a baseline)

| Metric | Target | Device tier | How measured |
|---|---|---|---|
| APK, arm64, no rootfs | ≤ 10 MB | n/a | APK analyzer in CI |
| APK, arm64, with bundled Alpine (offline flavor) | ≤ 15 MB | n/a | APK analyzer in CI |
| Cold start to first frame | ≤ 700 ms | Mid | Macrobenchmark `StartupTimingMetric` with baseline profile |
| Warm start | ≤ 300 ms | Mid | Macrobenchmark |
| Input-to-echo latency | p95 ≤ 30 ms | Mid | Injected keys, timestamps at input and damage |
| Scroll 10,000 lines (`seq 10000`) | ≥ 55 fps average, few dropped frames | Mid | Macrobenchmark `FrameTimingMetric` |
| RAM, one session, idle | ≤ 120 MB app process | Low | `dumpsys meminfo` |
| RAM, each extra session | ≤ 15 MB app side | Low | Same |
| Background battery, idle session with keep-awake off | ≤ 0.5% per hour | Mid | Battery Historian |
| Distro install, Alpine, offline | ≤ 60 s on 3 GB RAM device | Low | Stopwatch in test |

### 11.2 Measurement setup

- Macrobenchmark module with startup, frame, and scroll tests. Generate baseline profiles and ship them in the release.
- StrictMode in debug builds: disk and network on main thread, leaked closures.
- LeakCanary in debug builds only. Never in release.
- R8 off until Phase 8 (DECISIONS D13). Dependency guard in CI.
- A synthetic workload script for scroll tests: `seq 1 10000` and `yes | head -n 50000`.

---

## 12. Security and privacy

### 12.1 Secrets

- Keys and tokens (API keys, GitHub tokens, FTP passwords if added) are encrypted with an AES-GCM key held in Android Keystore. Store the ciphertext in DataStore.
- Exclude secrets from backup and device transfer (section 2.5).
- Never log secrets. Redact them in crash reports and diagnostics.

### 12.2 Components

- No exported components except the launcher activity.
- No content providers except `FileProvider` (`exported="false"`).
- No broadcast receivers except those required for specific features.
- The IPC socket for T2 is a filesystem socket with owner-only permissions (section 10.3).

### 12.3 Network

- `cleartextTrafficPermitted="false"` by default. Allow it only per host if a real need appears.
- HTTPS only for the rootfs manifest and downloads. Manifests are signed (Ed25519). Verify the signature before reading the manifest.
- Certificate Transparency is on by default on Android 17.

```xml
<!-- res/xml/network_security_config.xml -->
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
</network-security-config>
```

### 12.4 Remote content rules

- Remote text (scripts, snippets, menus) is never run automatically.
- Any remote command is shown in full, with the source, before the user runs it. The default action inserts the text and does not press Enter.
- Remote menus and packs must be signed and hash-checked. No unsigned updates.

### 12.5 Runtime isolation

- Distros run as the app UID under proot. There is no root, no device-wide access beyond what the app already has, and no privileged bridge.
- Bind mounts are listed in the Storage Health screen so the user can see exactly what a session can reach.

### 12.6 Privacy

- No analytics or telemetry by default.
- Crash records stay on the device. Sharing is manual.
- History is local, redacts configured patterns, respects a per-profile toggle, and expires after 90 days by default.
- Session recording's input capture is off by default.

### 12.7 Process

- `SECURITY.md` with a reporting address.
- Dependabot or Renovate for dependency updates (wolfi-terminal already uses Renovate).
- Review the vendored terminal code for updates to the upstream security fixes.

---

## 13. Licensing and supply chain

- **TermX:** pick a license for your code. MIT matches wolfi-terminal. Document it in LICENSE.
- **Vendored Termux code:** Apache-2.0 for the emulator and view modules, per the Termux license exception. Keep the NOTICE and attribution.
- **proot:** upstream proot is GPL-2.0 (verify in the copyright file of the proot source you build; I could not fetch the license page during research). Bundling proot binaries means:
  - Ship the license text and a link to the exact proot source, with the build recipe.
  - Keep proot as a separate executable launched by the app, not linked into app code.
  - Ask a lawyer to confirm this matches your distribution plan. This is engineering reading, not legal advice.
- **ZeroTermux (GPL, per its README):** ideas only, no code.
- **Fonts:** OFL or other permissive licenses only, checked per font.
- **Rootfs images:** your `wolfi-os-rootfs` releases. Publish the build recipe and signed manifests alongside them.
- **Supply chain:** pin dependency versions in a version catalog. Dependency guard fails the build on version conflicts. Signed release artifacts and checksums published with each release.

---

## 14. Testing

### 14.1 Layers

| Layer | Tool | Scope |
|---|---|---|
| Unit | JUnit, Robolectric | `ProotCommand`, `BindTable`, `StorageAccess` logic, theme and keymap validation, history redaction and parsing, manifest verification |
| Instrumented | AndroidJUnitRunner on emulators | Service lifecycle, storage grants, SAF and MediaStore flows, notifications |
| Benchmark | Macrobenchmark | Startup, frames, scroll |
| Manual | Device lab checklist | IME and keyboards, OEM background behavior, storage flows on real devices |
| Compatibility | CI matrix | API 26, 29, 34, 36, 37 emulators |

### 14.2 Storage test cases (must pass before release)

| # | Scenario | Expected |
|---|---|---|
| S1 | Fresh install, no grants, open session | Session starts. Shared folder works. `ls ~/storage` shows the hint line, not an error |
| S2 | `direct` flavor, grant All files, open session | `~/storage/shared` appears and lists `/storage/emulated/0` |
| S3 | Revoke All files while a session is open | Existing session stays up. New session shows the hint. Banner appears |
| S4 | Upgrade from a previous version with the grant | Grant status preserved. Binds recomputed |
| S5 | `play` flavor, import a file through the system picker | File lands in the app shared folder |
| S6 | Export a file to Downloads through MediaStore | File appears in Downloads, no permission prompt |
| S7 | Workspace SAF folder, copy in and out | Copies succeed. Persisted grant survives reboot |
| S8 | API 26 device, legacy storage | Works with `WRITE_EXTERNAL_STORAGE` (maxSdk 29) |
| S9 | API 37 device, all tiers | Works. No deprecated permission prompts |
| S10 | Denied grant twice | No infinite prompt loop. Settings deep link offered |

### 14.3 Terminal test cases

- Escape-sequence conformance tests (cursor movement, colors, alternate screen, mouse, bracketed paste).
- Unicode tests: CJK width, emoji ZWJ, combining marks.
- IME tests per keyboard in the matrix: commit, compose, delete surrounding, voice dictation without Enter.
- Process death: kill the app with a running session, relaunch, verify Reconnect restores cwd and env.

---

## 15. Release engineering

### 15.1 Flavors and channels

| Flavor | Distribution | Permissions | Notes |
|---|---|---|---|
| `play` | Google Play | No `MANAGE_EXTERNAL_STORAGE` | T0 and T2 only |
| `direct` | F-Droid and GitHub releases | Adds `MANAGE_EXTERNAL_STORAGE` | T0, T1 and T2 |

Channels: `stable` and `beta`. Beta releases carry a visible badge in the About screen.

### 15.2 CI gates (every PR)

1. Unit tests.
2. Lint with no new warnings.
3. APK size gate (fails above the budget in 11.1).
4. 16 KB alignment check (appendix).
5. Dependency guard (no version conflicts).
6. Build both flavors, arm64 and x86_64 splits.

Nightly:

- Instrumented tests on the API matrix.
- Macrobenchmark run, with regressions reported against the last release.

### 15.3 Release steps

1. Generate baseline profile on a release-like build.
2. Sign with the release key from CI secrets. The build fails if the key is missing. There is no fallback key in the repo.
3. Run the 16 KB check on the signed APK.
4. Publish checksums and the signed distro manifest.
5. Tag the release. Publish to GitHub (direct), F-Droid metadata, and Play.

### 15.4 Versioning

- Semantic versions. `versionCode` increments on every release.
- Settings and database migrations are versioned. Every migration has a unit test.

---

## 16. Roadmap

Effort estimates are rough, for one person working part-time. Adjust once you've done Phase 0.

### Phase 0: Foundations (about 2 weeks)

- Create the repo, module layout, version catalog, CI gates, flavors.
- Ship the quick fixes to wolfi-terminal (section 2.6).
- Vendor the terminal modules with NOTICE.

**Acceptance:** CI green, an empty app launches on API 26 and API 37, both flavors build, 16 KB check passes on an empty native lib.

### Phase 1: Core terminal and distro runtime (about 4–6 weeks)

- Terminal surface, session service, and PTY via emulator JNI.
- Distro install, update, rollback, and launch (`ProotCommand`, `BindTable`).
- Wolfi, Alpine, Debian, and Void.

**Acceptance:** all four distros start on arm64 and x86_64, input works, cold start budget met on the mid tier, scroll budget met.

### Phase 2: Storage and sessions (about 2–3 weeks)

- Storage tiers, Storage Health screen, error translation, grant flows.
- Foreground service with `specialUse`, notification actions, wake policy, process-death restore.

**Acceptance:** storage test cases S1–S10 pass. Sessions survive app switching for 30 minutes in the background on the mid tier.

### Phase 3: Productivity (about 4 weeks)

- History (hooks, import, FTS5 search, redaction, retention).
- Command palette, snippets, profiles, workspaces, prompt navigation (OSC 133).
- File browser and share receiver.

**Acceptance:** history search returns results under 100 ms for 100,000 entries. Palette opens in under 100 ms.

### Phase 4: Customization and Termux bridge (about 3 weeks)

- Theme engine, font loader, keymap, extra-keys layouts, settings export and import.
- T1 bridge (after confirming the RUN_COMMAND details).

**Acceptance:** themes and layouts validate with clear errors. Bridge test passes against a current Termux build.

### Phase 5: Termux compatibility shim (about 2 weeks)

- `pkg` shim installed into each distro.
- `termx` helper and IPC socket, covering the commands in 10.3.

**Acceptance:** `pkg install`, `pkg search`, and `termx open` work on each distro. IPC rejects connections from other UIDs.

### Phase 6: Hardening (about 3–4 weeks)

- Performance tuning against the budgets, baseline profiles, OEM testing.
- IME and accessibility pass.
- Crash history, diagnostics, recording.

**Acceptance:** all budgets in section 11 met on the mid tier, zero open critical bugs, OEM checklist complete.

### Phase 7: Release (about 1–2 weeks)

- Store listings, F-Droid metadata, release notes, migration guide from wolfi-terminal.

**Acceptance:** signed release artifacts, checksums, manifest signature verified in a clean install.

### Optional, later

- Custom renderer (only if the budget is missed).
- Pluggable runtime backend for Android's built-in Linux VM on supported devices.
- SSH client profiles.
- T3 Termux package set.

---

## 17. Migration from wolfi-terminal

- Different package ID means a separate install. The new app can't read the old app's private data.
- Path A (simplest): reinstall the distro in TermX. The downloads are the same releases.
- Path B: in wolfi-terminal, export each distro home to the shared folder as a tar. In TermX, import it through the file picker. Add a checksum on export.
- Keep wolfi-terminal on a maintenance branch until TermX reaches parity for your daily use.

---

## 18. Risks and mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| proot is slow for heavy I/O | Slow builds and installs inside distros | Keep Java-side extraction. Guide users to keep caches inside the rootfs. Track the budget |
| OEM kills background sessions | Lost sessions | Guidance card, notification, process-death restore |
| Android 17 tightening | Breakage on newest devices | Test on 37 early in Phase 0. Watch background audio and BAL changes |
| Play policy on All-files access | Play rejection or removal | Play flavor has no `MANAGE_EXTERNAL_STORAGE` |
| Vendored terminal code drifts | Missed security fixes | Track upstream releases. Quarterly merge window |
| GPL obligations for proot | Legal exposure | Ship license text and source link. Get a legal read before the first public release |
| 16 KB requirement | Blocked Play updates from Feb 2027 | CI check. Recompile native code with NDK r28 or later |
| Distro supply chain | Compromised rootfs | Signed manifests, SHA-256 checks, reproducible builds where possible |
| IME and voice differences | Broken input on some keyboards | Keyboard matrix. Settings for input mode |
| Scope creep (advanced features) | Late release | Phase gates. Anything not in a phase goes to "Optional, later" |

---

## 19. Decisions needed

| # | Decision | Default if you don't answer |
|---|---|---|
| D1 | Final app name and package ID | TermX and `app.termx` (placeholder) |
| D2 | Distribution: Play, F-Droid, GitHub | Both `play` and `direct` flavors |
| D3 | minSdk | 26 |
| D4 | Termux priority | T2 shim default; T1 bridge optional; T3 not scheduled |
| D5 | Offline flavor with bundled Alpine | Yes, as a separate flavor, if the size budget allows |
| D6 | armeabi-v7a support | No, unless usage shows demand |
| D7 | Languages at launch | English and Chinese (matching wolfi-terminal), add more on request |
| D8 | License for TermX code | MIT |
| D9 | "Period history" meaning (from your earlier message) | Per-session command history with timestamps, searchable, with retention |
| D10 | Start Phase 0 now on a new repo | Yes, after you confirm D1 and D2 |

---

## 20. Appendix

### 20.1 Verify and build checklist for the 16 KB rule

```sh
#!/usr/bin/env bash
# tools/ci/check16k.sh: fails if the APK has native libs that are not 16 KB aligned.
# Usage: tools/ci/check16k.sh app-direct-arm64-v8a-release.apk
set -euo pipefail

APK="${1:?usage: check16k.sh <apk>}"
ANDROID_HOME="${ANDROID_HOME:?set ANDROID_HOME}"

ZIPALIGN=$(find "$ANDROID_HOME/build-tools" -name zipalign | sort -V | tail -1)
OBJDUMP=$(find "$ANDROID_HOME/ndk" -name llvm-objdump | sort -V | tail -1)

# 1) APK-level alignment for uncompressed entries.
"$ZIPALIGN" -c -P 16 -v 4 "$APK" >/dev/null
echo "zipalign -P 16: OK"

# 2) ELF LOAD alignment for every native library (this is what the loader checks).
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
unzip -q "$APK" 'lib/*' -d "$tmp" || true

bad=0
while IFS= read -r so; do
  while IFS= read -r exp; do
    [ -z "$exp" ] && continue
    if [ "$exp" -lt 14 ]; then
      echo "FAIL: $so has LOAD align 2**$exp (need 2**14 or higher)"
      bad=1
    fi
  done < <("$OBJDUMP" -p "$so" | awk '/LOAD/ { split($NF, a, "[*][*]"); print a[2] }')
done < <(find "$tmp" -name '*.so')

if [ "$bad" -eq 0 ]; then
  echo "ELF 16 KB alignment: OK"
fi
exit "$bad"
```

Run this on the signed APK, not the unsigned build. The ELF check covers the native libraries that get extracted on disk, which is what the loader uses.

### 20.2 Gradle (app module)

```kotlin
// app/build.gradle.kts (excerpt)
android {
    namespace = "app.termx"

    defaultConfig {
        applicationId = "app.termx"
        minSdk = 26
        targetSdk = 37
    }

    buildFeatures {
        buildConfig = true
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "HAS_ALL_FILES", "false")
        }
        create("direct") {
            dimension = "distribution"
            buildConfigField("boolean", "HAS_ALL_FILES", "true")
        }
    }

    packaging {
        jniLibs {
            // Required: proot and loader must exist as files on disk to be executed.
            useLegacyPackaging = true
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}
```

Pin library versions in `gradle/libs.versions.toml` at implementation time, using the latest stable versions you verify then. Don't copy version numbers from this plan.

### 20.3 TermuxBridge permission in the direct and play manifests

Add `com.termux.permission.RUN_COMMAND` only in the flavor or feature module that enables the bridge. Confirm the exact permission name against the current Termux wiki first.

### 20.4 Extra keys and keymap validation rules

- Every key needs a `label` and exactly one of `send`, or `modifier`.
- `send` values are escape strings. Reject unknown control sequences with a message that names the key.
- `long` alternates are optional and capped at 6 per key.
- Layers have fixed names: `base`, `more`, and optional `symbols`.

### 20.5 Decision log template

Record each architectural decision as `docs/decisions/NNNN-title.md` with: context, decision, alternatives, consequences, and status. Start with D1 to D10 from section 19.

---

## 21. Sources and what was verified

Verified during research:

- Storage root causes in the original wolfi-terminal: `core/main/src/main/assets/init-host.sh` and related init scripts (unconditional `-b /sdcard` and `-b /storage`); `SettingsScreen.kt` for its All-files access intent; `MainActivity.kt` requesting only `POST_NOTIFICATIONS`.
- Foreground service `specialUse` declaration and required subtype property: Android 14 foreground service types documentation.
- `specialUse` is not in the 6-hour limit for `dataSync` and `mediaProcessing`: Android 15 foreground service timeout documentation.
- 16 KB page size requirements, deadline of February 1, 2027, and NDK r28 default alignment: Android 16 KB page size documentation.
- Android 17 behavior changes (background audio, BAL, ACCESS_LOCAL_NETWORK, Certificate Transparency): Android 17 behavior changes documentation.
- Termux package build constraints (package name and prefix coupling): termux-packages build guide.
- Termux-style binaries and targetSdk 29 restrictions, and the Briar merge request example: https://code.briarproject.org/briar/briar/-/merge_requests/1279 (an example, not an official Android document; verify on device).
- Android 15 Linux terminal on Pixel (AVF Debian VM, opt-in): The Register, March 2025.

Not verified (flagged in the text):

- Exact RUN_COMMAND intent names and the service's foreground behavior (confirm against the Termux wiki).
- proot's license text (I could not fetch the license page; upstream is believed to be GPL-2.0).
- Official Android scoped storage and app-specific storage pages (the search results pointed to them, but I did not fetch them). The behavior described is standard and should be confirmed on a device during S1 to S10.
- Device behavior: coexistence with official Termux, OEM kill behavior, IME matrix, and the proot exec behavior on targetSdk 37. All require device tests.
