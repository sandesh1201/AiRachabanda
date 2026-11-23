# Test plan

## Automated tests in this repository

`./gradlew :app:testDebugUnitTest` runs the JVM tests.

Policy tests cover zero-cost blocking, the OpenRouter free-route rule, the prepared ChatGPT authorization URL, the disabled live token exchange, assisted import rejection, log redaction, and the Devil's Advocate decision.

Devil's Advocate checks:

- A conversation with no provider response does not produce a critique.
- A provider response permits an assisted Claude critique and does not permit automated review.
- Deep Research plans three rounds and still blocks automation when the budget is zero.
- Custom depth zero discloses reduced verification and prepares nothing.
- The Claude prompt names the discussion round, quotes the source material, forbids invented evidence, and states that the review is not independent verification.

Repository tests cover conversation rename and delete, a declared paid plan that does not enable automation, an imported response marked user-supplied, a skipped task that does not invent a message, and account disconnection that deletes only that provider's credential.

Credential tests use synthetic secrets. They cover AES-256-GCM encryption, a new initialization vector on replacement, deletion, provider isolation, a restart against the same key and files, tampered ciphertext, a missing key, and concurrent replacement. A Robolectric check confirms the Android Keystore key specification is 256-bit AES-GCM and does not require biometric or device-unlock authentication. The unit tests drive the production envelope through an in-memory key source. They do not call the AndroidKeyStore provider.

Migration tests open a version 1 database that already contains a conversation, message, assisted task, and imported response. After migration through version 3, those rows remain, model identity columns are `unknown`, and the discussion tables, including `protocol_actions`, exist. Room rejects the database if the migrated schema does not match. Production does not use a destructive migration fallback.

Orchestration tests cover role assignment from complexity and an added critique capability, an automated participant that proceeds while an assisted participant is waiting, ChatGPT remaining ineligible for automated budget use, a two-round critique that stops at the round limit and preserves an open objection, and pause, resume, cancel, deadline, and follow-up limits.

Council workflow tests cover the assisted path from the same engine the Council screen uses: start, import, critique, an open objection, a rebuttal request, pause, resume, and recovery from a closed database. They also check that two hosts of the same verified model are not treated as independent reviewers, that an unknown model stays unknown, that research tasks leave automated capacity for review and synthesis, and that a protocol action cannot increase the request budget.

## Device checks for Milestone 004A

On the same Pixel 9a guest, with the 0.4.1 debug build installed:

- `wm size` reports physical 1080×2424 and no override.
- `wm density` reports physical 420 and no override.
- The conversation titled "Milestone 004A test" is created in History and opened in portrait.
- Start discussion assigns Claude and shows that only assisted providers are available.
- An imported test response and an imported test critique are labeled user-supplied and not authenticated.
- One objection stays unresolved after a rebuttal is requested.
- Pause and resume keep that objection.
- After the process is stopped and the app is opened again, the same stage, roles, pending task, and unresolved objection are still on screen.
- This pass uses typed test text. It is not live AI research.

## Device checks for Milestone 004

On the same Pixel 9a guest, after boot and again after the critique check:

- `wm size` reports physical 1080×2424 and no override.
- `wm density` reports physical 420 and no override.
- `display_size_forced` is unset and `display_density_forced` is null.
- Window Manager reports `sw411dp w411dp h923dp 420dpi port` and bounds `Rect(0, 0 - 1080, 2424)`.
- The installed package is versionName 0.4.0, versionCode 4.
- AI Council in portrait shows the bottom navigation bar and the enabled **Prepare Claude critique** action.
- Tapping that action creates a round 2 assisted task whose prompt is a Devil's Advocate prompt quoting the imported Claude material. The confirmation says Claude is not researching inside the app.

## Device checks for the phone display

On a portrait phone window of 1080×2424 at 420 dpi (411×923 dp):

- Home, AI Council, Activity, History, and Settings fill the window.
- Navigation is the bottom bar.
- An open conversation keeps the bottom bar.
- The keyboard resizes the conversation and leaves the message field visible.
- Rotation to landscape uses the rotated phone metrics (923×411 dp), the navigation rail, and the two-pane council.

Android Settings is checked on the same display so a guest-wide size override is not mistaken for an application layout bug.

## Device checks for Milestone 004B

On the same Pixel 9a guest, with the 0.4.2 debug build installed:

- `wm size` reports physical 1080×2424 and no override.
- `wm density` reports physical 420 and no override.
- `display_size_forced` is null.
- Settings, OpenRouter shows connection status, billing eligibility, empty model discovery, masked key entry, and a disabled Verify connection action.
- A synthetic key typed in that field is shown as dots. After Save credential, the screen says a credential is stored and does not show the key. Remove credential clears it.
- The three Android Keystore instrumentation tests ran on this emulator and passed. They used synthetic secrets only.
- This pass does not call OpenRouter.

## Not yet run

- A live OpenRouter metadata request.
- A live inference request.
- Galaxy Z Fold 6 folded and unfolded.
- The accuracy benchmark in `docs/architecture/devils-advocate.md`.
- A live provider call. None is authorized.
