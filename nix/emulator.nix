{
  pkgs,
  sdk,
  environment,
}:
pkgs.writeShellApplication {
  name = "frem-emulator";
  meta.description = "Run Frem's Android emulator interactively or around a command";
  runtimeInputs = [
    pkgs.bash
    pkgs.coreutils
    pkgs.gnugrep
    pkgs.gnused
    pkgs.util-linux
    pkgs.jdk21
    sdk
  ];
  text = ''
    usage() {
      cat <<'HELP'
    Usage: frem-emulator [OPTIONS] [-- COMMAND [ARG...]]

    Run Frem's API 36 Google APIs x86_64 Pixel 6 emulator.
    With COMMAND, wait for boot, run it with ANDROID_SERIAL set, then stop the
    emulator and return the command's exit status. Command stdout stays clean;
    emulator output and progress go to stderr.

    Options:
      --headless              Run without a window or audio (software graphics)
      --gpu MODE              Override graphics mode (default: host, or swiftshader headless)
      --port PORT             Even console port from 5554 to 5584 (default: 5554)
      --boot-timeout SECONDS  Maximum boot wait (default: 300)
      -h, --help              Show this help

    Examples:
      frem-emulator
      frem-emulator --headless -- adb shell getprop ro.build.version.sdk
      nix run .#emulator -- --headless -- adb shell getprop ro.build.version.sdk

    Device data is reused in ANDROID_AVD_HOME (default: ~/.android/avd).
    Ctrl+C or SIGTERM stops the emulator and any command started by this tool.
    HELP
    }

    die() { printf 'frem-emulator: %s\n' "$*" >&2; exit 1; }
    argument_error() { printf 'frem-emulator: %s\nTry --help for usage.\n' "$*" >&2; exit 2; }

    headless=false
    gpu=""
    port=5554
    boot_timeout=300
    while (( $# )); do
      case "$1" in
        -h|--help) usage; exit 0 ;;
        --headless) headless=true; shift ;;
        --gpu|--port|--boot-timeout)
          (( $# >= 2 )) || argument_error "$1 requires a value"
          case "$1" in
            --gpu) gpu=$2 ;;
            --port) port=$2 ;;
            --boot-timeout) boot_timeout=$2 ;;
          esac
          shift 2
          ;;
        --) shift; (( $# )) || argument_error "-- requires a command"; break ;;
        *) argument_error "Unknown option: $1" ;;
      esac
    done
    [[ "$port" =~ ^[0-9]{4}$ ]] || argument_error "--port must be an even number from 5554 to 5584"
    port=$((10#$port))
    (( port >= 5554 && port <= 5584 && port % 2 == 0 )) || argument_error "--port must be an even number from 5554 to 5584"
    [[ "$boot_timeout" =~ ^[0-9]{1,6}$ ]] || argument_error "--boot-timeout must be a positive number of seconds"
    boot_timeout=$((10#$boot_timeout))
    (( boot_timeout > 0 )) || argument_error "--boot-timeout must be positive"

    ${pkgs.lib.concatStringsSep "\n" (
      pkgs.lib.mapAttrsToList (name: value: "export ${name}=${pkgs.lib.escapeShellArg value}") (
        builtins.removeAttrs environment [ "GRADLE_OPTS" ]
      )
    )}
    export GRADLE_OPTS="''${GRADLE_OPTS:-} "${pkgs.lib.escapeShellArg environment.GRADLE_OPTS}
    ANDROID_AVD_HOME="$(realpath -m -- "''${ANDROID_AVD_HOME:-''${ANDROID_EMULATOR_HOME:-''${ANDROID_USER_HOME:-$HOME/.android}}/avd}")"
    export ANDROID_AVD_HOME
    export ANDROID_SERIAL="emulator-$port"
    avd_name=frem-api-36
    image_path=system-images/android-36/google_apis/x86_64/
    avd_directory="$ANDROID_AVD_HOME/$avd_name.avd"
    emulator="$ANDROID_HOME/emulator/emulator"
    adb=${sdk}/bin/adb
    [[ -d "$ANDROID_HOME/$image_path" ]] || die "API 36 system image is missing from $ANDROID_HOME"
    timeout 15 "$emulator" -accel-check >&2 || die "Enable hardware virtualization and read/write access to /dev/kvm"

    devices=$(timeout 15 "$adb" devices)
    while read -r serial _; do
      [[ "$serial" != "$ANDROID_SERIAL" ]] || die "$ANDROID_SERIAL already exists; use another --port or use the running device"
    done <<< "$devices"
    mkdir -p -- "$ANDROID_AVD_HOME"
    exec {avd_lock}>"$ANDROID_AVD_HOME/$avd_name.lock"
    flock -n "$avd_lock" || die "$avd_name is already in use in $ANDROID_AVD_HOME"

    config="$avd_directory/config.ini"
    record="$ANDROID_AVD_HOME/$avd_name.ini"
    if [[ ! -e "$config" && ! -e "$record" ]]; then
      (
        exec {avd_lock}>&-
        timeout 60 ${sdk}/bin/avdmanager create avd \
          --name "$avd_name" --package 'system-images;android-36;google_apis;x86_64' \
          --device pixel_6 --path "$avd_directory" <<< no >&2
      )
    fi
    [[ -f "$config" && -f "$record" ]] || die "Incomplete AVD at $avd_directory; inspect it with avdmanager list avd"
    # Relative image paths keep existing device data usable after Nix store updates.
    if ! grep -qxF "image.sysdir.1=$image_path" "$config"; then
      sed -i '/^[[:space:]]*image\.sysdir\.1[[:space:]]*=/d' "$config"
      printf 'image.sysdir.1=%s\n' "$image_path" >> "$config"
    fi

    emulator_pid=""
    command_pid=""
    stop_group() {
      local pid=$1
      kill -TERM -- "-$pid" 2>/dev/null || true
      for (( attempt=0; attempt<100; attempt++ )); do
        kill -0 -- "-$pid" 2>/dev/null || break
        sleep 0.1
      done
      kill -KILL -- "-$pid" 2>/dev/null || true
      wait "$pid" 2>/dev/null || true
    }
    cleanup() {
      local status=$?
      trap - EXIT INT TERM HUP
      [[ -z "$command_pid" ]] || stop_group "$command_pid"
      [[ -z "$emulator_pid" ]] || stop_group "$emulator_pid"
      exit "$status"
    }
    trap cleanup EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM
    trap 'exit 129' HUP

    options=(-avd "$avd_name" -port "$port" -accel on -no-snapshot -no-boot-anim)
    if "$headless"; then
      options+=(-no-window -no-audio -gpu "''${gpu:-swiftshader}")
    else
      options+=(-gpu "''${gpu:-host}")
    fi
    printf 'Starting %s (%s); press Ctrl+C to stop.\n' "$avd_name" "$ANDROID_SERIAL" >&2
    # Keep the desktop's Qt environment out of the emulator's bundled Qt only.
    setsid --wait env -u QT_PLUGIN_PATH -u QT_QPA_PLATFORM_PLUGIN_PATH \
      -u QT_QPA_PLATFORMTHEME -u QT_STYLE_OVERRIDE -u QML_IMPORT_PATH -u QML2_IMPORT_PATH \
      QT_QPA_PLATFORM="''${QT_QPA_PLATFORM:-xcb}" "$emulator" "''${options[@]}" {avd_lock}>&- >&2 &
    emulator_pid=$!

    deadline=$((SECONDS + boot_timeout))
    while true; do
      if ! kill -0 "$emulator_pid" 2>/dev/null; then
        status=0
        wait "$emulator_pid" || status=$?
        die "Emulator exited before booting (status $status)"
      fi
      remaining=$((deadline - SECONDS))
      (( remaining > 0 )) || die "Emulator did not finish booting within $boot_timeout seconds"
      (( remaining <= 5 )) || remaining=5
      boot=$(timeout "$remaining" "$adb" -s "$ANDROID_SERIAL" shell getprop sys.boot_completed {avd_lock}>&- 2>/dev/null || true)
      [[ "''${boot//$'\r'/}" != 1 ]] || break
      sleep 1
    done
    printf 'Ready. ANDROID_SERIAL=%s\n' "$ANDROID_SERIAL" >&2

    if (( $# )); then
      setsid --wait "$@" {avd_lock}>&- <&0 &
      command_pid=$!
      status=0
      wait "$command_pid" || status=$?
      exit "$status"
    fi
    wait "$emulator_pid"
  '';
}
