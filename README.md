<p align="center">
  <img src="docs/banner.png" alt="Home on Fire" width="640">
</p>

# Home on Fire — Fire TV Cube Gen 1 fork

**All credit for the original Home on Fire app goes to [toolicious](https://github.com/toolicious) and the [original contributors](https://github.com/toolicious/home-on-fire/graphs/contributors).** This is a fork of [toolicious/home-on-fire](https://github.com/toolicious/home-on-fire), based on **v1.5.2**.

I created this fork to get the app working with my **first-generation Amazon Fire TV Cube (AFTA), running Fire OS 6.7.0.9 / NS6709/5669**. The compatibility changes were implemented with **OpenAI Codex**, at my request. The original app, design, and upstream work belong to the original developer; Codex adapted that work for this Cube. This fork is maintained independently of toolicious.

## Download

**[Download the APK](https://github.com/MikeisVS/home-on-fire/releases/download/v1.5.2-afta.9/home-on-fire-afta.apk)** · **[Release notes, checksums, and source](https://github.com/MikeisVS/home-on-fire/releases/tag/v1.5.2-afta.9)**

Version **1.5.2-afta.9**, code **14009**, package **`io.github.toolicious.homeonfire.afta`**. It installs alongside the original app. Enable only one Home redirection app at a time.

This is an **experimental preview**, using the same development/debug build as the recorded Cube tests. Full reliability qualification and testing on other firmware/devices remain outstanding. Read [compatibility and testing](docs/COMPATIBILITY.md).

## Cube changes

The original accessibility service was rejected by the tested Cube's firmware even after permissions were granted. This fork uses **Usage Access** on AFTA/API 25 to observe Amazon Home and open the selected launcher.

- Home redirection with **Couchy** selected initially if installed.
- The original loading animation during a **5.5-second** Home wait; firmware event delivery can add delay.
- Target launch on boot and wake, enabled by default alongside Home redirection.
- Long-Home escape/return to Amazon, original-style settings, and local diagnostics/recovery.
- A fix that preserves **Couchy → Settings → Android settings** instead of interrupting it with a Home redirect.
- Working AFTA controls only; unsupported double-Home, custom key mapping, and Menu shortcuts are omitted.

The original accessibility path remains for other devices, with **no Fire OS 7/8 hardware regression testing in this fork**. Amazon RAM cleanup failed its measured trial, remains disabled, and its switch is omitted.

## Install

Install your preferred launcher first, enable the Cube's **ADB debugging**, and use Google's [Android platform-tools](https://developer.android.com/tools/releases/platform-tools). Replace `<cube-ip>` with the Cube's address and approve its ADB prompt.

```text
adb connect <cube-ip>:5555
adb -s <cube-ip>:5555 install -r home-on-fire-afta.apk
adb -s <cube-ip>:5555 shell appops set io.github.toolicious.homeonfire.afta GET_USAGE_STATS allow
adb -s <cube-ip>:5555 shell appops set io.github.toolicious.homeonfire.afta SYSTEM_ALERT_WINDOW allow
adb -s <cube-ip>:5555 shell am start -n io.github.toolicious.homeonfire.afta/io.github.toolicious.homeonfire.MainActivity
```

Open the app, select your launcher, and check Usage Access and monitor status. Give Home launches time to finish. Three automatic requests within 30 seconds suspend automation; inspect the cause and use **Advanced settings → Resume after automatic suspension**. Long-Home returns from a preserved Amazon Settings visit.

Downloader/USB sideloading is also possible, followed by the ADB permission grants. No root is required. See [installation and recovery](docs/INSTALL-AFTA.md) for permission checks and the inspected Windows installer.

## Build

Prerequisites: Windows PowerShell **5.1**, Git, **JDK 25.0.1**, Android SDK **platform 35**, and **build tools 36.1.0**. No Gradle is needed.

```powershell
git clone https://github.com/MikeisVS/home-on-fire.git
cd home-on-fire
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./tests/run-tests.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./build.ps1
```

Output: `artifacts/home-on-fire-afta.apk`, a SHA-256 checksum, APK/signature inspection, and a build record. Minimum SDK is 21; target SDK is 34. Building does not install or increment the version. Optional inputs are `-SdkRoot`, `-JdkRoot`, `-CompileSdk`, `-BuildToolsVersion`, and `-KeystorePath`.

The default creates a persistent development key under `%LOCALAPPDATA%/HomeOnFireAfta/signing/`, outside Git. Keep your key for future updates. A self-build signed with another key cannot update this fork's signed APK in place.

The host suite has **262 assertions**: 80 redirection, 21 usage cursor, 79 loading cover, 59 cleanup, and 23 script helpers. Passing these does not establish hardware compatibility.

## Limits, privacy, and license

Usage Access reports activities rather than raw Home presses. Back-to-home can also redirect; Home on the same already foreground Amazon activity may produce no new event. Earlier missed Home/wake events and process-loss failures remain limitations. Wake automation can bring the launcher over a previously open app. See [validation boundaries](docs/COMPATIBILITY.md).

The app has **no Internet permission**. Settings and optional diagnostic metadata stay local. Public source excludes signing keys, private device logs, test screenshots, and personal device addresses. The preview is debuggable to support the existing local diagnostic tools.

License: **[GPL-3.0-or-later](LICENSE)**. Original copyright notices and the license are preserved; corresponding source is provided with the APK. Fork changes were made on **November 12, 2026** with OpenAI Codex. See [attribution and changes](docs/FORK.md) and the [preserved upstream README](docs/UPSTREAM-README.md), including its additional credits.
