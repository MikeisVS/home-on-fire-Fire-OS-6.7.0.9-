# Compatibility and validation boundaries

Target: the owner's **Fire TV Cube Gen 1**, **AFTA**, Fire OS **6.7.0.9 / NS6709/5669**, Android **7.1.2 / API 25**, with **Couchy 1.0.6**. This is an **experimental preview**. Another firmware or device is unverified.

## Recorded evidence — November 12, 2026

Latest tested version: **1.5.2-afta.9**, code **14009**. Installed bytes matched the inspected delivery. The firmware rejected the original accessibility service; this Cube uses the separate Usage Access backend.

- Earlier ADB trials observed Home redirection, Amazon HUD escape/return, wake behavior, and boot delivery to the monitor.
- Afta.9 passed three ADB D-pad/OK activations of Couchy's Android Settings, subpage/Back navigation, long-Home return, and a separate normal Home trial.
- The owner subsequently reported that physical Settings and return both worked. This completes that bounded smoke check.
- The host suite has **262 passing assertions**: 80 redirection, 21 cursor, 79 cover, 59 cleanup, and 23 script helpers.
- APK checks cover package/version/SDK/components, ZIP alignment, and v1/v2 signatures. The publication rebuild is compared with the tested APK and summarized in the release verification record.

Raw logs, package dumps, screenshots, private preferences, and device addresses remain local and are excluded from the public repository. This document summarizes their recorded results.

## Retained failures and limits

- Earlier Home/wake transitions were missed. One wake failed during monitor process loss amid low-memory events; long-term survival remains unqualified.
- Another Home miss occurred with a running poller. Increased overlap addresses one reproduced late-publication mechanism; it does not establish the cause of every earlier miss.
- Rapid Home can open the **three requests / 30 seconds** suspension circuit. Recovery is explicit in Advanced settings.
- Usage Access reports activities, not physical Home presses. Back-to-home can redirect; Home on the already foreground Amazon activity may produce no new event.
- Home waits **5.5 seconds after observation**. Event delivery and system scheduling add latency. Already-submitted Android starts cannot be withdrawn.
- A missing target observation remains unconfirmed after ten seconds, without an automatic retry or suspension by itself.
- A preserved Amazon Settings/escape visit may need long-Home to return.
- Five paired cleanup trials found **no sustained RAM saving** and **1,817 ms additional median ADB Home delay**. Cleanup stays off and its switch is omitted.

## Outstanding qualification

The full **20 physical Home / ten HUD** sequence, repeated cold boots/wakes, extended monitor survival, log rotation, and actual experimental-uninstall qualification are not complete. Smaller physical smoke reports do not replace those checks.

Fire OS 7/8 behavior is inherited from the original accessibility path, with **no hardware regression testing in this fork**. Other Fire OS 6 builds are unverified. Vega OS is not Android and cannot run this APK.

Build success and permission grants alone do not establish compatibility. Read [installation and recovery](INSTALL-AFTA.md).
