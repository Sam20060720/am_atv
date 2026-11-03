# Composition tracing for the benchmark APK

The `benchmark` application variant adds `androidx.compose.runtime:runtime-tracing` through the existing Compose BOM (currently **1.11.4**) and `androidx.tracing:tracing-perfetto-binary:1.0.1`. The former depends on `tracing-perfetto:1.0.1`, so its Java bridge and packaged native binary match. These dependencies are absent from `standalone` and `release`. The existing `app/src/benchmark/AndroidManifest.xml` makes the app shell-profileable without making it debuggable; the variant retains release optimization and the existing signing identity.

Build the application with the normal local credential configuration:

```sh
./gradlew :app:assembleBenchmark
```

Do not use an `assembleStandalone` APK for this capture. After installing the benchmark APK and launching the existing app, enable tracing in its current process:

```sh
adb -s 192.168.1.116:5555 shell am broadcast \
  -a androidx.tracing.perfetto.action.ENABLE_TRACING \
  -n dev.amatv/androidx.tracing.perfetto.TracingReceiver
```

No library-path extra or cold-start/persistent broadcast is needed because the `.so` is inside the APK. Wait for the ordered-broadcast result: success is `result=1` and the JSON requiredVersion is `1.0.1`; `result=2` means already enabled. Other result codes require diagnosis before capturing. The receiver requires the shell's `android.permission.DUMP`; ordinary apps cannot invoke it. This SDK requires Android 11/API 30 or newer, matching the tested TV. Enabling lasts for the current process; after a process restart, enable again. Do not force-stop during a playback experiment merely to reset tracing.

Add the following data source to the existing working scheduler/atrace Perfetto text config, retaining its buffers and duration:

```protobuf
# The actual AndroidX 1.0.1 native category is "rendering".
data_sources {
  config {
    name: "track_event"
    target_buffer: 0
    track_event_config {
      disabled_categories: "*"
      enabled_categories: "rendering"
    }
  }
}
```

The existing `linux.ftrace` data source should keep `atrace_apps: "dev.amatv"`, `gfx`, `view`, scheduler switch/waking and any already-supported frame events. The `track_event` source adds named Compose slices alongside those main-thread `Choreographer#doFrame`/animation blocks; atrace alone does not produce the compiler's composable names. Capture only the same bounded navigation path used by the baseline. Compare composition slices to the long animation intervals and separately inspect draw and RenderThread work. Inclusive nested composition durations must not be summed as independent frame time. Tracing adds overhead, so use it for attribution, then verify performance with ordinary uninstrumented measurements.

The shipped library registers `ComposeTracingInitializer` through AndroidX Startup. It calls `Composer.setTracer`, forwards the compiler-provided `info` name to `PerfettoSdkTrace.beginSection`, and reports enabled only after the native bridge is enabled. No product logging, custom composition counters or application startup code is necessary.

Sources checked: [Compose tracing documentation](https://developer.android.com/develop/ui/compose/tooling/tracing), the exact [runtime-tracing 1.11.4 POM](https://dl.google.com/dl/android/maven2/androidx/compose/runtime/runtime-tracing/1.11.4/runtime-tracing-1.11.4.pom), its published sources/manifest, and the cached `tracing-perfetto`/`tracing-perfetto-binary` **1.0.1** AARs. The native [registration and category implementation](https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/tracing/tracing-perfetto-binary/src/main/cpp/tracing_perfetto.cc) uses `kSystemBackend`, registers `TrackEvent`, and emits the `rendering` category; the actual 1.0.1 arm64 binary also contains this category. Merely adding these dependencies is not proof that a captured trace contains composable names: confirm named slices in the resulting trace before interpreting it.

## Local Foundation 1.11.4 idle-prefetch deadline backport

The real-TV composition trace identified an idle-prefetch request receiving Long.MAX_VALUE time and running about71ms while composing nested card shells. Foundation1.11.4 already enables pausable composition; its Android scheduler nevertheless makes delayed/idle frames unbounded, preventing that pause budget from expiring. Upstream b/531649461 fixes the mechanism in Foundation1.13.0-beta02 (enabled by default), but that AAR requires compileSdk37.1 and AGP9.1.0; this project currently uses compileSdk36 and AGP8.13.2. Remove the local backport when adopting the fixed framework with its coordinated toolchain upgrade.

`HomePrefetchBudgetVisitorFactory` rewrites only the exact1.11.4 `AndroidPrefetchScheduler$PrefetchRequestScopeImpl.availableTimeNanos()J` idle-branch Long.MAX_VALUE constant into `HomePrefetchBudget.remainingIdleBudgetNanos()J`. It validates the method/access/opcodes, exactly one expected constant, and the unchanged finite-deadline branch; unsupported bytecode fails the build. No reflection, SDK feature flag or framework source fork is used. The normal finite branch is untouched.

The helper grants one shared3ms allowance per Choreographer frame to the app's idle prefetch work, including nested requests. It starts that allowance at the first actual idle request after drawing so expensive drawing cannot permanently starve prefetch. After expiration every nested request sees0 until the next distinct frame callback re-arms it. One singleton callback is posted at most once for that next frame; no perpetual frame ticker is retained after work stops. It does not grant a fresh3ms on each call. Atomic apply/premeasure/individual composition segments can still overrun the allowance; this bounds cooperative prefetch opportunities and does not guarantee hard preemption or60fps. Validate the transformed APK and paired device traces before claiming improvement.

The benchmark APK also emits `AMATV:home-scroll` Android async trace sections (cookie = identity of the retained Home LazyListState) from actual `isScrollInProgress` start/stop transitions. The section closes on normal stop, feed hiding or effect disposal. This uses only a distinct Boolean snapshot observer, not per-pixel counters, and the constant build-type guard removes it from standalone/release. Use these intervals to compare actual continuous-scroll presentation cadence separately from intentional idle artwork-video cadence.

A bounded active-row-pinning candidate was measured and removed after it showed no material improvement over the3ms-budget candidate: actual-present p90 stayed about33ms and the maximum gap stayed66–67ms, while frame median rose from18.7 to20.1ms. The production implementation retains the symmetric cache window and finite idle-prefetch deadline without additional row pins. Source details remain in `work/tv-performance/20261101/cache-retention-source.md`; the trial does not establish that pinning is generally faster.
