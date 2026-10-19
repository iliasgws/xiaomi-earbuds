# Kyant Backdrop catalog components

Vendored from [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass),
tag `1.0.0`, commit `eada9a8600142c1bd6a5c5fc143d06f599cdfaef`.
Apache-2.0; see LICENSE in this directory.

Original sources retain their packages under `app/src/main/java/com/kyant/backdrop/catalog/`:

- `components/LiquidBottomTabs.kt`
- `components/LiquidBottomTab.kt`
- `components/LiquidButton.kt`
- `components/LiquidSlider.kt`
- `components/LiquidToggle.kt`
- `utils/DampedDragAnimation.kt`
- `utils/InteractiveHighlight.kt`
- `utils/DragGestureInspector.kt`

Local adaptations: selection callback stability and layout-dependent captures;
older-device surface contrast; button enabled state and configurable blur radius;
slider completion callbacks
and fresh slider/toggle callback captures. Rendering, continuous capsule geometry, double
backdrop sampling, drag deformation, dispersion, and interactive highlights
come from the upstream example, not a replacement approximation.

The 1.0.0 release matches this app's Kotlin 2.2.21 toolchain. Its Backdrop
and ContinuousCapsule dependencies are pinned Maven Central artifacts.
