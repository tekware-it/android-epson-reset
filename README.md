# Android Epson Reset

Android port scaffold for Epson waste ink counter maintenance over USB OTG.

## Current scope

- Android 8+ Kotlin project with Jetpack Compose UI
- Epson USB vendor filtering (`0x04B8`)
- USB Host transport using `UsbManager`, `UsbDeviceConnection`, bulk endpoints, and printer-class control requests
- IEEE 1284.4 / D4 handshake and `EPSON-CTRL` channel setup
- Epson command framing for:
  - status request (`st`)
  - factory service commands (`||`)
  - EEPROM read (`0x41`)
  - EEPROM write (`0x42`)
- Waste counter reset flow backed by the full upstream `devices.xml` database bundled in [`app/src/main/assets/devices.xml`](/home/tekware/Documenti/tekware/ae-reset/app/src/main/assets/devices.xml)
- Minimal on-device developer log

## Upstream reverse-engineering source

The protocol logic in this project is derived from:

- `upstream-ez-reset/src/ez_reset/d4.py`
- `upstream-ez-reset/src/ez_reset/printer.py`
- `upstream-ez-reset/src/ez_reset/status.py`
- `upstream-ez-reset/src/ez_reset/devices.py`
- `upstream-ez-reset/src/ez_reset/devices.xml`

## Important limits

- This workspace did not contain Android/Gradle tooling, so the project was not compiled here.
- The app now parses the bundled upstream XML at runtime and resolves Epson models from the printer IEEE 1284 device ID.
- Some Epson models use filtered counter bytes in `devices.xml`; this first port currently reads the raw EEPROM bytes directly, which is sufficient for the included examples but should be validated against real hardware.
- USB packet timing and interface selection can vary by printer; test on physical devices before production use.
