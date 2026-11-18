# Tests

Run the JVM tests and lint with the Gradle wrapper.

```sh
./gradlew :app:testDebugUnitTest :app:testStandaloneSystemUnitTest :app:lintStandalone :app:lintStandaloneSystem
./gradlew -p buildSrc test
```

Run one Android TV test by choosing the emulator and test class.

```sh
ANDROID_SERIAL=EMULATOR_SERIAL ./gradlew :benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.amatv.benchmark.RuntimeControlFixtureTest
```

This test checks playback commands with isolated demo data on an emulator. Check each test class for its device and account requirements. Use `Class#method` to run one method. Pass test arguments as `-Pandroid.testInstrumentationRunnerArguments.<name>=<value>`. Reports are under `benchmark/build/reports/`.

## Record a Compose trace

Install the benchmark APK on an Android 11 or later device. The benchmark build uses the developer token described in the [build guide](build.md).

```sh
ANDROID_SERIAL=TV_ADB_SERIAL ./gradlew :app:installBenchmark
```

Open the app and enable tracing.

```sh
adb -s TV_ADB_SERIAL shell am broadcast \
  -a androidx.tracing.perfetto.action.ENABLE_TRACING \
  -n dev.amatv/androidx.tracing.perfetto.TracingReceiver
```

A broadcast result of `1` means tracing is enabled. A result of `2` means it was already enabled. Run the broadcast again after restarting the app process.

Add this data source to the Perfetto configuration alongside `linux.ftrace`. Keep `atrace_apps: "dev.amatv"` and the `gfx` and `view` categories in the ftrace configuration.

```protobuf
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

In the trace, use `AMATV:home-scroll` to find the time spent scrolling on Home. Look for named Compose sections and compare them with frame timing. Nested sections include time already counted in their parent. Measure frame timing again with tracing disabled when comparing builds.
