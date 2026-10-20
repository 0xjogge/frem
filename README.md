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

**Android 10 or newer.** Download the APK from [GitHub Releases](https://github.com/0xjogge/frem/releases/latest), then open it to install.

Choose one complete installer:

| Build                   | Filename                                 | Device                                             |
| ----------------------- | ---------------------------------------- | -------------------------------------------------- |
| **ARM64 (recommended)** | `frem-<version>-release-arm64-v8a.apk`   | Most modern Android phones                         |
| ARM32                   | `frem-<version>-release-armeabi-v7a.apk` | Older 32-bit ARM devices                           |
| Intel 64-bit            | `frem-<version>-release-x86_64.apk`      | Intel devices and emulators                        |
| Intel 32-bit            | `frem-<version>-release-x86.apk`         | 32-bit Intel devices                               |
| **Universal**           | `frem-<version>-release.apk`             | All supported architectures; choose this if unsure |

Architecture-specific APKs are smaller because they include native libraries for just that CPU. All builds use the same signing key and support in-place updates with your existing data.

For update checks, add `https://github.com/0xjogge/frem` to [Obtainium](https://obtainium.imranr.dev/). Use the [APK filters](RELEASING.md#obtainium) to select your architecture or the universal build automatically.

## Features

- Steps, distance, activity history, daily goals, and streaks.
- GPS walking, hiking, running, and cycling with live OpenStreetMap maps, screen-off recording, pause/resume, and GPX export.
- Recorded step cadence, estimated active minutes, barometric floor equivalents, and elevation gain/loss, with Health Connect daily imports preferred when present.
- User-started environmental-noise sessions with microphone calibration, local numerical history, and measured-interval exposure estimates.
- Weight, body measurements, hydration, and reminders.
- Sleep, heart rate, and workout imports through Health Connect.
- Light/dark themes, metric/imperial units, and CSV export.

<p align="center">
  <img src="docs/images/frem-light.png" alt="Frem Today screen in light mode" width="240" />
  <img src="docs/images/frem-dark.png" alt="Frem Today screen in dark mode" width="240" />
</p>

## Documentation

- [Development](docs/development.md) — build, run, and test.
- [Activity recording](docs/activity-recording.md) — record outings, understand metrics, and export routes.
- [Health measurements](docs/health-measurements.md) — sensor requirements, noise calibration, source selection, and validation.
- [Releasing](RELEASING.md) — versioning and publishing.
- [Changelog](https://github.com/0xjogge/frem/releases).

## License

Frem is licensed under the [GNU General Public License v3.0](LICENSE) (`GPL-3.0-only`).
