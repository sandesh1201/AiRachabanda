# Implementation roadmap

Live inference stays unauthorized until a milestone explicitly names the provider and the request.

## Done

- Milestone 002: feasibility findings, accepted as partial.
- Milestone 003: Android foundation, local conversations, assisted Claude and ChatGPT, zero-cost policy. Accepted as partial.
- Milestone 003A: phone display restored by clearing the guest size override. Devil's Advocate role specified. Assisted Claude critique prompt added. Accuracy is not claimed.
- Milestone 004: portrait display rechecked at 1080×2424 and 420 dpi. Android Keystore AES-256-GCM credential store. Room schema version 2 migration. Orchestration engine and dynamic roles. The visible critique action still uses the assisted-task path. Accuracy is not claimed. No live request was sent.

## Next

Milestone 005 — connect the visible council actions to the orchestration engine:

- Keep the Pixel 9a guest at 1080×2424 and 420 dpi. Do not set `display_size_forced`.
- Start, pause, resume, and cancel a session from the existing council screen without adding a new navigation structure.
- Keep assisted participants from blocking an automated participant, and keep automated requests blocked until billing eligibility is verified.
- Show recorded claims, objections, and unresolved objections from the local database.
- ChatGPT `/v1/responses` stays disabled. Do not perform a live provider call unless a later milestone names the provider and the request.

## Later

- Automated Devil's Advocate rounds for a provider whose billing state is `VERIFIED_FREE` or `INCLUDED_SUBSCRIPTION_VERIFIED`, inside the remaining request budget.
- Rebuttal routing back to the original participant.
- Final answers that retain unresolved objections.
- The held-out accuracy benchmark.
- Folded and unfolded Galaxy Z Fold 6 verification on hardware or a foldable emulator.
- Document generation, attachments, and notifications.
