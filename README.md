# MyVoltalis Widget

A tiny Android home-screen widget to see and change your [MyVoltalis](https://www.myvoltalis.com) heating mode without opening the official app.

## Features

- Shows the current mode: Auto, Confort, Eco or Hors-gel.
- Tap the widget to pick a mode (Auto follows your Voltalis programming).
- Optional EDF Tempo "red day" overrides: a timetable of modes applied during peak hours (06:00–22:00) on red days. Tempo colours come from [calendrier-tempo.fr](https://www.calendrier-tempo.fr) (CC BY 4.0).

## Install

Download `MyVoltalisWidget.apk` from the [latest release](../../releases/latest) and install it (you'll need to allow installs from unknown sources). Open the app once, enter your MyVoltalis login and password, then add the widget from your launcher.

Your credentials are stored only in the app's private preferences on your device and are sent only to `api.myvoltalis.com`.

## Build

Requirements: JDK 17, Android SDK, Gradle 8.7.

```powershell
./accept-licenses.ps1   # once, to accept SDK licenses
./build.ps1             # produces app/build/outputs/apk/release/
```

The release APK is signed with the debug key so it is directly installable.

## Disclaimer

Unofficial project, not affiliated with Voltalis. It uses the same private API as the official app, which may change at any time.

## License

[WTFPL](LICENSE)
