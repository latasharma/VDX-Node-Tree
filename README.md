# Node Tree app

One iOS and Android app where everything the user can do is a node in one
tree: the request, the plan, each step, each capability and each app. Voice
front ends ("drivers") are swappable, so we build and test now with stand-ins
and plug in Alexa when we get access.

## What's here

| Path | What | Status |
| --- | --- | --- |
| `shared/` | Kotlin Multiplatform core: tree model, catalog, recipe-first planner, step runner with confirmation gate, narration and outline, driver contract, Console and Simulated Alexa drivers, contact matching and phone executor | Builds; 57 tests pass on JVM |
| `cli/` | Developer console for talking to the tree with either driver | Builds and runs |
| `androidApp/` | Compose app: talk (system recognizer, offline preferred) or type, yes/no, spoken reply, accessible outline; real contacts (ContactsContract) and dialer (`ACTION_DIAL`) | **Not built yet** (needs the Android SDK) |
| `iosApp/` | SwiftUI app: tap-to-talk on-device speech (SFSpeechRecognizer) or type; real contacts (CNContactStore) and dialer (`tel:`, iOS asks first); XcodeGen project | **Not built yet** (needs a Mac with Xcode) |

The apps use the phone's real contacts and dialer. The calendar is still demo
data until slice 2, and every reply that used it says so. The console uses
fake contacts and a fake dialer, also labelled.

Slice 1 is not done until the checks in
[`docs/SLICE-1-DEVICE-TEST.md`](docs/SLICE-1-DEVICE-TEST.md) pass on real
Android and iOS phones.

## Run it

Needs JDK 17 or later.

```bash
./gradlew :shared:jvmTest                                     # all shared tests
./gradlew :cli:installDist
cli/build/install/cli/bin/cli                                 # typed driver
cli/build/install/cli/bin/cli --alexa                         # simulated Alexa, prints the JSON
cli/build/install/cli/bin/cli --events                        # also print run events
```

Try: `call Mom`, then `yes`; `call`, then `Dad`; `what's on my calendar today`.

**Android:** set `sdk.dir` in `local.properties` (or `ANDROID_HOME`), open in
Android Studio, run `androidApp`. Library versions in
`androidApp/build.gradle.kts` were not checked against a real build.

**iOS (macOS only):** `brew install xcodegen && cd iosApp && xcodegen`, then
open `NodeTree.xcodeproj`. The pre-build step compiles the shared core into
`NodeTreeShared.framework`. iOS targets are declared only on macOS.

## Drivers, and swapping in Alexa

A driver turns its input into a `DriverRequest` and renders the
`DriverReply` (`Say`, `Ask`, `AskConfirm`, `Progress`, `End`). It never
plans or calls apps; `Conductor` does.

- `ConsoleDriver`: typed text, for tests and the console.
- `SimulatedAlexaDriver`: builds the JSON Alexa would send (one catch-all
  intent with a `query` slot, plus the built-in yes/no/stop/help intents) and
  runs it through `AlexaSkillHandler`, the same handler the real skill will use.
  It enforces `AlexaRules` (reply length, response time budget with a
  progress reply). **Those limits are placeholders: check them against
  Amazon's docs when access arrives.**
- Real Alexa (later): host an HTTPS endpoint next to Elastic Core that passes
  the request body to `AlexaSkillHandler.handle` and returns its JSON. Then
  subclass `DriverContractTest` to run the same 11 behaviour tests against it.

## Design

See the design doc for principles, the tree model and the build plan. Slice 0
(the skeleton) is done. Slice 1 (real contacts and dialer, on-device voice) is
written and unit-tested in shared code; it needs the device checks above.

Contact matching is deliberately strict (`ContactMatcher`): exact full name,
then exact first name, then a name starting with the words said. Two matches
at the same level means asking for the full name; a name that only *contains*
the words is never dialed.
