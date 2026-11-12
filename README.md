# PumpkinLauncher

PumpkinLauncher is an Android control panel for a Pumpkin-MC server. The launcher APK and Pumpkin engine are built and distributed independently.

## Architecture

- **Launcher APK:** console UI, world/configuration management, plugin management, and engine version manager.
- **Pumpkin engine module:** a native Android shared library loaded by the launcher at runtime.
- **Engine distribution:** this repository's GitHub Releases. The launcher downloads engine modules independently; it never downloads engine code from the upstream Pumpkin repository directly.

The engine module is built for:

- `arm64-v8a`
- `armeabi-v7a`
- `x86_64`

## Engine channels

- **Release:** stable upstream Pumpkin release source.
- **Nightly:** latest upstream `master` source, published as a prerelease.
- **Installed:** locally installed versions, with the ability to activate another version.

Each engine release contains ABI-specific shared libraries and a `manifest.json` with SHA-256 checksums. The launcher verifies the checksum before installing a module and marks the library read-only before loading it.

## Independent CI/CD

The application and engine pipelines are intentionally separate. A change to the launcher UI does not rebuild the Rust engine, and a change to the engine bridge does not rebuild the APK.

### `app-ci.yml` — Android application

- Runs on pull requests and pushes to `master` when app/build files change, or manually.
- Runs JVM unit tests and builds the debug APK.
- Verifies that the APK does not contain `libpumpkin_engine.so`.
- Uploads the APK as a standalone workflow artifact.
- Does not wait for or download engine build artifacts.

### `engine-ci.yml` — Native engine modules

- Runs on pull requests and pushes to `master` when `engine-bridge/**` changes.
- Builds the three Android ABIs in a parallel matrix.
- Checks the ELF machine type and uploads each ABI library as a separate artifact.
- Daily schedule builds and publishes a Nightly engine release.
- Manual dispatch can publish either Release or Nightly, with an optional upstream Pumpkin ref.
- Generates `manifest.json` containing asset names and SHA-256 checksums.

### Release and installation flow

1. Download and install the launcher APK from the App CI artifact.
2. Open **Engine Manager**.
3. Choose **Releases** or **Nightly**.
4. The launcher fetches the independent engine release metadata and downloads only the library matching the device ABI.
5. The launcher verifies SHA-256 before activating the engine module.

No engine binary is embedded in the APK, so engine updates do not require a new launcher APK.

## Build the launcher APK locally

```bash
./gradlew testDebugUnitTest assembleDebug
```

The APK does not contain Pumpkin-MC. Install the APK, open **Engine Manager**, choose Release or Nightly, and install an engine module before starting a server.

## Android runtime note

Android restricts executing downloaded ELF executables directly from application data directories. The engine is therefore built as a JNI shared library and loaded from app-private storage only after SHA-256 verification and making the file read-only. This design requires testing on real Android devices and should be reviewed against the distribution platform's dynamic-code policies before any store publication.

## Development status

The modular engine path is under active development. CI success confirms cross-compilation and APK packaging; actual server startup, stop, console command handling, and compatibility still require device testing.
