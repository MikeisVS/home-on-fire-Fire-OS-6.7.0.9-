# Install and recover on the tested Cube

Target: **Fire TV Cube Gen 1 (AFTA), Fire OS 6.7.0.9 / API 25**. Read [compatibility](COMPATIBILITY.md). The preview is a development/debug APK with its own signing identity.

## Download and inspect

Get `home-on-fire-afta.apk` and `home-on-fire-afta.apk.sha256` from [the release](https://github.com/MikeisVS/home-on-fire/releases/tag/v1.5.2-afta.9). Corresponding source is included there.

```powershell
Get-FileHash -LiteralPath ./home-on-fire-afta.apk -Algorithm SHA256
Get-Content -LiteralPath ./home-on-fire-afta.apk.sha256
```

Check that the hashes match. Keep the previous APK before updating.

## ADB setup

Install your preferred launcher, enable **ADB debugging**, and find the Cube's address under **Settings → My Fire TV → About → Network**. With Google's platform-tools on PATH, replace `<cube-ip>` and approve the TV's debugging prompt.

```text
adb connect <cube-ip>:5555
adb -s <cube-ip>:5555 install -r home-on-fire-afta.apk
adb -s <cube-ip>:5555 shell appops set io.github.toolicious.homeonfire.afta GET_USAGE_STATS allow
adb -s <cube-ip>:5555 shell appops set io.github.toolicious.homeonfire.afta SYSTEM_ALERT_WINDOW allow
adb -s <cube-ip>:5555 shell appops get io.github.toolicious.homeonfire.afta GET_USAGE_STATS
adb -s <cube-ip>:5555 shell appops get io.github.toolicious.homeonfire.afta SYSTEM_ALERT_WINDOW
adb -s <cube-ip>:5555 shell am start -n io.github.toolicious.homeonfire.afta/io.github.toolicious.homeonfire.MainActivity
```

Both app-ops should report `allow`. Usage Access drives the monitor; overlay permission enables the loading cover. In the app, choose the launcher and check monitor status. Installed Couchy is the initial target if none is saved. Home, boot, and wake are enabled by default; wake can bring the launcher over a previously open app.

This AFTA backend does not need a secure-settings/accessibility grant. Enable only one redirection app. No root, Amazon package disabling, or default-home replacement is required. Downloader/USB sideloading is possible, followed by these ADB grants.

## Inspected Windows helper

From the source checkout, put the APK and checksum under `artifacts/`, or supply `-ApkPath`. The helper needs the project's Android build tools to inspect the APK.

```powershell
./scripts/Install-Afta.ps1 -Serial '<cube-ip>:5555' -WhatIf
./scripts/Install-Afta.ps1 -Serial '<cube-ip>:5555'
```

It verifies AFTA/API 25, the APK and signature, grants this package's app-ops, and opens setup. Every device command has an explicit serial. Building does not invoke installation.

## Recovery and uninstall

Let the 5.5-second Home wait finish. Three automatic requests within 30 seconds suspend automation. Inspect the cause, then use **Advanced settings → Resume after automatic suspension**. **Stop monitor and all automatic redirection** cancels local pending work; submitted Android activity starts cannot be withdrawn.

Long-Home escapes/returns through Amazon on the tested firmware, including returning from Couchy's preserved Android Settings visit. Diagnostics remain local. The debuggable flag supports the existing export helper; review exported metadata before sharing it.

```powershell
./scripts/Get-AftaDiagnostics.ps1 -Serial '<cube-ip>:5555'
./scripts/Uninstall-Afta.ps1 -Serial '<cube-ip>:5555' -WhatIf
./scripts/Uninstall-Afta.ps1 -Serial '<cube-ip>:5555'
```

Or uninstall only the fork:

```text
adb -s <cube-ip>:5555 uninstall io.github.toolicious.homeonfire.afta
```

Uninstalling removes the fork's settings/logs; export them first if needed. Upstream Home on Fire, your launcher, and Amazon remain separate. A build signed with another key requires uninstalling the existing fork first, losing its data. Downgrades remain device-dependent and are not a qualified public recovery path.
