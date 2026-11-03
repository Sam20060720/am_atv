# APK and source distribution

```sh
tools/build-standalone.sh
tools/build-standalone-gecko.sh
tools/export-source.sh
```

Outputs are written to `dist/`. The source archive contains the committed source
tree and licenses, without Git history, credentials, Apple binaries or local data.

The standalone APK contains a signed, expiring developer credential and uses no
token endpoint. Each recipient signs in on their own TV. Rebuild before the
credential expires; see [setup](musickit-setup.md).

Keep the APK signing key so updates can preserve existing installations. The
local standalone configuration uses the existing development signing identity;
configure your own release signing identity for your distribution.

`AMATV-<version>.apk` uses the system WebView for sign-in.
`AMATV-<version>-gecko.apk` bundles GeckoView for sign-in only, for ARM32 and
ARM64 TVs. The Gecko browser runs in a separate login process; music playback
continues to use the native MusicKit SDK. Both APKs have the same application ID
and signing identity, so installing one replaces the other and preserves app data.

Gecko native libraries are compressed in the APK and extracted for the device's
architecture during installation. To omit the other architecture entirely:

```sh
AMATV_GECKO_ABI=armeabi-v7a tools/build-standalone-gecko.sh # -gecko-arm32.apk
AMATV_GECKO_ABI=arm64-v8a tools/build-standalone-gecko.sh   # -gecko-arm64.apk
```

Both login screens keep the diagnostic console hidden by default. Press the
remote's MENU key to show or hide it. The panel does not take focus or intercept input.
Diagnostics are bounded in memory and redact credentials and URL parameters.

## Install-time Baseline Profiles for sideloaded TVs

`tools/build-standalone.sh` now produces both the existing flat
`dist/AMATV-<version>.apk` and `dist/AMATV-<version>-install-kit.zip`. The ZIP contains
one APK, the AGP-generated API 28–30 and API 31+ DexMetadata files, a self-contained
Python installer, and a manifest with package/version, SHA-256 hashes and profile
DEX checksum/method-count evidence. No Apple account data is packaged.

With Python 3.9+ and Android SDK Platform-Tools installed, extract the ZIP, connect
the TV with ADB, then run from its extracted folder:

```sh
python3 install.py --serial <TV_ADB_SERIAL>
# File verification only; does not contact a device:
python3 install.py --validate-only
```

The installer requires an explicit serial. It validates all files before reading
the TV SDK, selects the matching SDK range from AGP's `output-metadata.json`, stages
the APK and DM with the same basename, and installs them together using
`adb install-multiple -r`. It uses `--no-streaming` only when the local `adb help`
advertises it. Existing app data and the signing identity are retained. It does
not clear data, reset ART/profile state, change compiler settings or force a
full-package `speed` compilation.

The APK itself already includes `ProfileInstaller`. Installing only that APK
remains supported: the profile can be installed after first launch for later
background ART optimization. It does **not** guarantee that the first launch has
already been AOT compiled. The companion DM supplies the profile during the
standard installation session, allowing install-time profile-guided optimization
without waiting for that later background cycle. Actual compiler policy remains
device-dependent; verify the TV's resulting `speed-profile` state and ordinary
performance rather than assuming every device/screen reaches 60fps.

To package an already-built APK with a dated filename, without another build:

```sh
python3 tools/package-standalone.py --name AMATV-20261102 --zip
```

The packager uses SDK ranges recorded by AGP instead of assuming that directory
`0` or `1` always means a particular Android version. It verifies the actual APK
manifest identity, profile versions (`010_P` for API 28–30, `015_S` for API 31+),
metadata version `002`, and each DEX's ZIP CRC32/type/method counts. A changed APK
or a profile from the benchmark/Gecko variant fails validation. Do not manually
rewrite DEX keys or repackage profile assets: Android 11's standard
`prepare_app_profile` / `profman --copy-and-update-profile-key` path updates install
location keys using matching DEX checksums and method counts. Keep each DM paired
with its exact APK build, including when versionCode stays the same.

The checked-in baseline rules include the measured Home focus, placement,
pre-fetch, shadow, backdrop and decorative TextureView helpers. These are focused
`H`/`P` rules; the distribution installer does not substitute an undocumented
whole-app compiler override.

References: [Android's APK + DexMetadata installation workflow](https://developer.android.com/topic/performance/baselineprofiles/manually-create-measure),
[Android 11 profile-key update](https://android.googlesource.com/platform/art/+/refs/heads/android11-release/libprofile/profile/profile_compilation_info.cc),
[Android 11 installd profile preparation](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android11-release/cmds/installd/dexopt.cpp).
