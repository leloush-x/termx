# TermX 0.8.0 changes

This release updates terminal modifiers and IME handling, lets settings pages show the selected wallpaper, and restores terminal focus on return. The process-management screen and its process signaling code have since been removed; terminal sessions still run through `SessionService` while the app is backgrounded.

## Verification

- GitHub Actions runs `testDirectDebugUnitTest`, builds the direct release APK, checks native library alignment and signature, then publishes the tagged release.
- Device checks for keyboard-specific IME behavior remain pending; this release is gated by the repository's CI workflow.

## Behavior

- CTRL, ALT and SHIFT share one state across the extra keys row and physical keyboard. Tap arms a one-shot; long press locks until tapped again.
- IME suggestions remain enabled and personalized learning is disabled. Composition is sent incrementally and deletion follows the shadow line.
- The wallpaper visibility preference defaults to enabled and is saved as `settings_wallpaper`.
- Background process management is not exposed in Settings. `SessionService` remains a foreground service so active terminal sessions can keep running when TermX is backgrounded.
