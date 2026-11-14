# Optional Settings Home listener

Added for **1.5.2-afta.11**, tested on **AFTA / Fire OS 6.7.0.9 / API 25** on November 14, 2026. This remains an experimental preview.

Amazon's generic Android Settings route hands over to its own Home activity before displaying Settings. Afta.9 preserved that visit, but a short Home press inside the same activity could produce no new UsageEvent. Long-Home remained the return path.

The attempted CLOSE_SYSTEM_DIALOGS/homekey receiver was denied by an Amazon signature permission on the tested firmware. It is not used by afta.11. The replacement reads the separately observed system ActivityManager Home-launch records with the additional `android.permission.READ_LOGS` grant.

## Permission and data boundary

`READ_LOGS` gives the application access to system logs; it is broader than this feature's narrow use. The installer grants it only with the explicit `-EnableHomeIntentLog` option. Manual grant/revocation is documented in [installation](INSTALL-AFTA.md).

The listener:

- Runs as the application's UID and filters ActivityManager records.
- Requires both the actual logger UID and the ActivityManager caller UID to be the system UID.
- Requires the exact MAIN/HOME action/category, Amazon Home component and supported flags.
- Rejects Settings-app handovers, explicit app/ADB starts, wrong UIDs, stale/future records and duplicate records.
- Uses clock/freshness guards and stops or re-arms with standby, permission, configuration and service lifecycle changes.
- Passes only a monotonic Home timestamp into the serialized policy. Raw system-log text and intent extras are not persisted.

A Home cue can end Settings preservation without interrupting Back/navigation or intentional Amazon escape. Delayed Settings events cannot cancel a newer Home request or loading cover. Duplicate Home paths share the existing request/circuit rules.

## Evidence and limits

The 347-assertion host suite includes 29 strict parser and 50 startup/Home checks. Recorded awake ADB Settings/Back/Home checks passed, three ADB restarts retained the grant, and the owner reported the prompted physical Settings/Back/Home return worked. One passively observed return lacked a fresh target UsageEvent and timed out unconfirmed despite the positive visual report.

The broader physical Home/HUD sequence, power-disconnection cold boots, long-term survival, log rotation and other firmware remain unqualified. Host checks do not establish future firmware behavior. See [compatibility](COMPATIBILITY.md).
