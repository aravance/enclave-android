# Enclave Power

Android TV app that turns an Enclave CineHome (Smart Center) on when the
device wakes or when a chosen app opens. Built for an NVIDIA Shield, where the
Enclave does not respond to HDMI-CEC.

## Build and install

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Setup

1. Open **Enclave Power** and press **Pair with Enclave**, then accept the prompt.
2. Enable the service: Settings > Device Preferences > Accessibility > Enclave Power.
3. Pick the apps that should turn the speakers on.

The Enclave's Bluetooth address is set in `Prefs.kt` (`DEFAULT_ADDRESS`).

## How it works

- Control is Bluetooth Classic serial (SPP), reverse-engineered from the
  official app. The protocol is documented in `EnclaveClient.kt`.
- An accessibility service watches for wake and foreground app changes.
- Android only opens the serial channel to a paired device, and pairing makes
  it route audio to the Enclave over Bluetooth. `A2dpGuard.kt` drops that audio
  link so sound stays on HDMI. It uses a hidden API, tested on Android 11.

## License

Copyright 2026 Alex Avance. Licensed under the [Apache License, Version 2.0](LICENSE.txt).
