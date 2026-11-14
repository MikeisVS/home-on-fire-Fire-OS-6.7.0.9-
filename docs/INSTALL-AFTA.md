# Install and recover on the tested Cube

Target: **Fire TV Cube Gen 1 (AFTA), Fire OS 6.7.0.9 / API 25**. Read [compatibility](COMPATIBILITY.md). The **1.5.2-afta.11** preview is a development/debug APK with its own signing identity.

## Download and inspect

Get `home-on-fire-afta.apk` and `home-on-fire-afta.apk.sha256` from [the release](https://github.com/MikeisVS/home-on-fire-Fire-OS-6.7.0.9-/releases/tag/v1.5.2-afta.11). Corresponding source and its checksum are included there.

```powershell
Get-FileHash -LiteralPath ./home-on-fire-afta.apk -Algorithm SHA256
Get-Content -LiteralPath ./home-on-fire-afta.apk.sha256
```

Check that the hashes match. Keep the previous APK and your original signing key before updating. The published afta.11 APK retains the same signing identity as the published afta.9 APK and can update it using `install -r`.

## ADB setup

Install your launcher, enable **ADB debugging**, and find the Cube's address under **Settings → My Fire TV → About → Network**. With Google's platform-tools on PATH, replace `<cube-ip>` and approve the TV's debugging prompt.

```text
adb connect <cube-ip>:5555
adb -s <cube-ip>:5555 install -r home-on-fire-afta.apk
adb -s <cube-ip>:5555 shell appops set io.github.toolicious.homeonfire.afta GET_USAGE_STATS allow
adb -s <cube-ip>:5555 shell appops set io.github.toolicious.homeonfire.afta SYSTEM_ALERT_WINDOW allow
adb -s <cube-ip>:5555 shell appops get io.github.toolicious.homeonfire.afta GET_USAGE_STATS
adb -s <cube-ip>:5555 shell appops get io.github.toolicious.homeonfire.afta SYSTEM_ALERT_WINDOW
```

Both app-ops should report `allow`. Usage Access drives the monitor; overlay permission enables the loading cover.

## Optional single-Home return from Settings

The additional `READ_LOGS` permission gives the application access to system logs. Its listener reads filtered ActivityManager Home records, validates the system UIDs, exact Home intent/component, freshness and ordering, and sends only a Home timestamp to the redirect policy. Raw system-log text is not stored. See [the permission boundary](HOME-INTENT-LOG.md).

To enable that feature:

```text
adb -s <cube-ip>:5555 shell pm grant io.github.toolicious.homeonfire.afta android.permission.READ_LOGS
```

Then open setup:

```text
adb -s <cube-ip>:5555 shell am start -n io.github.toolicious.homeonfire.afta/io.github.toolicious.homeonfire.MainActivity
```

Choose your launcher and check monitor status. Installed Couchy is the initial target if none is saved. Home, boot and wake are enabled by default; wake can bring the launcher over a previously open app.

Without the additional grant, the existing Usage Access path and long-Home return remain available. A short Home press within an already foreground Settings/Home activity may be missed. To revoke only the extra permission:

```text
adb -s <cube-ip>:5555 shell pm revoke io.github.toolicious.homeonfire.afta android.permission.READ_LOGS
```

This AFTA backend does not need a secure-settings/accessibility grant. Enable only one redirection app. No root, Amazon package disabling or default-home replacement is required. Downloader/USB sideloading is possible, followed by these ADB grants.

## Inspected Windows helper

From the source checkout, put the APK and checksum under `artifacts/`, or supply `-ApkPath`. The helper needs the project's Android build tools to inspect the APK.

With the optional Settings Home feature:

```powershell
./scripts/Install-Afta.ps1 -Serial '<cube-ip>:5555' -EnableHomeIntentLog -WhatIf
./scripts/Install-Afta.ps1 -Serial '<cube-ip>:5555' -EnableHomeIntentLog
```

Omit `-EnableHomeIntentLog` to keep the additional permission ungranted. The helper verifies AFTA/API 25, APK/checksum/SDK, and the candidate against the installed signing identity **before force-stop**, including under `-WhatIf`. A mismatch leaves the installed app running. A failed installation attempts to reopen the previous app. Every device command has an explicit serial. Building does not invoke installation.

## Recovery and uninstall

Let the 5.5-second Home/HUD/wake wait finish. Boot requests no longer add that wait. Three automatic requests within 30 seconds suspend automation. Inspect the cause, then use **Advanced settings → Resume after automatic suspension**. **Stop monitor and all automatic redirection** cancels local pending work; submitted Android starts cannot be withdrawn.

Long-Home escapes/returns through Amazon on the tested firmware. With the optional listener enabled, single Home ends a preserved Android Settings visit while Back keeps it open. See the [recorded limits](COMPATIBILITY.md).

A differently signed self-build cannot update the published APK in place. Recover the original signing key or retain the working APK; the installer rejects mismatches before stopping the app. Do not erase app data to work around a key mismatch. Downgrades remain device-dependent and are not a qualified public recovery path.

The debuggable flag supports the local export helper. Review exported metadata before sharing it.

```powershell
./scripts/Get-AftaDiagnostics.ps1 -Serial '<cube-ip>:5555'
./scripts/Uninstall-Afta.ps1 -Serial '<cube-ip>:5555' -WhatIf
./scripts/Uninstall-Afta.ps1 -Serial '<cube-ip>:5555'
```

Or uninstall only the fork:

```text
adb -s <cube-ip>:5555 uninstall io.github.toolicious.homeonfire.afta
```

Uninstalling removes the fork's settings/logs; export them first if needed. Upstream Home on Fire, your launcher and Amazon remain separate.
