# Implementation roadmap

Live inference stays unauthorized until a milestone explicitly names the provider and the request.

## Done

- Milestone 002: feasibility findings, accepted as partial.
- Milestone 003: Android foundation, local conversations, assisted Claude and ChatGPT, zero-cost policy. Accepted as partial.
- Milestone 003A: phone display restored by clearing the guest size override. Devil's Advocate role specified. Assisted Claude critique prompt added. Accuracy is not claimed.
- Milestone 004: portrait display rechecked at 1080×2424 and 420 dpi. Android Keystore AES-256-GCM credential store. Room schema version 2 migration. Orchestration engine and dynamic roles. Accuracy is not claimed. No live request was sent.
- Milestone 004A: Council actions use the orchestration engine. Room schema version 3. Dynamic roles, critical review, validated workflow actions, and request budgeting. Assisted workflow exercised on the portrait phone. Accuracy is not claimed. No live request was sent.
- Milestone 004B: Android Keystore instrumentation on the emulator. Fail-closed OpenRouter adapter and configuration screen. Reviewer classifications. Room schema version 4. No live metadata request and no live inference.
- Milestone 005A: Stream outcomes distinguish truncation from completion. In-app confirmation can call the OpenRouter key and models endpoints. Chat completions stay off. Room schema version 5.

- Milestone 005B: OpenRouter metadata was not sent because no credential was stored. Billing safeguards were re-read. Cloudflare Workers AI and the Gemini API Free tier are the next provider candidates. Inference stays off.
- Milestone 005C: Cloudflare and Gemini adapters, configuration screens, and provider-specific billing policies. Live metadata and live inference stay off.
- Milestone 005D: Free chat is assisted for providers that publish an official chat. Free APIs stay off until billing protection is positively established.
- Milestone 005E: Gemini key lookup and project billing are prepared and not sent. Cloudflare still cannot positively name Workers Free. A loopback local provider is prepared. No runtime, model download, or live request.

## Next

The shortest automated path is a loopback model after the owner installs a local runtime and downloads one small model. Gemini can be the cloud path only after an owner-consented lookup shows the key's own project has billing disabled. No live inference is authorized.

## Later

- Automated Devil's Advocate rounds for a provider whose billing state is `VERIFIED_FREE` or `INCLUDED_SUBSCRIPTION_VERIFIED`, inside the remaining request budget.
- Rebuttal routing back to the original participant.
- Final answers that retain unresolved objections.
- The held-out accuracy benchmark.
- Folded and unfolded Galaxy Z Fold 6 verification on hardware or a foldable emulator.
- Document generation, attachments, and notifications.
