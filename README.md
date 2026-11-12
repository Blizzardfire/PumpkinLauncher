# PumpkinLauncher

PumpkinLauncher is an Android control panel for a Pumpkin-MC server. The launcher APK and Pumpkin engine are built and distributed independently.

## Architecture

- **Launcher APK:** console UI, world/configuration management, plugin management, and engine version manager.
- **Pumpkin engine module:** a native Android shared library loaded by the launcher at runtime.
- **Launcher distribution:** signed APKs are published to this repository's GitHub Releases when a new `v*` version tag is pushed.
- **Engine distribution:** ABI-specific shared libraries are published to the Foenui Package Manager. GitHub Releases contain only the engine manifest, not the native engine binaries.

The engine module is built for:

- `arm64-v8a`
- `armeabi-v7a`
- `x86_64`

## Engine download flow

1. The launcher lists stable, version-tagged releases from this repository.
2. Each release contains `manifest.json`, which records the engine version, upstream Pumpkin commit, per-ABI SHA-256, and Foenui PM download URL.
3. The launcher downloads only the shared library matching the device ABI from `files.oryn.my.id`.
4. The launcher verifies SHA-256 before installing or activating the engine module.

The engine is not bundled in the APK. Updating Pumpkin engine binaries therefore does not require changing the launcher APK, although this release workflow publishes both from the same version tag.

## Independent CI/CD

### `app-ci.yml` — Android application validation

- Runs on pull requests and pushes to `master` when app/build files change.
- Runs JVM unit tests and builds the debug APK.
- Verifies that the APK does not contain `libpumpkin_engine.so`.
- Uploads the debug APK as a temporary workflow artifact only; it does not create a GitHub Release.

### `engine-ci.yml` — Native engine validation

- Runs on pull requests and pushes to `master` when `engine-bridge/**` changes.
- Resolves the latest stable upstream Pumpkin release.
- Builds the three Android ABIs in a parallel matrix and checks each ELF machine type.
- Uploads temporary CI artifacts only; it does not publish to GitHub Releases or Foenui PM.

### `release.yml` — tag-only publication

- Runs only when a new tag matching `v*` is pushed.
- Builds and signs the release APK.
- Builds Pumpkin engine libraries for `arm64-v8a`, `armeabi-v7a`, and `x86_64`.
- Publishes the three engine libraries to `user/fahry-a/pumpkin-engine` in Foenui PM.
- Publishes the signed APK, `manifest.json`, and SHA-256 checksums to the GitHub Release for that tag.
- Does not run on ordinary branch pushes, pull requests, schedules, or manual dispatch.

### Required GitHub Actions secrets

Configure these secrets in the PumpkinLauncher repository before pushing a release tag:

- `FOENUI_UPLOAD_TOKEN`: personal Foenui PM token with `package:write` access to `user/fahry-a/pumpkin-engine`.
- `ANDROID_KEYSTORE_BASE64`: base64-encoded persistent Android signing keystore.
- `ANDROID_KEYSTORE_PASSWORD`: keystore password.
- `ANDROID_KEY_ALIAS`: signing key alias.
- `ANDROID_KEY_PASSWORD`: signing key password.

Keep the same Android keystore for every release so users can install updates over an existing version. Do not commit the keystore or passwords to the repository.

## Build the launcher APK locally

For a debug build:

```bash
./gradlew testDebugUnitTest assembleDebug
```

For a local unsigned release variant:

```bash
./gradlew assembleRelease
```

## Android runtime note

Android restricts executing downloaded ELF executables directly from application data directories. The engine is therefore built as a JNI shared library and loaded from app-private storage only after SHA-256 verification and making the file read-only. This design requires testing on real Android devices and should be reviewed against the distribution platform's dynamic-code policies before any store publication.

## Development status

The modular engine path is under active development. CI success confirms cross-compilation and APK packaging; actual server startup, stop, console command handling, and compatibility still require device testing.
