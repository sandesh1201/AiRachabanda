# Implementation roadmap

Live inference stays unauthorized until a milestone explicitly names the provider and the request.

## Done

- Milestone 002: feasibility findings, accepted as partial.
- Milestone 003: Android foundation, local conversations, assisted Claude and ChatGPT, zero-cost policy. Accepted as partial.
- Milestone 003A: phone display restored by clearing the guest size override. Devil's Advocate role specified. Assisted Claude critique prompt added. Accuracy is not claimed.

## Next

Milestone 004 — phone regression and one authorized non-inference check:

- Keep the Pixel 9a guest at 1080×2424 and 420 dpi. Do not set `display_size_forced`.
- Add a Room migration for objection records without a destructive fallback.
- Persist underlying model identity when a response actually includes it.
- Instrument the compact window and a real share intent.
- Only with a separate owner authorization, perform one metadata read such as OpenRouter `GET /api/v1/key`. ChatGPT `/v1/responses` stays disabled.

## Later

- Automated Devil's Advocate rounds for a provider whose billing state is `VERIFIED_FREE` or `INCLUDED_SUBSCRIPTION_VERIFIED`, inside the remaining request budget.
- Rebuttal routing back to the original participant.
- Final answers that retain unresolved objections.
- The held-out accuracy benchmark.
- Folded and unfolded Galaxy Z Fold 6 verification on hardware or a foldable emulator.
- Document generation, attachments, and notifications.
