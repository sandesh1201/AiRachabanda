# Milestone 004A — Council engine connection

Status: partial. The Council screen now uses the local orchestration engine. No live provider request was sent, and this pass is not live AI research.

Baseline commit: `e418e1d01644a954bb2047f8970bd627af18f8fe`.

## What the Council screen does

`CouncilWorkflow` is the only path behind the Council actions. It reads and writes the same Room session, tasks, claims, objections, and protocol records as `OrchestrationEngine`.

The portrait Council screen shows the persisted stage, participants, roles, completed and pending tasks, whether the app is waiting for the user, the critical-review note, objection and rebuttal counts, verification, the automated request count, and whether a final answer exists. When every selected provider is assisted, the screen says the user has to submit each prompt and import the response.

## Role assignment

A role is assigned for the session. It is not permanently tied to Claude or ChatGPT. The choice uses question complexity, required capabilities, the providers the user selected, assisted versus automated participation, a known quota only when that quota is a number, the remaining request budget, existing claim authors, and verified model identity. Unknown stays unknown. Two hosts of one verified model are not independent reviewers.

Standard and Deep Research attempt critical review before synthesis. Quick mode attempts it when an assisted reviewer is available or the automated budget can pay for one. If nobody can review, the session says critical review is unavailable and does not invent an objection.

## Budget and protocol

Automated research leaves capacity for an automated critical review and an automated final synthesis. Assisted work spends nothing. A workflow action that asks for more requests than remain is rejected, and the budget is not increased. Actions cannot change billing or credentials.

## Phone check

Pixel 9a guest, physical 1080×2424 at 420 dpi, no forced display size. Installed debug build versionName 0.4.1, versionCode 5.

The conversation "Milestone 004A test" was created in History. Starting the discussion assigned Claude as moderator, researcher, analyst, final synthesizer, and Devil's Advocate, with declared and verified model identity unknown. The screen stated that only assisted providers are available. A typed test response and a typed test critique were imported and labeled user-supplied, not authenticated. One objection stayed unresolved after a rebuttal was requested. Pause and resume kept it. After the process was stopped and the app was opened again, the stage was still Critical review, tasks were 2 completed and 1 pending, and the objection was still unresolved. Screenshots are in `docs/milestones/004a-screenshots/`.

## Checks

Debug APK assembled. Android lint reported no errors or warnings. Unit tests, including the workflow, role, budget, objection, and recovery tests, passed. The version 1 database migration test now also opens schema version 3 and checks `protocol_actions`.

## Still open

Automated inference stays disabled. ChatGPT and Claude are not called. No provider quota was measured. A user-declared model name is not verified. The accuracy benchmark and folded-phone layout were not run. Milestone 004B later prepared OpenRouter without enabling a live request.
