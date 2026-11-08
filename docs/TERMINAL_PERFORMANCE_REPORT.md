# TermX terminal performance report and improvement plan

**Review date:** 2026-11-08  
**Scope:** source inspection and Android rendering research; no device benchmark was run.

## Debug-branch implementation

The debug candidate fixes cursor frame scheduling so idle trails no longer request frames, and adds a bounded per-renderer cache for repeated non-ASCII glyph width measurements. Unit tests cover idle/active frame scheduling, trail expiry, and cache collisions. CI and physical-device checks are pending. Hardware benchmarks are still needed to quantify the gains and decide whether row caching or a GPU-specific renderer is justified.

## Executive summary

TermX already uses Android's hardware-accelerated `Canvas`: `MainActivity` is marked `android:hardwareAccelerated="true"`, and the terminal is a custom `View` drawing through `Canvas`. A custom OpenGL/GLES renderer is therefore not the first step. Android already sends supported Canvas drawing through its hardware accelerated 2D pipeline; whether the CPU or GPU is limiting a terminal frame needs measurement.

There was a concrete source of avoidable work: smooth cursor and cursor trail are both initialized to `true`, and `TerminalView.onDraw()` called `postInvalidateOnAnimation()` whenever the trail flag was true. The flag remains true when the cursor is idle, so each scheduled frame called `TerminalRenderer.render()` and walked the complete visible grid even without output. The debug candidate schedules frames only while the cursor is moving or a trail sample remains visible. Confirm idle frame frequency and battery impact on a device before attributing a measured gain.

## Current rendering path

1. The pinned Termux terminal emulator reads PTY bytes on an I/O thread and queues them for the main thread. Its `TerminalSession` documentation says terminal emulation and callbacks run on the main thread ([pinned v0.118.3 source](https://github.com/termux/termux-app/blob/v0.118.3/terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java)).
2. `TerminalBackend.onTextChanged()` notifies the session service and calls `TerminalView.onScreenUpdated()`.
3. `onScreenUpdated()` updates scroll/selection state, calls `invalidate()`, and, when accessibility is enabled, rebuilds the content description from terminal text.
4. `TerminalView.onDraw()` advances the optional cursor animation and calls `TerminalRenderer.render()`.
5. `TerminalRenderer` scans every visible row and column, groups cells by style, checks Unicode cell widths, and issues Canvas text/rectangle drawing operations.

The architecture document already calls for damaged-row redraws, batching PTY reads, and capping UI updates to display refresh, but the current view redraws the full visible grid after its update callback. Those design goals are not yet backed by a macrobenchmark; the benchmark module remains unchecked in the execution plan.

## Findings, ranked

### 1. Continuous idle redraw from cursor trail scheduling — fixed in debug candidate; device validation pending

In [TerminalView.java](/root/termx/app/src/main/java/app/termx/terminal/view/TerminalView.java:81), smooth cursor and trail both default to `true`. In `onDraw()`, the condition at line 1172 schedules another animation frame whenever `mCursorTrailEnabled` is true. Trail samples are only added while the cursor moves and are ignored after their decay interval, but the scheduling condition does not check either fact. Each scheduled frame traverses the full terminal grid.

**Change:** schedule another frame only while the cursor is moving or while at least one trail sample is still visible. Stop scheduling after all trail samples fade. Unit tests cover the idle, cursor-moving, visible-trail, and expired-trail cases. Verify idle frame rate and battery draw on a device.

### 2. No terminal-specific performance measurements — high priority

The plan lists a macrobenchmark for startup, frames, and scroll, but it is not implemented. Existing architecture targets include scrolling `seq 10000` at 55 FPS; they have not been shown as measured device results.

**Plan:** add a `Macrobenchmark` module with a reproducible terminal workload: idle terminal, continuous ASCII output (`seq 1 10000`), colored output, Unicode output, and scrollback fling. Record median frame time, p95/p99 frame overrun, dropped frames, CPU time, GPU/render time, temperature, and power on one mid-tier and one low-tier device. Use traces to separate terminal emulation on the main thread from view drawing and GPU work. Android `FrameTimingMetric` captures frame timing and overrun; Macrobenchmark can be run in CI, while power/device comparisons need representative hardware ([Macrobenchmark overview](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview), [FrameTimingMetric](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics)).

### 3. Main-thread work and accessibility text rebuilding — medium priority

The pinned emulator processes terminal bytes and callbacks on the main thread. `onScreenUpdated()` additionally calls `getText()` to rebuild a full content description whenever accessibility is enabled. This may become costly during fast output for users of accessibility services.

**Plan:** measure parser, callback, accessibility extraction, and draw time separately. If accessibility extraction is material, coalesce descriptions to frame cadence or expose a bounded visible-screen description. Do not move terminal parsing to another thread as a speculative optimization: emulator/view state is currently main-thread oriented and would require a deliberate synchronization redesign.

### 4. Repeated Unicode width measurement — bounded cache added; benchmark validation pending

`TerminalRenderer` precomputes widths for ASCII, but calls `Paint.measureText()` for non-ASCII code points on each render. The same Unicode glyphs commonly recur in prompts, CJK text, and command output.

**Change:** cache non-ASCII advances in a 1,024-entry direct-mapped table owned by each renderer. Recreating the renderer on font or size changes also resets this cache. Unit tests cover hits, misses, and collisions. Measure Unicode-heavy output and check wide-character, combining-character, emoji, and font-fallback behavior on device.

### 5. OpenGL/GLES or `SurfaceView` renderer — defer unless profiling supports it

The app already has hardware acceleration enabled, so replacing Canvas with GLES is not equivalent to “turning on the GPU.” A separate surface/thread would add synchronization against the main-thread emulator, plus work for text selection, overlays, resizing, accessibility, and low-end GPU compatibility. `SurfaceView` is intended for a surface rendered by a secondary thread, and requires coordinating its lifecycle and shared state ([SurfaceView API](https://developer.android.com/reference/android/view/SurfaceView)).

**Recommendation:** keep Canvas for now. Consider a GPU glyph atlas or dedicated renderer only if benchmarks show rendering submission or fill-rate is still the limiting factor after idle invalidation, update scheduling, and CPU-side work are corrected. Android recommends measuring custom-view drawing and eliminating unnecessary invalidations before larger rendering changes ([custom view optimization](https://developer.android.com/develop/ui/views/layout/custom-views/optimizing-view), [hardware acceleration](https://developer.android.com/develop/ui/views/graphics/hardware-accel)).

## Suggested implementation plan

| Phase | Work | Exit criteria |
|---|---|---|
| 0. Baseline | Add Macrobenchmark and trace markers for PTY output, emulator callback, and terminal draw. Capture idle, ASCII, styled, Unicode, and scroll workloads. | Results recorded on low- and mid-tier Android devices; identify CPU, GPU, and idle frame-rate bottleneck. |
| 1. Idle efficiency | Fix cursor/trail frame scheduling. Ensure no frame loop after animation and trail expiry. Gate accessibility description updates. | Idle terminal stops requesting frames; cursor and trail animation still look correct; accessibility output remains current. |
| 2. Output efficiency | Measure callback/update cadence. Coalesce redundant redraw and ancillary UI work to vsync while retaining all PTY bytes and emulator semantics. | Lower CPU/frame time during burst output with no lost or reordered terminal content. |
| 3. Renderer tuning | Optimize measured Unicode width work and row/cell traversal. Evaluate row-level caching only if traces show full-grid CPU traversal is material. | Improved benchmark against phase 0, with Unicode, styles, selection, alternate screen, and scrollback regression coverage. |
| 4. GPU prototype (conditional) | Build a separate experiment for a glyph atlas or GLES renderer only if GPU profiling shows Canvas is the limiting stage. | Demonstrable user-visible win across tested devices, no input/overlay regressions, and acceptable memory/power before considering production adoption. |

Do not claim “GPU accelerated” as a new improvement until the measured results show a real benefit over the current hardware-accelerated Canvas path.

## References

- [Android hardware acceleration](https://developer.android.com/develop/ui/views/graphics/hardware-accel)
- [Optimize a custom view](https://developer.android.com/develop/ui/views/layout/custom-views/optimizing-view)
- [Macrobenchmark](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)
- [Macrobenchmark frame metrics](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics)
- [SurfaceView API](https://developer.android.com/reference/android/view/SurfaceView)
- [Termux terminal-emulator v0.118.3 `TerminalSession`](https://github.com/termux/termux-app/blob/v0.118.3/terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java)
