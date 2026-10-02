# Slice 1 device test: call a person

Slice 1 is done only when these pass on a real phone, from a fresh install,
for both Android and iOS. Record the device, OS version and result for each
row. A screenshot or screen recording beats a tick.

## Setup

- Android: uninstall any previous build first (`adb uninstall app.nodetree`), then install.
- iOS: delete the app from the phone, then run from Xcode.
- Contacts on the phone: one contact named exactly `Mom` with a mobile number,
  two contacts sharing a first name (e.g. `Sam Lee`, `Sam Patel`).
- No accessibility service enabled for the app (Android). There isn't one to enable.

## Checks

| # | Do | Expect | Android | iOS |
| --- | --- | --- | --- | --- |
| 1 | Launch | No permission prompts at launch | | |
| 2 | Tap Talk, say "call Mom" | Contacts permission is asked now (first time only) | | |
| 3 | Allow contacts | App says and shows "Call Mom?" with Yes/No; dialer has **not** opened | | |
| 4 | Say or tap "yes" | Dialer opens with Mom's mobile number filled in; no call is placed until you tap Call (iOS shows its own Call prompt) | | |
| 5 | Outline after 4 | Both steps "done", no "demo data" label on them | | |
| 6 | "call Sam" | App asks for the full name and names both Sams; nothing dials | | |
| 7 | "call Mom", then "no" | "Okay, I won't do that."; nothing dials | | |
| 8 | "call Mom", then "maybe" | Asked again for yes or no; nothing dials | | |
| 9 | "call Zed" (no such contact) | "I couldn't find Zed…"; nothing dials | | |
| 10 | Fresh install, deny contacts, "call Mom" | App explains it needs contacts permission; nothing dials | | |
| 11 | Airplane mode, "call Mom" by voice | Recognition still works on devices with on-device speech for the language (record which) | | |
| 12 | "what's on my calendar today" | Answer ends with "I used demo data for this." | | |
| 13 | TalkBack / VoiceOver on, swipe through the outline | Each step is read as "Step n of m, …" with its state | | |

Not done if any row is unverified or fails. Note any OEM-specific speech
behaviour in row 11 (some Android recognizers send audio to the cloud even
with "prefer offline").
