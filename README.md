<p align="center">
  <img src="docs/banner.png" alt="Home on Fire" width="640">
</p>

# Home on Fire — Fire TV Cube Gen 1 fork

**All credit for the original Home on Fire app goes to [toolicious](https://github.com/toolicious) and the [original contributors](https://github.com/toolicious/home-on-fire/graphs/contributors).** This independently maintained fork is based on [Home on Fire v1.5.2](https://github.com/toolicious/home-on-fire).

I created this fork to get the app working with my **first-generation Amazon Fire TV Cube (AFTA), running Fire OS 6.7.0.9 / NS6709/5669**. The Cube compatibility changes were implemented with **OpenAI Codex**, at my request. The original app, design, and upstream work belong to the original developer.

## Download

**[Download the APK](https://github.com/MikeisVS/home-on-fire-Fire-OS-6.7.0.9-/releases/download/v1.5.2-afta.12/home-on-fire-afta.apk)** · **[Release notes, checksums, and source](https://github.com/MikeisVS/home-on-fire-Fire-OS-6.7.0.9-/releases/tag/v1.5.2-afta.12)**

Version **1.5.2-afta.12**, code **14012**, package **`io.github.toolicious.homeonfire.afta`**. It installs alongside the original app. Enable only one Home redirection app at a time.

This is an **experimental preview**, using the same development/debug APK as the recorded Cube tests. Full reliability qualification and testing on other firmware/devices remain outstanding. Read [compatibility and testing](docs/COMPATIBILITY.md).

## Updated in afta.12

- **Recovery after a missed Home return.** If an earlier Home launch timed out without a fresh launcher confirmation and Amazon reused the same activity, a new verified Home cue from the optional `READ_LOGS` listener can schedule another return. Recovery keeps the 5.5-second delay, cancellation rules and suspension circuit. A timeout alone does not retry.
- **362 host assertions**, including 15 recovery checks covering duplicate cues, delayed delivery, navigation cancellation and reset conditions.

The earlier afta.11 improvements remain:

- **No extra 5.5-second boot wait.** The first eligible monitor poll can start the target after boot notification. Duplicate boot delivery is coalesced, and prior-boot activity history cannot satisfy the new startup request. Fire OS's own boot delay remains.
- **Single Home from preserved Android Settings.** An optional, filtered system Home listener distinguishes a Home press from the Settings handover. It requires the additional `READ_LOGS` grant described below. Back keeps the Settings visit open; deliberate long-Home escape to Amazon remains available.
- **Safer APK updates.** The Windows installer compares the candidate and installed signing identities before stopping the app. A mismatched key is rejected; an installation failure attempts to reopen the existing app.

## Cube features

The tested Cube's firmware rejects the original accessibility service even after secure-settings permissions are granted. This fork uses **Usage Access** on AFTA/API 25 to observe Amazon Home and open the selected launcher.

- Home redirection with **Couchy** selected initially if installed.
- The original loading animation during the **5.5-second Home/HUD/wake wait**; event delivery and scheduling can add delay. Only the additional boot wait was removed.
- Target launch on boot and wake, enabled by default alongside Home redirection.
- Long-Home escape/return to Amazon, original-style settings, and local diagnostics/recovery.
- Preservation of **Couchy → Settings → Android settings**.
- Working AFTA controls only; unsupported double-Home, custom key mapping, and Menu shortcuts are omitted.

The original accessibility path remains for other devices, with **no Fire OS 7/8 hardware regression testing in this fork**. Amazon RAM cleanup failed its measured trial, remains disabled, and its switch is omitted.

## Install

Install your preferred launcher first, enable the Cube's **ADB debugging**, and use Google's [Android platform-tools](https://developer.android.com/tools/releases/platform-tools). Replace `<cube-ip>` with the Cube's address and approve its ADB prompt.

```text
adb connect <cube-ip>:5555
adb -s <cube-ip>:5555 install -r home-on-fire-afta.apk
adb -s <cube-ip>:5555 shell appops set io.github.toolicious.homeonfire.afta GET_USAGE_STATS allow
adb -s <cube-ip>:5555 shell appops set io.github.toolicious.homeonfire.afta SYSTEM_ALERT_WINDOW allow
```

**Optional: single-Home return from preserved Settings.** `READ_LOGS` gives the app access to system logs. The listener filters recent system ActivityManager Home launches and sends only a Home timestamp to the redirect policy; it does not store raw system-log text. Grant it if you want this feature:

```text
adb -s <cube-ip>:5555 shell pm grant io.github.toolicious.homeonfire.afta android.permission.READ_LOGS
```

Then open setup:

```text
adb -s <cube-ip>:5555 shell am start -n io.github.toolicious.homeonfire.afta/io.github.toolicious.homeonfire.MainActivity
```

Choose your launcher and check Usage Access and monitor status. Without `READ_LOGS`, the existing activity-event Home path and long-Home return remain; a short Home press inside the already foreground Settings/Home activity may be missed.

Give Home launches time to finish. Three automatic requests within 30 seconds suspend automation; inspect the cause and use **Advanced settings → Resume after automatic suspension**.

Downloader/USB sideloading is also possible, followed by the ADB grants. No root is required. See [installation and recovery](docs/INSTALL-AFTA.md) and the [Home-listener permission notes](docs/HOME-INTENT-LOG.md).

## Build

Prerequisites: Windows PowerShell **5.1**, Git, **JDK 25.0.1**, Android SDK **platform 35**, and **build tools 36.1.0**. No Gradle is needed.

```powershell
git clone https://github.com/MikeisVS/home-on-fire-Fire-OS-6.7.0.9-.git
cd home-on-fire-Fire-OS-6.7.0.9-
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./tests/run-tests.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./build.ps1
```

Output: `artifacts/home-on-fire-afta.apk`, a SHA-256 checksum, APK/signature inspection, and a build record. Minimum SDK is 21; target SDK is 34. Building does not install or increment the version. Optional inputs are `-SdkRoot`, `-JdkRoot`, `-CompileSdk`, `-BuildToolsVersion`, and `-KeystorePath`.

The default creates a persistent development key under `%LOCALAPPDATA%/HomeOnFireAfta/signing/`, outside Git. Keep the original key for updates. A self-build signed with another key cannot update the published APK in place; the installer checks this before stopping the installed app.

The host suite has **362 assertions**: 80 redirection, 23 usage cursor, 65 startup/Home, 29 Home-log recognition, 79 loading cover, 59 cleanup, and 27 script helpers. Passing these does not establish hardware compatibility.

## Limits, privacy, and license

Earlier missed Home/wake events and process-loss failures remain limitations. Wake automation can bring the launcher over a previously open app. **Early post-reboot app loading remains unresolved**; removing the app's boot wait does not establish a general Fire TV performance fix. See [validation boundaries](docs/COMPATIBILITY.md) and [app/VPN troubleshooting](docs/TROUBLESHOOTING.md).

The app has **no Internet permission**. Settings and optional diagnostic metadata stay local. `READ_LOGS` is optional and broader than the filtered Home listener's use. Public source excludes signing keys, private device logs, screenshots from tests, and personal device addresses. The preview is debuggable to support the existing local diagnostic tools.

License: **[GPL-3.0-or-later](LICENSE)**. Original copyright notices and the license are preserved; corresponding source is provided with the APK. Fork changes were made on **November 12 and November 14, 2026**, with OpenAI Codex. See [attribution and changes](docs/FORK.md) and the [preserved upstream README](docs/UPSTREAM-README.md).
