# LightSet

Android app for matching interview lights to the ambient light. Point the phone at
a white or grey card, save the ambient as a reference, and LightSet adjusts Nanlite
lights over Bluetooth until their colour matches it.

## What it does

- **Meter:** colour temp and tint (Duv) of the light on a card, from the phone's RAW
  sensor data. Exposure is metered on the sampled box and stepped down if it clips.
- **Calibration:** one tap steps a trusted light (e.g. a Forza 60C) through
  2700–6500 K and corrects the phone's colour matrices from what it reads. Manual
  calibration is also available.
- **Match:** sets a light's CCT and G/M, reads it on the card with the ambient
  subtracted (the light blinks off once to read the ambient), and adjusts until it's
  within 5 mired and 1.5 tint. Brightness is left where you set it. If a light can't
  reach the tint (no G/M, or out of green at warm CCTs), it matches CCT and suggests a gel.
- **IM150:** the Illuminati IM150 colour meter can be used instead of the phone (Phone /
  IM150 at the top of Match) for readings, references and Match. When it's connected,
  calibration uses its reading of the light as the target instead of the light's setting.
  The meter takes one connection at a time, so close the Illuminati app first.
- **Exposure:** while the IM150 is connected, a bar at the bottom of the Lights tab (so
  it's in view while setting key and fill) works out aperture, ISO, shutter angle and ND
  from the meter's lux (or a light value set by hand), for a chosen frame rate. Lock the
  settings to keep; if they can't all be met, it says how much light to add or cut.
- **Lights:** adds Nanlite lights to its own Bluetooth Mesh network (after a Bluetooth
  reset on the light), then controls brightness, CCT, G/M and colour (HSI) for each,
  with online/offline status. Each light can be switched off and back on to its
  previous settings, here and on the Match tab.

## How the lights are controlled

Nanlite lights with Feasycom Bluetooth modules are Bluetooth Mesh nodes. They take
the Nanlite "app protocol" (see
[vmedea's notes](https://gist.github.com/vmedea/434694c11092261fcac401b7a4b9a741))
inside vendor messages: opcode `0xC1 1111` plus an 8-byte short packet, or a
segmented extended packet for `set_mode` (CCT ⇄ HSI). They use the Mesh
specification's sample app key at index 0. Some firmware installs it on joining;
for the rest LightSet adds it and binds the vendor model with the light's device key.

Quirks found so far:
- Any write makes the light re-apply brightness, CCT and G/M from values its module
  stores, and those reset on reconnect. So the app owns each light's full state and
  sends all of it every time, with G/M last.
- Knob changes on the light aren't reported back, and reads return the module's
  stored values, not the knobs'. A vendor read also makes the light re-apply those
  stored values, so the app never reads a light just to check it's there: it sends a
  Config message with the light's device key instead, which leaves knob settings
  alone. Lights added by other apps (no device key) still get a read, so add every
  light through LightSet.
- Some lights (e.g. PavoTube) act on writes but don't answer reads, so status is
  checked with a configuration request instead.

## Build

Tooling lives outside the repo in `F:\AndroidTools` (JDK 17, Android SDK, Gradle).

```bash
JAVA_HOME=/f/AndroidTools/jdk17 ./gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

Install on a USB-connected phone with USB debugging enabled:

```bash
/f/AndroidTools/sdk/platform-tools/adb.exe install -r app/build/outputs/apk/debug/app-debug.apk
```

Note: the mesh network key is stored on the phone and seeded in `MeshStore`; keep this
repository private.
