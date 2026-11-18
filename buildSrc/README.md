# MusicKit SDK compatibility

The build supports the official MusicKit Android playback SDK 1.1.1. It validates
the supplied AAR and applies a narrowly scoped 64-bit catalog-ID compatibility
adjustment during compilation. The original SDK file remains unchanged and is
not included in source control.

The supplied playback and authentication AARs also omit JVM stack-map frames in
methods with branches. The build selects only affected classes present in those
AARs and uses AGP's `COMPUTE_FRAMES_FOR_INSTRUMENTED_CLASSES` mode to reconstruct
their verification metadata. It does not alter other SDK instructions, suppress
R8 warnings, or transform application classes.

After downloading the SDK, compatibility tests can be run with:

```sh
./gradlew -p buildSrc test --tests dev.amatv.build.MusicKitSongIdPatchTest
```
