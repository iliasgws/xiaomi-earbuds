# Buds

A native Android controller for **global Redmi Buds 6 Pro**, built with Jetpack
Compose **Material 3** and wallpaper-derived **Material You** colors.

## Features

- Direct paired-device Bluetooth connection and mutual RCSP authentication.
- Live left/right/case battery readings and charging indicators.
- ANC with 20 strength values, transparency with three settings, and Off.
- Adaptive ANC, personalized ANC, and adaptive sound controls.
- Balanced, bass, treble, voice, and custom EQ; ten-band custom-EQ editor.
- Separate left/right single, double, triple, hold, and swipe assignments.
- Background connected-device service with ongoing battery/mode status.
- **Android Live Updates** promotion request on Android 16/API 36+, including
  a status chip and progress-style notification where the OS allows promotion.
  Older versions get a standard ongoing notification.
- No root, Xiaomi account, vendor SDK, or internet access required by the app.

The controller profile is for **VID `2717`, PID `509E`**, validated against
firmware **1.3.9.9**. A different device is rejected before any setting write.

## Build

Requirements: JDK 17+ (tested with 21), Android SDK Platform 36, and an SDK path
in `ANDROID_HOME` or local `local.properties`:

```properties
sdk.dir=/path/to/android-sdk
```

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The Gradle wrapper version and distribution checksum are pinned. Dependencies
are pinned as well; the first build needs access to Google/Maven repositories.

Debug APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install it with Android Studio, your phone's package installer, or:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For a phone on the same LAN, you can serve only the APK (no directory browsing):

```bash
python3 scripts/serve_apk.py --bind YOUR_COMPUTER_LAN_IP
# Open http://YOUR_COMPUTER_LAN_IP:8765/buds.apk on the phone.
```

## Connect

1. Pair the Redmi Buds 6 Pro in Android's Bluetooth settings.
2. Close the Xiaomi Earbuds app's control session; the earbuds can refuse a
   second simultaneous RFCOMM control connection.
3. Open **Buds**, allow Nearby devices and notification access, select your
   paired earbuds on the **Device** tab, and tap **Connect earbuds**.
4. Use **Sound** for listening controls and **Gestures** for per-ear assignments.
5. On Android 16+, **Live Update settings** opens the system's promotion setting.
   Notification promotion and its presentation remain the OS/OEM's decision.

The foreground service polls device/configuration state every 15 seconds.
Setting writes are serialized and followed by device readback. A timeout closes
the session rather than letting a late response satisfy a subsequent command.
The notification's **Disconnect** action closes the Bluetooth socket/service.

Custom-EQ editing is explicit: moving sliders changes only the local draft;
**Save custom EQ** sends the recovered save payload and checks the returned bands.

## Verification

- The authentication port passes **516 vectors from actual ARM64 instruction
  replay**, including varied challenges and seeds.
- It also matches **both directions of a successful live handshake** with the
  global Redmi Buds 6 Pro.
- Packet fixtures come from actual app exchanges for mode, strength, EQ and
  gesture controls; tests also cover fragmented/batched frames, malformed input,
  unrelated response sequences, auth failure, timeout cleanup and device guards.
- Device state and the custom-EQ band frequencies were read from the connected
  device. See [docs/PROTOCOL.md](docs/PROTOCOL.md) for the tested commands and limits.

**Current build verification:** unit tests and Android lint/build pass. Installation
and UI/notification behavior of this new app on the physical phone are awaiting
reconnection: the phone left USB/ADB before installation. Successful official-app
exchanges are protocol evidence, not a claim that this new APK has been exercised
on the hardware yet.

## Layout

```text
app/src/main/java/dev/earbuds/buds/
  protocol/       Frame codec, auth calculation, session and typed device data
  connection/     Android RFCOMM transport, foreground service and Live Updates
  ui/             Material 3 screens and controls
app/src/test/     Original-code vectors and captured-packet/session tests
docs/            Portable protocol reference and verification boundary
```

Local reverse-engineering artifacts, phone databases/logs, vendor APKs/libraries,
machine paths and signing keys are excluded from Git. The committed fixtures
contain only selected protocol bytes and synthetic/native-replay vectors.
