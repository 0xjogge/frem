{ pkgs }:
let
  fakeEmulator = pkgs.writeShellScript "fake-emulator" ''
    [[ "$1" != -accel-check ]] || exit 0
    [[ -z "''${QT_PLUGIN_PATH:-}" ]] || exit 1
    printf '%s\n' "$$" > "$ANDROID_AVD_HOME/emulator.pid"
    trap 'exit 0' TERM
    while true; do sleep 0.1; done
  '';
  fakeAdb = pkgs.writeShellScript "fake-adb" ''
    if [[ "$1" == devices ]]; then
      printf 'List of devices attached\n'
    elif [[ -f "$ANDROID_AVD_HOME/emulator.pid" && "''${NEVER_BOOT:-0}" != 1 ]]; then
      printf '1\r\n'
    fi
  '';
  fakeAvdmanager = pkgs.writeShellScript "fake-avdmanager" ''
    directory="$ANDROID_AVD_HOME/frem-api-36.avd"
    mkdir -p "$directory"
    printf 'image.sysdir.1=/nix/store/old-sdk/system-images/\nhw.ramSize=2048\n' > "$directory/config.ini"
    printf 'path=%s\n' "$directory" > "$ANDROID_AVD_HOME/frem-api-36.ini"
    printf 'existing device data\n' > "$directory/userdata-qemu.img"
  '';
  sdk = pkgs.runCommand "fake-android-sdk" { } ''
    mkdir -p "$out/bin" "$out/libexec/android-sdk/emulator" \
      "$out/libexec/android-sdk/system-images/android-36/google_apis/x86_64"
    ln -s ${fakeEmulator} "$out/libexec/android-sdk/emulator/emulator"
    ln -s ${fakeAdb} "$out/bin/adb"
    ln -s ${fakeAvdmanager} "$out/bin/avdmanager"
  '';
  emulator = import ../emulator.nix {
    inherit pkgs sdk;
    environment = {
      ANDROID_HOME = "${sdk}/libexec/android-sdk";
      ANDROID_SDK_ROOT = "${sdk}/libexec/android-sdk";
      GRADLE_OPTS = "-Dtest-sdk=true";
    };
  };
in
pkgs.runCommand "frem-emulator-check"
  {
    nativeBuildInputs = [
      emulator
      pkgs.bash
      pkgs.coreutils
      pkgs.gnugrep
    ];
  }
  ''
    trap 'cat ./*.log >&2' ERR
    export HOME="$TMPDIR/home"
    export ANDROID_AVD_HOME="$HOME/custom avds"
    export ANDROID_SERIAL=physical-device
    export QT_PLUGIN_PATH=/desktop/qt/plugins

    # The API 36 device must not reuse or overwrite the previous API 35 device.
    legacy="$ANDROID_AVD_HOME/frem-api-35.avd"
    mkdir -p "$legacy"
    printf 'API 35 device data\n' > "$legacy/userdata-qemu.img"

    assert_stopped() {
      if kill -0 "$(cat "$ANDROID_AVD_HOME/emulator.pid")" 2>/dev/null; then
        printf 'Emulator was not stopped\n' >&2
        exit 1
      fi
    }

    frem-emulator --headless -- bash -c '
      set -e
      read -r input
      [[ "$input" == "command input" ]]
      [[ "$ANDROID_SERIAL" == emulator-5554 ]]
      [[ "$QT_PLUGIN_PATH" == /desktop/qt/plugins ]]
      [[ "$GRADLE_OPTS" == *-Dtest-sdk=true ]]
      printf "command output\n"
    ' <<< 'command input' > stdout 2> success.log
    [[ "$(cat stdout)" == 'command output' ]]
    [[ "$(cat "$legacy/userdata-qemu.img")" == 'API 35 device data' ]]
    assert_stopped

    # An existing AVD must retain its data when the SDK path changes.
    directory="$ANDROID_AVD_HOME/frem-api-36.avd"
    printf 'image.sysdir.1=/nix/store/another-sdk/image/\nhw.ramSize=2048\n' > "$directory/config.ini"
    printf 'user changes\n' > "$directory/userdata-qemu.img"
    status=0
    frem-emulator --headless --port 5556 -- bash -c '
      set -e
      [[ "$ANDROID_SERIAL" == emulator-5556 ]]
      exit 7
    ' > stdout 2> failure.log || status=$?
    [[ "$status" == 7 ]]
    [[ "$(cat "$directory/userdata-qemu.img")" == 'user changes' ]]
    grep -qxF 'hw.ramSize=2048' "$directory/config.ini"
    grep -qxF 'image.sysdir.1=system-images/android-36/google_apis/x86_64/' "$directory/config.ini"
    assert_stopped

    status=0
    NEVER_BOOT=1 frem-emulator --headless --boot-timeout 1 -- touch unexpected \
      > stdout 2> timeout.log || status=$?
    [[ "$status" == 1 && ! -e unexpected ]]
    grep -q 'did not finish booting' timeout.log
    assert_stopped

    # SIGTERM must clean up both the emulator and a running command.
    frem-emulator --headless -- bash -c '
      set -e
      printf "%s\n" "$$" > "$ANDROID_AVD_HOME/command.pid"
      sleep 60
    ' > stdout 2> signal.log &
    launcher=$!
    for (( attempt=0; attempt<100; attempt++ )); do
      [[ ! -f "$ANDROID_AVD_HOME/command.pid" ]] || break
      sleep 0.1
    done
    [[ -f "$ANDROID_AVD_HOME/command.pid" ]]
    kill -TERM "$launcher"
    status=0
    wait "$launcher" || status=$?
    [[ "$status" == 143 ]]
    assert_stopped
    ! kill -0 "$(cat "$ANDROID_AVD_HOME/command.pid")" 2>/dev/null

    # Never recreate an incomplete device over existing data.
    rm "$directory/config.ini"
    status=0
    frem-emulator --headless -- true > stdout 2> incomplete.log || status=$?
    [[ "$status" == 1 && ! -f "$directory/config.ini" ]]
    grep -q 'Incomplete AVD' incomplete.log
    [[ "$(cat "$directory/userdata-qemu.img")" == 'user changes' ]]

    touch "$out"
  ''
