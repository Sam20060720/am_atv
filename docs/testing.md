# Tests

Run the shared JVM regressions and release lint with the Gradle wrapper:

```sh
./gradlew :app:testDebugUnitTest :app:testStandaloneSystemUnitTest :app:lintStandalone :app:lintStandaloneSystem
./gradlew -p buildSrc test
```

For an Android TV instrumentation test, select the device and test class:

```sh
ANDROID_SERIAL=TV_ADB_SERIAL ./gradlew :benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.amatv.benchmark.RuntimeControlFixtureTest
```

The runtime fixture requires an emulator and uses isolated storage without
account access or playback. Tests with other hardware, playback, or fixture
requirements describe them in their test classes. Use `Class#method` to run one
case and pass its required inputs with `-Pandroid.testInstrumentationRunnerArguments.<name>=<value>`.
Gradle saves reports under `benchmark/build/reports/`.
