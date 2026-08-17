# Android rules

## Android SDK is incomplete
- code: IMPORTED-OPS-7
- kind: curative
- tier: android-emulator
- symptom: sdkmanager, platform-tools, emulator, platform, or build-tools is missing.
- check: inspect SDK directories and sdkmanager --list.
- cause: an interrupted download or unaccepted license.
- fix: request approval, then install only missing Android 35 components and accept licenses.
- verify: adb version, emulator -list-avds with tima_test, and APK assembly succeed.

## Multiple Android devices present
- code: IMPORTED-OPS-9
- kind: preventive
- tier: *
- symptom: adb reports more than one device or actions hit the wrong peer.
- check: run adb devices -l.
- cause: a second emulator or a USB phone is attached.
- fix: select the intended serial and pass -s to every ADB command; request approval before stopping another device.
- verify: install, reverse mapping, and activity belong to that serial.

## Incompatible installed Android signature
- code: IMPORTED-OPS-10
- kind: curative
- tier: phone-install
- symptom: install returns INSTALL_FAILED_UPDATE_INCOMPATIBLE.
- check: inspect the installed io.tima.app signing identity.
- cause: the installed package uses a different signing key.
- fix: explain state loss and request approval before uninstalling the old package.
- verify: io.tima.app/.MainActivity starts after install.

## Android UI coordinates stale
- code: IMPORTED-OPS-12
- kind: curative
- tier: android-emulator
- symptom: an ADB tap or swipe misses the intended control.
- check: create a fresh uiautomator dump and inspect bounds, orientation, resolution, and screen state.
- cause: coordinates were reused after layout or state changed.
- fix: derive coordinates from current XML; prefer resource IDs/text or manual interaction.
- verify: a new dump confirms the intended transition.
