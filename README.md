This covers building the native Pumpkin server binaries, packaging the Android
app, and installing it — all from a terminal.

## Prerequisites

- **Rust** (via [rustup](https://rustup.rs))
- **Android NDK** (installed via Android Studio's SDK Manager, or standalone)
- **cargo-ndk**: `cargo install cargo-ndk`# Building Pumpkin Launcher (Command Line, No Android Studio Required)

This covers building the native Pumpkin server binaries, packaging the Android
app, and installing it — all from a terminal.

## Shortcut: skip building the native binaries entirely

Pre-built Android binaries (`libpumpkin.so` for `arm64-v8a`, `armeabi-v7a`,
and `x86_64`) are published on this repo's
[Releases page](<PASTE_YOUR_RELEASES_URL_HERE>). If you just want to build
the app itself, download those and skip straight to
**Step 2: Copy the binaries into the Android project** below — no Rust
toolchain, NDK, or `cargo-ndk` setup needed at all.

Only follow Step 1 if you want to compile the native binaries yourself
(e.g. to track a newer Pumpkin commit than what's currently published).

## Prerequisites

- **Rust** (via [rustup](https://rustup.rs))
- **Android NDK** (installed via Android Studio's SDK Manager, or standalone)
- **cargo-ndk**: `cargo install cargo-ndk`
- **Android SDK Platform Tools** (for `adb`, if installing via command line)
- The Rust targets for all three architectures:
  ```
  rustup target add aarch64-linux-android
  rustup target add armv7-linux-androideabi
  rustup target add x86_64-linux-android
  ```

## Step 1 (optional): Build the native server binaries yourself

From the root of the Pumpkin source repo:

```powershell
$env:ANDROID_NDK_HOME = "C:\path\to\Android\Sdk\ndk\<version>"

cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 --platform 26 -o .\android-build build --release -p pumpkin
```

> **Note:** `--platform 26` targets Android API level 24+ networking symbols
> (e.g. `getifaddrs`/`freeifaddrs`), which some of Pumpkin's dependencies
> require. Without it, linking may fail with `undefined symbol` errors.

This produces three binaries:

```
target\aarch64-linux-android\release\pumpkin
target\armv7-linux-androideabi\release\pumpkin
target\x86_64-linux-android\release\pumpkin
```

You may see `error: No cdylib file found to copy` at the very end — this is
a harmless quirk of `cargo-ndk` expecting a `cdylib` artifact, which Pumpkin
doesn't produce (it builds a plain executable). As long as all three binaries
above exist, the build succeeded.

## 2. Copy the binaries into the Android project

```powershell
copy target\aarch64-linux-android\release\pumpkin <ANDROID_PROJECT>\app\src\main\jniLibs\arm64-v8a\libpumpkin.so
copy target\armv7-linux-androideabi\release\pumpkin <ANDROID_PROJECT>\app\src\main\jniLibs\armeabi-v7a\libpumpkin.so
copy target\x86_64-linux-android\release\pumpkin <ANDROID_PROJECT>\app\src\main\jniLibs\x86_64\libpumpkin.so
```

Each binary must be placed under `jniLibs/<abi>/libpumpkin.so` — this exact
naming lets Android's package installer treat it as a native library, which
is what grants it execute permission at install time.

## 3. Build the Android APK

From the Android project root (where `gradlew`/`gradlew.bat` live):

```powershell
cd <ANDROID_PROJECT>
.\gradlew clean
.\gradlew assembleDebug
```

Output APK:

```
app\build\outputs\apk\debug\app-debug.apk
```

### If the build fails with `OutOfMemoryError`

Add/update `gradle.properties` in the project root:

```properties
org.gradle.jvmargs=-Xmx8192m -XX:MaxDirectMemorySize=2048m -Dfile.encoding=UTF-8
```

Then restart the Gradle daemon so it picks up the change:

```powershell
.\gradlew --stop
.\gradlew assembleDebug
```

## 4. Install on a device

With a phone connected via USB (USB debugging enabled) or an emulator running:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

`-r` reinstalls in place over any existing install — no need to manually
uninstall first.

If `adb` isn't on your `PATH`, use the full path from your SDK install, e.g.:

```powershell
& "C:\path\to\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
```

## Automating steps 1–2

`pumpkin_auto_build.py` (included in this repo) automates checking for new
Pumpkin releases and rebuilding the native binaries automatically. See the
comments at the top of that file for configuration. It stops short of
building/installing the APK itself (steps 3–4 above), so you can review
changes before deploying.

- **Android SDK Platform Tools** (for `adb`, if installing via command line)
- The Rust targets for all three architectures:
  ```
  rustup target add aarch64-linux-android
  rustup target add armv7-linux-androideabi
  rustup target add x86_64-linux-android
  ```

## 1. Build the native server binaries

From the root of the Pumpkin source repo:

```powershell
$env:ANDROID_NDK_HOME = "C:\path\to\Android\Sdk\ndk\<version>"

cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 --platform 26 -o .\android-build build --release -p pumpkin
```

> **Note:** `--platform 26` targets Android API level 24+ networking symbols
> (e.g. `getifaddrs`/`freeifaddrs`), which some of Pumpkin's dependencies
> require. Without it, linking may fail with `undefined symbol` errors.

This produces three binaries:

```
target\aarch64-linux-android\release\pumpkin
target\armv7-linux-androideabi\release\pumpkin
target\x86_64-linux-android\release\pumpkin
```

You may see `error: No cdylib file found to copy` at the very end — this is
a harmless quirk of `cargo-ndk` expecting a `cdylib` artifact, which Pumpkin
doesn't produce (it builds a plain executable). As long as all three binaries
above exist, the build succeeded.

## 2. Copy the binaries into the Android project

```powershell
copy target\aarch64-linux-android\release\pumpkin <ANDROID_PROJECT>\app\src\main\jniLibs\arm64-v8a\libpumpkin.so
copy target\armv7-linux-androideabi\release\pumpkin <ANDROID_PROJECT>\app\src\main\jniLibs\armeabi-v7a\libpumpkin.so
copy target\x86_64-linux-android\release\pumpkin <ANDROID_PROJECT>\app\src\main\jniLibs\x86_64\libpumpkin.so
```

Each binary must be placed under `jniLibs/<abi>/libpumpkin.so` — this exact
naming lets Android's package installer treat it as a native library, which
is what grants it execute permission at install time.

## 3. Build the Android APK

From the Android project root (where `gradlew`/`gradlew.bat` live):

```powershell
cd <ANDROID_PROJECT>
.\gradlew clean
.\gradlew assembleDebug
```

Output APK:

```
app\build\outputs\apk\debug\app-debug.apk
```

### If the build fails with `OutOfMemoryError`

Add/update `gradle.properties` in the project root:

```properties
org.gradle.jvmargs=-Xmx8192m -XX:MaxDirectMemorySize=2048m -Dfile.encoding=UTF-8
```

Then restart the Gradle daemon so it picks up the change:

```powershell
.\gradlew --stop
.\gradlew assembleDebug
```

## 4. Install on a device

With a phone connected via USB (USB debugging enabled) or an emulator running:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

`-r` reinstalls in place over any existing install — no need to manually
uninstall first.

If `adb` isn't on your `PATH`, use the full path from your SDK install, e.g.:

```powershell
& "C:\path\to\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
```

## Automating steps 1–2

`pumpkin_auto_build.py` (included in this repo) automates checking for new
Pumpkin releases and rebuilding the native binaries automatically. See the
comments at the top of that file for configuration. It stops short of
building/installing the APK itself (steps 3–4 above), so you can review
changes before deploying.
