# Attribution and modification notice

Original: [Home on Fire by toolicious](https://github.com/toolicious/home-on-fire).
Base: **v1.5.2**, commit `f629042674f1faa4b7bce0f54b77a5f849202651`.
Fork: [MikeisVS/home-on-fire-Fire-OS-6.7.0.9-](https://github.com/MikeisVS/home-on-fire-Fire-OS-6.7.0.9-).

**All credit for the original app, design, and upstream work belongs to toolicious and the original contributors.** Their Git history, license, copyright notices, assets and [README](UPSTREAM-README.md) are retained.

The owner created this fork to get the app working on their **Fire TV Cube Gen 1, AFTA, Fire OS 6.7.0.9 / NS6709/5669, Android 7.1.2 / API 25**. **OpenAI Codex implemented the Cube adaptation at the owner's request.** Independent maintenance does not imply endorsement or qualification by the original developer.

## Modifications made November 12, 2026

- Separate application ID `io.github.toolicious.homeonfire.afta` with the original Java/resource namespace.
- Usage Access monitor, cancellable 5.5-second scheduling, foreground confirmation, boot/wake handling, event overlap and circuit recovery.
- Original loading animation and original-style settings with working AFTA controls, target selection, diagnostics and recovery.
- Exact Settings-handler recognition preserving Couchy's Android Settings visit.
- Build-only Windows pipeline, separate signing, deterministic packaging, APK inspection, deployment scripts and host tests.
- Experimental Amazon background cleanup was measured and failed its retention limits. It remains disabled and its switch is omitted.

Modified inherited files include `AndroidManifest.xml`, `build.ps1`, `.gitignore`, `res/values/colors.xml`, `res/values/strings.xml`, and `AccessibilityHelper.java`, `BootReceiver.java`, `Colors.java`, `HijackService.java`, `LogViewerActivity.java`, `MainActivity.java` and `Prefs.java` under `src/io/github/toolicious/homeonfire/`. New AFTA classes, resource IDs, scripts, tests and public documentation are included. Modified source files carry dated notices.

## Update made November 14, 2026

Afta.11 removes only the additional boot scheduling wait, coalesces duplicate boot delivery and bounds initial usage history to the current boot. It adds the optional, strictly filtered READ_LOGS Home cue for single-Home return from preserved Settings and guards delayed Settings events against newer requests/covers. Home/HUD/wake delays and the suspension circuit remain.

The Windows installer now checks installed/candidate signing identities before stopping the app and attempts to reopen the existing app on installation failure. The host suite expands to 347 assertions. Original modification notices are retained and changed/new files carry the update date.

Core changes are in `RedirectEngine.java`, `UsageCursor.java`, `UsageMonitorService.java`, new `HomeIntentLog.java` and `HomeIntentMonitor.java`, the manifest, installation helpers, tests and public documentation.

| Version | Change |
| --- | --- |
| afta.1 | Initial Usage Access adaptation and independent package. |
| afta.2 | Missing foreground confirmation no longer suspends or retries by itself. |
| afta.3 | Ten-second overlap and ordering guard for delayed usage events. |
| afta.4 | Cancellable loading cover; rapid Home can still open the circuit. |
| afta.5 | RAM cleanup trial failed; cleanup remains off. |
| afta.6 | Restore the original settings appearance. |
| afta.7 | Historical inactive-controls attempt; verbose output required correction. |
| afta.8 | Working AFTA controls only; correct verbose logging. |
| afta.9 | Preserve Couchy's Android Settings visit. |
| afta.10 | Intermediate boot/Settings work; its Home broadcast was denied on this firmware. |
| afta.11 | Immediate eligible boot request, filtered Settings Home cue and signing preflight. |

Publication does not change the recorded [qualification status](COMPATIBILITY.md). The APK remains an experimental development/debug build. License: **GPL-3.0-or-later**, with the original [LICENSE](../LICENSE) unchanged. Corresponding source is available from this fork and its release source archive.
