# Buds

A native Android controller for **global Redmi Buds 6 Pro**, built with Jetpack
Compose **Material 3** and wallpaper-derived **Material You** colors.
Floating Liquid Glass chrome adds backdrop blur, edge refraction, highlights,
and a responsive, draggable lens on the Sound/Gestures/Device navigation bar.
This is an Android approximation using [Kyant Backdrop](https://github.com/Kyant0/AndroidLiquidGlass),
not Apple's proprietary renderer or a static glass texture.

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

Backdrop 1.0.0 and ContinuousCapsule 2.1.1 resolve from Maven Central. No
local library build or Maven Local repository is needed. These releases match
the app's Kotlin 2.2.21 toolchain.

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The Gradle wrapper version and distribution checksum are pinned. Dependencies
are pinned as well; the first build needs access to Google/Maven repositories.

Debug APK:

```text
app/build/outputs/apk/liquid-glass/Buds-Liquid-Glass.apk
```

Install it with Android Studio, your phone's package installer, or:

```bash
adb install -r app/build/outputs/apk/liquid-glass/Buds-Liquid-Glass.apk
```

For a phone on the same LAN, you can serve only the APK (no directory browsing):

```bash
python3 scripts/serve_apk.py --bind YOUR_COMPUTER_LAN_IP
# Open http://YOUR_COMPUTER_LAN_IP:8765/Buds-Liquid-Glass.apk on the phone.
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

## Glass rendering

The header and functional tab bar share one live backdrop, outside its
subtree, over a wallpaper-colored gradient and scrolling content. Cards, battery
tiles, buttons, selectors, sliders, toggles, and expanding menus now use glass.
Insets keep controls clear of system bars;
content can scroll behind the floating glass.

- Android 13+: shader refraction, dispersion, blur, and highlights.
- Android 12: frosted blur and highlights.
- Android 8–11 (the app's supported older versions): translucent scrim and rim.

The tab bar uses Kyant's actual `LiquidBottomTabs` / `LiquidBottomTab` catalog
examples: continuous capsule geometry, refracted backdrop plus a second sampled
icon layer, draggable selection lens, velocity-dependent deformation, animated
dispersion, inner shadow, and touch-position highlights. The header uses the same
optical engine with a light surface wash. No neon border or opaque modern-device
panel is used to stand in for glass. Attribution, pinned source revision, and
local adaptations are in [third_party/kyant-backdrop](third_party/kyant-backdrop).

Appearance, contrast over scrolling content, drag/tap navigation, and accessibility
still need physical-device testing on each rendering tier.

Controls use a separate sibling background provider to avoid sampling themselves;
the header/tab provider records the resulting screen. Menus expand inline to keep
their backdrop coordinates in the same window. Firmware confirmation uses a local
glass scene in its dialog window, not a cross-window backdrop capture. The sliders
retain discrete values, command-on-release for ANC/transparency, and explicit
Save custom EQ behavior. See [the implementation checklist](docs/LIQUID_GLASS_TODO.md).
