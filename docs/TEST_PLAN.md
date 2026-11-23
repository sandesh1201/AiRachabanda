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

Repository tests cover conversation rename and delete, a declared paid plan that does not enable automation, an imported response marked user-supplied, and a skipped task that does not invent a message.

## Device checks for the phone display

On a portrait phone window of 1080×2424 at 420 dpi (411×923 dp):

- Home, AI Council, Activity, History, and Settings fill the window.
- Navigation is the bottom bar.
- An open conversation keeps the bottom bar.
- The keyboard resizes the conversation and leaves the message field visible.
- Rotation to landscape uses the rotated phone metrics (923×411 dp), the navigation rail, and the two-pane council.

Android Settings is checked on the same display so a guest-wide size override is not mistaken for an application layout bug.

## Not yet run

- Instrumentation tests.
- Galaxy Z Fold 6 folded and unfolded.
- The accuracy benchmark in `docs/architecture/devils-advocate.md`.
- A live provider call. None is authorized.
