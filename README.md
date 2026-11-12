# PumpkinLauncher

PumpkinLauncher is an Android control panel for a Pumpkin-MC server. The launcher APK and Pumpkin engine are built and distributed independently.

## Architecture

- **Launcher APK:** console UI, world/configuration management, plugin management, and engine version manager.
- **Pumpkin engine module:** a native Android shared library loaded by the launcher at runtime.
- **Launcher distribution:** signed APKs are published to this repository's GitHub Releases when a new `v*` version tag is pushed.
- **Engine distribution:** stripped, `zip -9` compressed engine packages are published both as GitHub Release assets and to the Foenui Package Manager (`files.oryn.my.id`).

The engine module is built for:

- `arm64-v8a`
- `armeabi-v7a`
- `x86_64`

## Engine download flow

1. The launcher lists two channels from this repository: stable releases (`pumpkin-<upstream tag>`) and the rolling `nightly` prerelease.
2. Each release contains `manifest.json`, which records the engine channel/version, upstream Pumpkin commit, per-ABI archive SHA-256, extracted library SHA-256 (`contentSha256`), and the Foenui PM download URL.
3. The launcher downloads only the compressed package matching the device ABI from `files.oryn.my.id`, falling back to the GitHub Release asset URL.
4. The launcher verifies the archive SHA-256, extracts the library, then verifies the extracted SHA-256 before installing or activating the engine module.

Older releases that ship a bare `.so` remain installable through the same flow.

The engine is not bundled in the APK. Updating Pumpkin engine binaries therefore does not require changing the launcher APK.

## Independent CI/CD

### `app-ci.yml` — Android application validation

- Runs on pull requests and pushes to `master` when app/build files change, plus manual validation dispatch.
- Runs JVM unit tests and builds the debug APK.
- Verifies that the APK does not contain `libpumpkin_engine.so`.
- Uploads the debug APK as a temporary workflow artifact only; it does not create a GitHub Release.

### `engine-ci.yml` — Native engine validation

- Runs on pull requests and pushes to `master` when `engine-bridge/**` changes.
- Resolves the latest stable upstream Pumpkin release.
- Builds the three Android ABIs in a parallel matrix and checks each ELF machine type.
- Uploads temporary CI artifacts only; it does not publish to GitHub Releases or Foenui PM.

### `releases.yml` — launcher release publication

- Runs only when a new tag matching `v*` is pushed.
- Validates the tag and the Android signing secrets, then builds and signs the release APK.
- Publishes the signed APK and `SHA256SUMS.txt` to the GitHub Release for that tag.
- Does not build or publish engine binaries, and does not require the Foenui token.
- Does not run on ordinary branch pushes, pull requests, schedules, or manual dispatch.

### `engine-releases.yml` — scheduled engine publication

- Runs on a cron schedule (every 6 hours) and via manual dispatch; engine builds are independent of launcher tags.
- Checks the newest official `Pumpkin-MC/Pumpkin` version tag: if `pumpkin-<tag>` does not exist here yet, it builds the release channel for that tag.
- Otherwise it builds the `nightly` channel from the latest upstream `master` commit, and skips entirely when that commit is already published (idempotent via the `Source: <sha>` line in the release body).
- Builds the three ABIs in a parallel matrix, strips symbols (`llvm-strip`, JNI exports preserved), and compresses each library with `zip -9`.
- Publishes the compressed packages to `user/fahry-a/pumpkin-engine` in Foenui PM and attaches them plus `manifest.json` and `SHA256SUMS.txt` to the GitHub Release (`nightly` is a rolling prerelease, never marked latest).

### Required GitHub Actions secrets

Configure these secrets in the PumpkinLauncher repository:

- `FOENUI_UPLOAD_TOKEN`: personal Foenui PM token with `package:write` access to `user/fahry-a/pumpkin-engine` (required by `engine-releases.yml`).
- `ANDROID_KEYSTORE_BASE64`: base64-encoded persistent Android signing keystore (for example, encode it locally with `base64 -w0 release-key.jks`) (required by `releases.yml`).
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
