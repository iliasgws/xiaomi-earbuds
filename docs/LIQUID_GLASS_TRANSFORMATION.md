# How the Buds Liquid Glass UI transformation was built

## Goal

Transform the Android Jetpack Compose interface into an iOS-inspired Liquid Glass
experience across the whole app—not just translucent cards or a blurred tab bar.
The work covers Sound, Gestures, and Device while preserving their real earbud
commands and navigation.

This is an Android approximation of the visual style, not Apple's proprietary
renderer or an exact implementation of iOS Liquid Glass.

## 1. Use Kyant Backdrop instead of a static glass imitation

The final implementation uses these Maven Central dependencies:

```kotlin
implementation("io.github.kyant0:backdrop:1.0.0")
implementation("io.github.kyant0:capsule:2.1.1")
```

Backdrop supplies live background sampling, blur, vibrancy, lens distortion,
highlights, and depth effects. Capsule supplies continuous rounded geometry.
Version 1.0.0 was selected to match the project's Kotlin 2.2.21 toolchain.

Rather than replacing the library's interaction design with simplified controls,
the real example components were copied from
[Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass), tag
`1.0.0`, commit `eada9a8600142c1bd6a5c5fc143d06f599cdfaef`:

- `LiquidBottomTabs` and `LiquidBottomTab`
- `LiquidButton`
- `LiquidSlider`
- `LiquidToggle`
- `DampedDragAnimation`, `InteractiveHighlight`, and `DragGestureInspector`

These live under `app/src/main/java/com/kyant/backdrop/catalog/`. The upstream
Apache-2.0 license and adaptation notes are in
[`third_party/kyant-backdrop/`](../third_party/kyant-backdrop/).

## 2. Separate the sampled background from the glass

Glass needs something behind it to distort. Sampling a surface that contains
itself would create a rendering feedback problem, so the app uses distinct layers:

1. `GlassControlScene` records a theme-colored gradient into a backdrop layer.
2. Cards and controls sample that sibling background through
   `LocalControlBackdrop`.
3. A separate screen backdrop records the resulting content for the header and
   bottom navigation to sample.
4. The header and navigation remain outside that recorded content subtree.

This means header/tab glass responds to the screen behind it, whereas the main
controls sample the gradient—not every neighboring control or the entire Android
screen. This distinction is intentional and avoids self-sampling.

Dialogs have their own window. `GlassDialog` therefore creates a local glass scene
instead of attempting to sample a backdrop across window owners.

## 3. Build reusable glass surfaces

`app/src/main/java/dev/earbuds/buds/ui/GlassChrome.kt` defines `glassChrome` for
capsule-shaped surfaces and wraps the upstream bottom tabs in `GlassTabs`.
The header combines vibrancy, 4dp blur, lensing with depth, and a translucent
theme-colored surface.

`app/src/main/java/dev/earbuds/buds/ui/GlassControls.kt` provides the reusable
controls used by `BudsApp.kt`:

- **GlassCard:** rounded section cards and battery tiles, with 5dp background blur,
  vibrancy, lensing, and a light surface tint.
- **GlassButton / GlassChoice:** upstream liquid buttons for actions and selected
  options, retaining disabled states and selection semantics.
- **GlassSlider:** liquid sliders with discrete snapping, accessible progress
  actions, and completion callbacks for sending device commands on release.
- **GlassSwitch:** liquid toggles with switch semantics and disabled input handling.
- **GlassPicker:** a title and menu contained in one morphing glass surface.
- **GlassDialog:** glass firmware confirmation in a separate window.
- **GlassSnackbarHost:** glass message/error banners with readable text, optional
  actions, a Dismiss button, and polite accessibility announcements.

The first pass covered the header and tabs. The follow-up replaced cards, battery
tiles, artwork surfaces, buttons, selectors, sliders, switches, menus, dialogs,
and error banners, so the treatment extends throughout the app.

## 4. Make pickers morph, stretch, and spring back

The initial inline dropdown had a separate Close entry. This was replaced with a
single `GlassPicker` surface:

- Tap the title to expand; retap it or press Back to collapse.
- Remove the dropdown triangle and explicit Close entry.
- Animate content size and corner radius with springs as the small title surface
  grows into the menu.
- Use `InteractiveHighlight` to drive press scaling and finger-following offsets.
- Bound the stretch with `tanh` so dragging cannot deform the surface indefinitely.
- Increase lens strength slightly while pressed.
- Apply a small, directional `RenderEffect` blur based on expansion velocity on
  Android 12+. It clears at rest.

That blur is a motion-blur approximation, not temporal frame accumulation. The
effect is deliberately subtle to keep menu text readable. Menu expansion remains
inline in the same Compose render tree rather than using a separate popup window.

## 5. Refine sliders and error presentation

The slider initially exposed a small draggable thumb and a thin tappable track.
The later adaptation adds a full-row pointer target: touching the track can move
the thumb to the finger and horizontal movement changes the value. The gesture
handling distinguishes horizontal dragging from vertical page scrolling.
The upstream lens, animated thumb scaling, and spring behavior remain in use.

Error banners also received the glass treatment. A local `blurRadius` parameter
was added to `LiquidButton`, allowing stronger background frosting specifically
for banner buttons:

- Regular button: **2dp**
- Banner action: **8dp**
- Dismiss: **12dp**

Only the sampled background is blurred; the Dismiss label stays sharp.
The top connecting/busy loading bar was removed at user request, while firmware
update progress was retained.

## 6. Preserve behavior and adapt the upstream components

The visual replacement keeps Sound/Gestures/Device navigation and repository
commands wired to their existing actions. Local adaptations include:

- `rememberUpdatedState` for current callbacks and values inside remembered logic.
- Layout-dependent remember keys where width, direction, or value range matters.
- Explicit button enabled states and disabled pointer handling for controls.
- Slider completion callbacks, discrete values, and explicit custom-EQ saving.
- Selection, switch, progress, and live-region accessibility semantics.
- Higher surface opacity on older devices as a readability fallback.

During phone testing, noise writes were observed to apply even when config 11
reported a different value. The session was changed to accept successful write
acknowledgments rather than display a false readback-mismatch error. It also keeps
the last acknowledged noise mode/level for the UI during that session. This is a
protocol workaround, not part of glass rendering; it can also mask later changes
made outside the app until the session is reset.

Debug logging was added for caught command, connection, and firmware failures.
The separate EQ write rejection (`0xF2`, status `1`) was not fixed by the UI work.

## 7. Compatibility and verification

- The app's minimum Android API remains 26.
- Background effect support depends on the OS: blur effects need Android 12+;
  full shader lensing needs Android 13+.
- Older devices use more opaque surfaces; they do not receive identical effects.
- Unit tests, Android lint, and debug assembly were run during development.
- APKs were installed through ADB and screenshots captured on the user's phone.
- The user confirmed the appearance and provided the interaction feedback used
  for subsequent changes.
- Automated ADB taps were blocked by the phone's `INJECT_EVENTS` restriction, so
  a successful build does not mean every interaction was automatically verified.

The implementation checklist is in
[`LIQUID_GLASS_TODO.md`](LIQUID_GLASS_TODO.md).

## 8. Build and distribution

The debug build produces a descriptively named copy alongside AGP's usual output:

```text
app/build/outputs/apk/liquid-glass/Buds-Liquid-Glass.apk
```

`scripts/serve_apk.py` serves that file over the LAN with the same download
filename. The transformation was pushed to GitHub and included in the `v1.0.0`
release. That published APK is debug-signed; calling the GitHub release stable
does not change its signing configuration.

## Main files to read

- `app/build.gradle.kts`: dependencies and named APK copy task.
- `app/src/main/java/dev/earbuds/buds/ui/BudsApp.kt`: screen integration and action wiring.
- `app/src/main/java/dev/earbuds/buds/ui/GlassChrome.kt`: header/artwork glass and tab wrapper.
- `app/src/main/java/dev/earbuds/buds/ui/GlassControls.kt`: surfaces, controls, morphing, and banners.
- `app/src/main/java/com/kyant/backdrop/catalog/`: adapted upstream examples and animation utilities.
- `third_party/kyant-backdrop/`: source attribution, pinned revision, and license.
