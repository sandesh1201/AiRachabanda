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
- Milestone 005F: Gemini consent and the single free-tier request are prepared. They stay unsent until the owner registers an Android OAuth client and a looked-up project has billing disabled. Local traffic uses adb reverse. Cleartext stays off.
- Milestone 005G: Android AuthorizationClient can obtain a read-only token. Metadata and one free-tier generation run only after in-app confirmation, key binding, and billingEnabled false. No live response has been received until the owner finishes Google configuration.
- Milestone 006A: The Council can run an assisted discussion from stored responses. Participant requests, objections, and the recorded synthesis do not contact a provider.
- Milestone 006B: Discussion planning, task dependencies, and parallel assisted work are recorded on the device. No live provider request is added.
- Milestone 007: Claim records, local arithmetic, objection history, and evidence notes are stored on the device. Live provider requests and live web retrieval remain off.
- Milestone 008: Public research can use Wikimedia, arXiv, and the Federal Register after consent. A retrieved page is not treated as proof. No AI inference request is added.

## Next

Gemini account setup can be finished later. Council work continues with imported responses until a verified free provider is authorized.

## Later

- Automated Devil's Advocate rounds for a provider whose billing state is `VERIFIED_FREE` or `INCLUDED_SUBSCRIPTION_VERIFIED`, inside the remaining request budget.
- Rebuttal routing back to the original participant.
- Final answers that retain unresolved objections.
- The held-out accuracy benchmark.
- Folded and unfolded Galaxy Z Fold 6 verification on hardware or a foldable emulator.
- Document generation, attachments, and notifications.
