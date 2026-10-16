<p align="center">
  <img src="docs/images/banner.svg" alt="Frem — your health, on your phone" width="960" />
</p>

<p align="center">
  <a href="https://github.com/0xjogge/frem/releases/latest"><img alt="Download APK" src="https://img.shields.io/badge/Download-APK-234D39?style=for-the-badge&logo=android&logoColor=DBF0AA" /></a>
  <a href="https://obtainium.imranr.dev/"><img alt="Updates with Obtainium" src="https://img.shields.io/badge/Updates-Obtainium-234D39?style=for-the-badge" /></a>
  <a href="https://github.com/0xjogge/frem/actions/workflows/ci.yml"><img alt="CI" src="https://img.shields.io/github/actions/workflow/status/0xjogge/frem/ci.yml?branch=main&style=for-the-badge&label=CI" /></a>
</p>

**A native, local-first Android health companion.** Built with Kotlin, Jetpack Compose, and Material 3 Expressive. No account, ads, analytics, or backend.

## Install

**Android 10 or newer.** Download the `frem-…-release.apk` from [**GitHub Releases →**](https://github.com/0xjogge/frem/releases/latest), then open it to install.

For update checks with [Obtainium](https://obtainium.imranr.dev/), add this GitHub source:

```text
https://github.com/0xjogge/frem
```

Public downloads become available when the repository is public and the first release is published.

## Everyday health, in one place

- **Move:** steps, distance, activity history, daily goals, and streaks.
- **Check in:** weight, body measurements, hydration, and optional reminders.
- **Connect:** import sleep, heart rate, workouts, and more through Health Connect.
- **Make it yours:** light/dark themes, wallpaper colors, metric/imperial units, and CSV export.

Your records stay on your phone. You choose Health Connect permissions and what to export; missing readings are never presented as zero.

<p align="center">
  <img src="docs/images/frem-light.png" alt="Frem Today screen in light mode" width="240" />
  <img src="docs/images/frem-dark.png" alt="Frem Today screen in dark mode" width="240" />
</p>

## Develop

The pinned [Nix](https://nixos.org/download/) environment includes JDK 21, Android SDK 37, Node.js, Python, and ADB:

```sh
nix develop
npm ci --ignore-scripts
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

Enable USB debugging and connect a device for `installDebug`. Without Nix, provide JDK 21, Android SDK 37.0, build tools 36.0.0, and Node.js.

### Check and format

```sh
npm run format:check
./gradlew spotlessCheck :app:lintDebug :app:testDebugUnitTest
```

Fix formatting with `npm run format` and `./gradlew spotlessApply`. Pull requests also build debug/release APKs and run the device-test suite on an emulator. [View CI →](https://github.com/0xjogge/frem/actions/workflows/ci.yml)

## Release

Push a tag such as **`0.1-release`** to build a signed APK and publish a release with grouped changelog notes. The first number is for features; the second is for patches.

[**Release guide →**](RELEASING.md) · [**Changelog →**](https://github.com/0xjogge/frem/releases)

## License

Frem is licensed under the [GNU General Public License v3.0](LICENSE) (`GPL-3.0-only`).
