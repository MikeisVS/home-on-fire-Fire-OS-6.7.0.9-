# App loading and VPN troubleshooting

Home on Fire handles launcher redirection. IPVanish and streaming applications
manage their own connections, playback and app settings.

## Current recorded follow-up — November 14, 2026

The owner reported that apps now work normally after the separate device
investigation. Earlier per-app compilation changes were restored to their
individually recorded original settings. Those device changes are separate from
the afta.12 launcher update. No root cause for every reported slowdown or crash
was established, and long-term performance remains unqualified.

The afta.12 code change recovers a later Home return after an earlier unconfirmed
launch. It retains the existing Home delay and circuit. Amazon can appear before
the loading cover arrives.

## Separate VPN behavior

Keep a VPN configuration that is stable on the device. In the recorded IPVanish
comparison, leaving with Back or allowing the connection to settle avoided an
early-Home disconnect. A 20-second wait worked in the owner's check; an exact
minimum safe wait and the native cause were not established. Immediate-Home VPN
work was stopped at the owner's request.

Hulu opened with the VPN off and showed a network error with the VPN on. The
owner chose to use Hulu with the VPN off; no Hulu bypass or unblocking change is
included here.

An IPVanish Automatic App Launch selection for Couchy was made during an
interrupted check. That handoff was not qualified by a controlled test. It is an
IPVanish setting and is not configured by installing Home on Fire.

## If slowness returns

Record the affected app, whether the delay is before its first screen or while
loading content, and whether the VPN is connected. Reopen the affected app and
compare ordinary use with the VPN disconnected when appropriate. Keep existing
app data and the known working settings while checking the specific failure.

Per-app compilation can affect behavior on this firmware. Reapplying broad
forced `speed` compilation is not a supported fix for the reported regressions.
The failed Amazon RAM-cleanup experiment remains disabled.

Home on Fire's local diagnostics can identify monitor suspension, delayed Home
events and missing launcher confirmations. Installation and permission recovery
are described in [the setup guide](INSTALL-AFTA.md); hardware validation limits
remain in [compatibility](COMPATIBILITY.md).
