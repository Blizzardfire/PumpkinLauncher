# PumpkinLauncher

PumpkinLauncher is an Android control panel for a Pumpkin-MC server. The launcher APK and the Pumpkin engine are distributed independently.

## Architecture

- **Launcher APK:** console UI, world/configuration management, plugin management, and engine version manager.
- **Pumpkin engine module:** a native Android shared library loaded by the launcher at runtime.
- **Engine distribution:** this repository's GitHub Releases. The launcher never downloads engine code from the upstream Pumpkin repository directly.

The engine module is built for:
- \`arm64-v8a\`
- \`armeabi-v7a\`
- \`x86_64\`

## Engine channels

- **Release:** stable upstream Pumpkin release source.
- **Nightly:** latest upstream \`master\` source, published as a prerelease.
- **Installed:** locally installed versions, with the ability to activate another version.

Each engine release contains ABI-specific shared libraries and a \`manifest.json\` with SHA-256 checksums. The launcher verifies the checksum before installing a module and marks the library read-only before loading it.

## CI/CD

The workflow at \`.github/workflows/auto-build-pumpkin-apk.yml\`:

1. Resolves the requested upstream Pumpkin ref.
2. Builds the Android JNI engine bridge with a three-ABI GitHub Actions matrix.
3. Builds the launcher APK independently, without embedding the engine.
4. On scheduled or manual runs, publishes the engine libraries and manifest to this repository's GitHub Releases.

### Triggers

- Pull request to \`master\`: validate all three engine targets and build the debug APK.
- Push to \`master\`: validate all three engine targets and build the debug APK.
- Daily schedule: build and publish a Nightly engine release.
- Manual workflow dispatch: choose Release or Nightly, optionally overriding the upstream Pumpkin ref.

## Build the launcher APK locally

\`\`\`bash
./gradlew assembleDebug
\`\`\`

The APK does not contain Pumpkin-MC. Install the APK, open **Engine Manager**, choose Release or Nightly, and install an engine module before starting a server.

## Android runtime note

Android restricts executing downloaded ELF executables directly from application data directories. The engine is therefore built as a JNI shared library and loaded from app-private storage only after SHA-256 verification and making the file read-only. This design requires testing on real Android devices and should be reviewed against the distribution platform's dynamic-code policies before any store publication.

## Development status

This modular engine path is under active development. CI success confirms cross-compilation and APK packaging; actual server startup, stop, console command handling, and compatibility still require device testing.
