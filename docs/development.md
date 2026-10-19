# Development

## Build

Use the pinned [Nix](https://nixos.org/download/) environment:

```sh
nix develop
npm ci --ignore-scripts
./gradlew :app:assembleDebug
```

For a connected phone, enable USB debugging and run `./gradlew :app:installDebug`.

Without Nix, install JDK 21, Android SDK 37.0, build tools 36.0.0, Node.js, and Python 3.

## Emulator

```sh
nix run .#emulator
```

This starts a persistent Android 16 / API 36 Pixel 6. The pinned Google APIs x86_64 image is revision 7 and includes Google Play services 25.26.35, which supports phone distance recording without an update. Linux requires hardware virtualization and read/write access to `/dev/kvm`.

The device is named `frem-api-36`; any existing `frem-api-35` device and its data are preserved.

Wait for **Ready**, then install and open the debug APK from another development shell:

```sh
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start -W -n dk.frem.app/.MainActivity
```

Inside `nix develop`, use `frem-emulator`. Press **Ctrl+C** to stop it. See `frem-emulator --help` for headless mode, software graphics, ports, and boot timeouts. Device data defaults to `~/.android/avd`; override it with `ANDROID_AVD_HOME`.

If ADB reports `device offline`, restart its server from the development shell with `adb kill-server && adb start-server`.

## Checks

```sh
nix flake check
npm run format:check
./gradlew spotlessCheck :app:lintDebug :app:testDebugUnitTest
python3 -m unittest discover -s scripts/tests -v
```

Fix formatting with `npm run format` and `./gradlew spotlessApply`.

## Device tests and automation

Run the device suite with automatic emulator startup and shutdown:

```sh
nix develop --command frem-emulator --headless -- bash -c '
  ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest &&
  python3 scripts/run-device-tests.py app/build/outputs/apk
'
```

Pass any command after `--`. The launcher waits for boot, sets `ANDROID_SERIAL`, returns the command's exit status, and stops the emulator afterward. Command output goes to stdout; emulator diagnostics go to stderr.
