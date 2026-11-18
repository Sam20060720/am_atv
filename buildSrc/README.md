# Build patches

## MusicKit Android SDK 1.1.1

Gradle checks the playback AAR before building. It updates the SDK's song
metadata parser during compilation so it can read 64-bit catalog IDs. Gradle
also regenerates missing JVM stack-map frames in affected playback and
authentication SDK classes. Both changes apply to build output.

After downloading the SDK, run the compatibility test.

```sh
./gradlew -p buildSrc test --tests dev.amatv.build.MusicKitSongIdPatchTest
```

## Compose Foundation 1.11.4

`HomePrefetchBudgetPatch` changes the idle-time branch of
`AndroidPrefetchScheduler.availableTimeNanos()` to call
`HomePrefetchBudget.remainingIdleBudgetNanos()`. This gives idle prefetch
requests a shared 3 ms budget per frame. Gradle stops if the expected method
changes. When upgrading Foundation, check whether the new version limits idle
prefetch time before deciding if the patch is still needed.
