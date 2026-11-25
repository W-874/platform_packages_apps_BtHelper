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

### Compatibility telemetry

The service is started by the existing bonded Bluetooth device identification
path. The Adapter's More settings entry does not imply a successful AACP session.
Apple manufacturer data with `07 13` and a 21-byte payload provides individual
battery percentages and charging flags. It is accepted only for the active bonded
headset's public address or its Android-resolved public identity. The standard
`07 19` path continues to require the device's IRK; a short advertisement cannot
authorize it. An advertised known model selects artwork, not authentication or
control support. AACP battery reports can publish without a model; missing or
invalid component readings are not converted to zero or an aggregate percentage.
For unrecognized earbuds, BtHelper leaves icon selection to Android, removing
its own previously published icons without clearing third-party artwork or
left/right/case battery metadata.

Artwork uses the first known model in this order: IRK-verified BLE, the primary
SDP Device Identification record (`0x1200`), AACP device information (`0x001D`),
GATT Device Information Service PnP ID (`0x180A` / `0x2A50`), then identity-bound
short BLE. Higher-priority evidence replaces lower-priority evidence, regardless
of arrival order. SDP is queried once per session for a bonded headset declaring
the AirPods service UUID, independently of the AACP socket. If SDP does not
identify a model, GATT is read only on an existing LE link or when Android
already knows the device exposes DIS. PnP IDs require the Bluetooth SIG vendor
namespace and a known Apple product ID. These reports select artwork, not
authenticity, battery readings, or advanced controls.

On a bonded headset exposing both the AirPods and Airoha service UUIDs, a valid
split AACP battery report identifies compatible earbuds, not their retail SKU.
An identity-bound `07 13 01 14 20` advertisement identifies the short-format
Pro 2 variant ([CAPod factory](https://github.com/d4rken-org/capod/blob/d7817d713691fa7601256eef6bdbdcf2cf0e8426/app/src/main/java/eu/darken/capod/pods/core/apple/ble/devices/misc/FakeAirPodsPro2.kt)).

More settings observes the current headset session. Valid reports on its secure
AACP socket can enable wear detection, conversation settings or personalized
volume separately. Listening modes are selectable only after the accessory has
reported those modes, and the displayed mode follows its acknowledgement.
Disconnecting discards socket-derived capabilities. To see the selected model,
source and session state without logging addresses or keys, use
`adb shell dumpsys activity service com.android.bluetooth.bthelper/.pods.PodsService`.

The SDP product IDs and AACP framing were cross-checked against MagicPodsCore's
[model table](https://github.com/steam3d/MagicPodsCore/blob/04af4bd/src/sdk/aap/enums/AapModelIds.h)
and [device information parser](https://github.com/steam3d/MagicPodsCore/blob/04af4bd/src/sdk/aap/watchers/AapFwInfoWatcher.cpp).

The short format doesn't establish wear or lid state and doesn't enable advanced
controls. No clone-specific command transport is implemented. The short-format
layout was cross-checked against [librepods_ble's analysis](https://github.com/tree-sakura/librepods_ble/blob/8efbb74058763c113e570052aad5a645dec0c6eb/REVERSE_ANALYSIS.md);
[LightPods](https://github.com/uzairdeveloper223/LightPods/tree/2684bbb5e10b2ff647f9f4b4b4256d23f7b580fe)
provided a reference for the separate nibble-based format. No HyperOS code or
assets were imported.

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
