# Android Epson Reset

<p align="center">
  <a href="https://github.com/tekware-it/android-epson-reset/releases">
    <img alt="GitHub Release" src="https://img.shields.io/github/v/release/tekware-it/android-epson-reset?display_name=tag" />
  </a>
</p>

<p align="center">
  <img src="play-store/Screenshot_20260312_215851_Android%20Epson%20Reset.jpg" alt="Android Epson Reset screenshot 1" width="30%" />
  <img src="play-store/Screenshot_20260312_215906_Android%20Epson%20Reset.jpg" alt="Android Epson Reset screenshot 2" width="30%" />
  <img src="play-store/Screenshot_20260312_215921_Android%20Epson%20Reset.jpg" alt="Android Epson Reset screenshot 3" width="30%" />
</p>

Android port of [`ez-reset`](https://github.com/CiRIP/ez-reset) for Epson printer maintenance over USB OTG.

This app is aimed at Epson inkjet printers that expose the same service protocol used by `ez-reset`. It can detect supported Epson USB devices, open the control channel, read printer status, inspect waste ink counters, and issue waste counter reset commands directly from an Android device.

## What It Does

- Detects Epson USB printers via Android USB Host API
- Reads IEEE 1284 device ID and resolves printer model data from the bundled Epson database
- Enters Epson IEEE 1284.4 / D4 mode and opens the `EPSON-CTRL` channel
- Reads printer status, including ink levels when exposed by the printer
- Reads waste ink counters from EEPROM
- Resets waste ink counters using model-specific reset values
- Shows a compact status monitor UI inspired by `ez-reset`
- Includes an in-app protocol log for debugging USB / D4 traffic

## Current UI

- `Ink Levels` panel with adaptive single-row gauges for 4 to 8 inks
- `Waste Levels` panel with dynamic counter rows
- `Refresh`, `Reset All`, `About`, and protocol log copy actions
- Exit confirmation on Android back press

## Technical Notes

- Platform: Android 7.0+ (`minSdk 24`)
- Language: Kotlin
- UI: Jetpack Compose
- USB stack: `UsbManager`, `UsbDeviceConnection`, bulk endpoints, printer-class control requests
- Protocol: Epson `st` / `||` commands over D4 / IEEE 1284.4
- Model database: bundled `devices.xml` derived from the upstream `ez-reset` project

## Project Structure

- `app/src/main/java/info/tekware/aereset/ui`
  UI, dialogs, status monitor, log panel
- `app/src/main/java/info/tekware/aereset/service`
  Printer orchestration and command flow
- `app/src/main/java/info/tekware/aereset/usb`
  Android USB Host transport
- `app/src/main/java/info/tekware/aereset/protocol`
  Epson packet and D4 framing logic
- `app/src/main/java/info/tekware/aereset/data`
  Printer model and status data structures
- `app/src/main/assets/devices.xml`
  Bundled Epson model database

## Build

Open the project in Android Studio and build the `app` module.

Key identifiers:

- Project name: `android-epson-reset`
- Application ID: `info.tekware.aereset`
- Launcher label: `Android Epson Reset`

## Usage

1. Connect an Epson printer through USB OTG.
2. Grant USB permission when Android asks.
3. Open the app.
4. Tap `Refresh` to read printer status.
5. Review waste counter values.
6. Tap `Reset All` only if you understand the maintenance implications.

## Warning

Resetting waste ink counters changes internal printer maintenance values.

It does **not** replace the physical waste ink pads or maintenance tank. Use it only if you understand the hardware implications and accept the risk.

## Status

This is a working port in progress, not a finished production tool.

Known realities:

- USB behavior can vary between Epson models
- Some models may need further D4 timing / transport tuning
- Hardware validation is still essential before trusting resets on a new model

## Upstream Basis

This port was reverse-engineered primarily from:

- `ez-reset` repository: <https://github.com/CiRIP/ez-reset>
- `upstream-ez-reset/src/ez_reset/d4.py`
- `upstream-ez-reset/src/ez_reset/printer.py`
- `upstream-ez-reset/src/ez_reset/status.py`
- `upstream-ez-reset/src/ez_reset/devices.py`
- `upstream-ez-reset/src/ez_reset/devices.xml`

## License Note

This repository is currently distributed under `GPL-2.0-or-later` as a conservative compatibility choice.

However, the upstream `ez-reset` repository does not currently expose a clear top-level project license in its repository metadata. The upstream licensing situation should therefore still be confirmed with the original author or maintainer.
