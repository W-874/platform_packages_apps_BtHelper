# Bluetooth Devices Helper

Bluetooth Devices Helper, also known as BtHelper, is an application<br>
that enhances the functionality of Bluetooth devices by providing features<br>
such as battery level display and device icons on Android Open Source Project (AOSP).<br>
These features are not currently supported by AOSP.<br>

## Project Status

Only Apple AirPods series are supported by this project.<br>

## uwuAOSP Android 17

The local manifest tracks the downstream `uwu-17.0` branch, which carries the
Android 17 adaptation. `vendor/uwu/config/common.mk` includes `BtHelper`;
Soong also installs the required `BtHelperAdapter` and permission grants on
`system_ext`. Use the normal product build and OTA packaging flow.

Both APKs use the platform certificate and separate application UIDs. AACP
uses the platform's hidden BR/EDR L2CAP factory via one scoped method lookup
because the factory is absent from the Bluetooth compile stubs. No per-package
hidden API exemption is needed for platform-signed apps. `l2c_should_skip_ertm`
in this Bluetooth tree already recognizes the AirPods SDP UUID, so do not apply
the upstream generic L2CAP fallback patch. Settings and SystemUI consume the
standard Bluetooth metadata and existing slice contract.

BtHelper requests `LOCAL_MAC_ADDRESS` for AACP cross-device audio routing.
It reads the adapter address only when routing needs it; a missing or redacted
address does not prevent service startup or device metadata updates.

Audio profile connection events start the service but do not trigger routing
decisions. The classic ACL disconnect ends the session. BtHelper does not open
a second ATT socket during the AACP handshake; no active feature uses it.

## Acknowledgements

We would like to express our gratitude to the following projects:
* [OpenPods](https://github.com/adolfintel/OpenPods): Code base of this project.
* [CAPod](https://github.com/d4rken-org/capod): Media play/pause, OnePod mode, and many other functionalities.
* [xingrz](https://github.com/xingrz): Android System Settings integration.
* [LibrePods](https://github.com/kavishdevar/librepods): Advanced AirPods support with in-depth researches.

## Trademark

<b>Trademark Notice:</b><br>
The term "AirPods" is a registered trademark of Apple Inc.<br>

BtHelper packages the upstream AirPods artwork and the gesture feedback sounds
used at runtime from `res-apple`. The artwork belongs to Apple Inc.; the
project's GPL does not grant redistribution rights to these third-party assets.

## License

Bluetooth Devices Helper is licensed under the [GNU General Public License v3.0 or later](LICENSE).
<br>
Copyright (C) 2023-2025 TheParasiteProject
