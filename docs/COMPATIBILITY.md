# Compatibility and validation boundaries

Target: the owner's **Fire TV Cube Gen 1**, **AFTA**, Fire OS **6.7.0.9 / NS6709/5669**, Android **7.1.2 / API 25**, with **Couchy 1.0.6**. This is an **experimental preview**. Another firmware or device is unverified.

## Current recorded evidence — November 14, 2026

Latest tested version: **1.5.2-afta.11**, code **14011**. Installed bytes matched the reviewed APK, with the same signing identity as afta.9. The firmware rejects the original accessibility provider; this Cube uses the separate Usage Access backend.

- **347 passing host assertions**: 80 redirection, 23 cursor, 50 startup/Home, 29 Home-log recognition, 79 cover, 59 cleanup, and 27 script helpers.
- Bounded awake ADB checks observed normal Home, preserved Couchy Android Settings entry, Back within the Settings grid, single Home from preserved root/subpage Settings, and deliberate long-Home Amazon escape/return.
- The owner reported **"It's working"** for the prompted physical Settings/Back/single-Home check. Passive metadata recorded two Settings exits and launch requests: one was freshly confirmed; the other timed out without a fresh target UsageEvent. Preserve the distinction between the positive visual report and incomplete machine confirmation.
- Three controlled **ADB restarts** each produced one confirmed boot request. Boot notification arrived at about 72 seconds uptime; monitor-to-request took 38–180 ms and request-to-observed Couchy took 351–575 ms. These are not physical power-disconnection cold boots.
- The additional `READ_LOGS` grant survived those restarts. The filtered listener excludes Settings-app handovers, explicit app/ADB starts, wrong UIDs, stale/future records and duplicates.
- Standby/wake restarted the reader and showed Couchy in the foreground window snapshot, but no fresh target UsageEvent arrived. The monitor timed out **unconfirmed**, without retry or suspension.
- APK checks cover package/version/SDK/components, ZIP alignment, and v1/v2 signatures. Publication verifies the rebuild against the recorded tested APK; see [release verification](RELEASE-VERIFICATION.json).
- No device settings or APK installation are changed during publication.

Raw logs, package dumps, private preferences, screenshots from tests, and device addresses remain local. This document summarizes the recorded results.

## Earlier evidence retained — November 12, 2026

Afta.9 passed three ADB D-pad/OK activations of Couchy's Android Settings, subpage/Back navigation, long-Home return and a separate normal Home trial. The owner also reported that bounded physical Settings/return check worked. Its 262 host assertions and earlier Home/HUD/wake/boot observations remain historical evidence; they do not replace current qualification.

## Retained failures and limits

- Earlier Home/wake transitions were missed. One wake failed during monitor process loss amid low-memory events; long-term survival remains unqualified.
- Another Home miss occurred with a running poller. Increased overlap addressed one reproduced late-publication mechanism, without proving the cause of every miss.
- Rapid Home can open the **three requests / 30 seconds** suspension circuit. Recovery is explicit in Advanced settings.
- Usage Access reports activities rather than raw Home presses. Back-to-home can redirect. Without the optional Home listener, Home on an already foreground Amazon activity may produce no new event.
- Home, HUD return and wake still wait **5.5 seconds after observation**. Event delivery and system scheduling add latency. Already-submitted Android starts cannot be withdrawn. Only the additional boot scheduling wait was removed.
- A missing target observation remains unconfirmed after ten seconds, without automatic retry or suspension by itself.
- Amazon's generic Android Settings route initially selected its Home tab in the current trials. Selecting the gear exposed the visible grid; the app preserved the visit. The generic route is not guaranteed to open the grid directly.
- The original CLOSE_SYSTEM_DIALOGS/homekey attempt was denied by an Amazon signature permission on this firmware. The current app uses the separately tested READ_LOGS fallback, not that rejected broadcast.
- **Early post-reboot app loading remains unresolved.** The app update is not a demonstrated general performance fix.
- Five earlier paired cleanup trials found **no sustained RAM saving** and **1,817 ms additional median ADB Home delay**. Cleanup stays off and its switch is omitted.

## Outstanding qualification

The full **20 physical Home / ten HUD** sequence, repeated power-disconnection cold boots/wakes, extended monitor survival, log rotation, fault/clock handling on hardware, and actual experimental-uninstall qualification are incomplete. Smaller physical smoke reports and ADB restarts do not replace those checks.

Fire OS 7/8 behavior is inherited from the original accessibility path, with **no hardware regression testing in this fork**. Other Fire OS 6 builds are unverified. Vega OS is not Android and cannot run this APK.

Build success and permission grants alone do not establish compatibility. Read [installation and recovery](INSTALL-AFTA.md) and the [optional Home-listener permission notes](HOME-INTENT-LOG.md).
