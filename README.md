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
- **Lights:** adds Nanlite lights to its own Bluetooth Mesh network (after a Bluetooth
  reset on the light), then controls brightness, CCT, G/M and colour (HSI) for each,
  with online/offline status.

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
- Knob changes on the light aren't reported back.
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
