# Health measurements

## Sources and scope

Frem uses a hybrid approach: Health Connect supplies all-day imports, while native sensors add measurements during activities you explicitly record. Granting a Health Connect permission does not cause a watch or another app to produce that record type.

| Measurement         | Health Connect                                                             | Native recording                                                     |
| ------------------- | -------------------------------------------------------------------------- | -------------------------------------------------------------------- |
| Active minutes      | Activity-intensity duration, when the feature and a producer are available | Estimated moderate/vigorous time from sustained pedestrian cadence   |
| Cadence             | Step-cadence records                                                       | Hardware step detector and physical activity permission              |
| Floors              | Floors-climbed records                                                     | Barometric ascent plus detected steps; 3 m per floor equivalent      |
| Elevation gained    | Elevation-gained records                                                   | Barometer preferred; accuracy-filtered GPS fallback                  |
| Elevation lost      | No corresponding elevation-gained aggregate                                | Barometer/GPS descent during Frem recordings                         |
| Environmental noise | No supported record type                                                   | User-started, calibrated microphone sessions                         |
| Headphone exposure  | No supported record type                                                   | Requires a device/provider interface not currently available in Frem |

Source selection is per metric and day. An imported zero wins over a native subtotal. Frem does not add a recording to an overlapping all-day total. Recording subtotals remain available independently in activity details and CSV export. Missing observations remain unknown; cadence averages use recorded moving time rather than time since midnight.

See [Activity recording](activity-recording.md) for collection rules and thresholds.

## Environmental-noise sessions

Open **Health → Hearing exposure → Measure & view history**. Tap **Start measurement**, grant microphone access, and finish using the screen or the ongoing notification. Sessions continue with the screen locked. Their numerical summaries are saved locally; PCM audio is processed in memory and never saved or uploaded.

The input is the built-in microphone at 48 kHz mono. Frem prefers Android's unprocessed audio source and otherwise uses voice-recognition input. A-weighting uses cascaded digital filter sections normalized at 1 kHz. LAeq averages energy, not decibel values. The first second warms the filter; muted, all-zero, or clipped blocks are excluded and reported as unmeasured time.

### Calibration

1. Place the phone beside a calibrated A-weighted sound-level meter in a steady sound field. Keep the microphone uncovered.
2. Start a session and collect at least ten uninterrupted valid seconds.
3. Enter the reference meter's equivalent level for that same interval and save calibration.
4. Finish and start a new session. The new session uses that calibration if the microphone configuration matches.

Without calibration, levels are digital **dBFS(A)**, not sound pressure. SPL and dose remain empty. Calibration is tied to the phone, audio source, sample rate, and routed microphone identity. Input changes interrupt a session; historical sessions retain their original calibration snapshot. A single-point calibration does not compensate for every microphone's frequency response, positioning, wind, or audio processing, so calibrated results are labelled estimates.

Calibrated sessions display estimated LAeq, the maximum one-second equivalent level, measured/unmeasured duration, and a measured-session dose. The dose reference is **85 dB(A) for 8 hours with a 3 dB exchange rate**. It only includes valid captured intervals; it does not imply all-day monitoring or fill gaps with silence. CSV export includes the input, calibration offset, reference, and coverage.

### Headphone exposure

Ambient microphone measurements do not measure sound at the ear inside headphones. Volume percentage and Android's volume attenuation are insufficient to derive that sound pressure. Android's accumulated sound-dose getter is hidden and requires privileged audio access. No model-specific headphone import has been implemented; a concrete public provider API or supported export is needed before adding one.

## Accuracy and battery decisions

- Hardware step detection is used instead of a continuously sampled accelerometer implementation.
- Pressure is sampled at approximately 1 Hz and callbacks can batch for five seconds. Pressure/step batches are correlated by their original timestamps.
- The activity's existing GPS stream is reused. These metrics do not start all-day GPS tracking.
- Native activity summaries and noise summaries are checkpointed in batches, with explicit completion persistence.
- Noise capture runs only after Start, uses a microphone foreground service, and processes one-second blocks. It has a higher collection cost than passive health imports.
- Battery percentages are not inferred from the emulator. Physical battery and sensor accuracy validation is still required on the Pixel 10 Pro.

## Validation

The standard Nix emulator command runs instrumented checks with injected step times, pressure batches, GPS observations, and synthetic PCM. The fake sources use the production processing, foreground services, Room storage, and UI; they are in-process test seams, not exported Android components. Headless audio is disabled, so deterministic noise tests do not depend on a host microphone.

```sh
nix develop --command frem-emulator --headless -- bash -c '
  ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest &&
  python3 scripts/run-device-tests.py app/build/outputs/apk
'
```

Unit checks cover cadence/intensity, stopped and invalid observations, midnight/DST splits, pressure drift/spikes/gaps, source precedence, A-weighting frequency response, energy averaging, dose exchange, and CSV source labels. Instrumented checks cover coordinate chart taps, independently delivered step/pressure batches, pause flushing, native-history display, microphone calibration, screen-off capture, repeated sessions, and upgrades from every previous Room schema.

For the physical Pixel pass:

- Count steps over repeated steady walks/runs and compare moving cadence; include stops and changes of pace.
- Repeat a staircase with known height, flat walking, and elevator-only travel. Compare floors and gain/loss separately.
- Repeat a route in both directions, including a stationary period, pause/resume, and poor GPS reception.
- Calibrate beside a reference sound-level meter, then compare multiple levels/frequency mixtures. Test input/microphone permission changes and clipping.
- Compare matched screen-off outings before/after native sensors, and a fixed-length noise session against idle. Record Android version, screen state, session length, battery/energy use, and sensor availability. Do not treat emulator power statistics as physical measurements.

## Reference approaches

- [Google Fit activity scoring](https://support.google.com/fit/answer/7619539?hl=en): cadence-based phone intensity benchmarks.
- [Fitbit daily activity](https://support.google.com/fitbit/answer/14237111?hl=en): pressure plus steps for approximately 3 m floor equivalents.
- [Fitbit Active Zone Minutes](https://support.google.com/fitbit/answer/14236509?hl=en): distinguish actual duration and vigorous weighting.
- [Strava elevation](https://support.strava.com/hc/en-us/articles/115001294564-Elevation-on-Strava): source-dependent filtering and noise suppression.
- [NoiseCapture calibration](https://noise-planet.org/noisecapture_calibration.html): reference-level calibration rather than labelling microphone amplitude as SPL.
- [Apple environmental noise](https://support.apple.com/en-us/102315) and [Sony Safe Listening](https://helpguide.sony.net/mdr/hpc/v1/en/contents/TP1001274112.html): ambient and headphone exposure require different sources.
