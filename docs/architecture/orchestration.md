# Orchestration and credential storage

Milestone 004 adds the local workflow foundation. It does not send a provider request, and it does not claim that a critique is more accurate.

## Credentials

`AndroidKeystoreGateway` stores one AES-256-GCM ciphertext per provider account in the app's private files directory. The key lives in Android Keystore under `airachabanda.{provider}.{account}`. The cipher generates a 12-byte initialization vector for each write. Authentication uses a 128-bit GCM tag. The key specification does not require biometric authentication or an unlocked device, because a later background research task must be able to run after the user has opted in.

A missing key is reported as invalidated. A failed authentication tag is reported as corrupted. Neither case returns plaintext or writes a fallback copy. Replacement writes a new envelope. Deleting an account choice of "Not configured" deletes that provider's default credential. Deleting local content deletes every stored credential. Secrets are not written to Room, DataStore, log messages, or this repository.

## Discussion record

Room schema version 2 adds participants, role assignments, model identities, discussion rounds, claims, evidence references, objections, rebuttals, verification results, preliminary and final conclusions, workflow checkpoints, and request-budget entries. Existing version 1 conversations and imported responses stay in place through `Migrations.MIGRATION_1_2`. There is no destructive fallback.

Model identity is `unknown` until a response actually supplies a name. That supplied name is stored as user-declared. `verifiedModelId` stays `unknown`, and `independentlyVerified` stays false, until a later milestone has a real verification source. The application does not invent a model name.

## Engine

`OrchestrationEngine` is constructed with the application database. Its session states are separate from task states. A pending assisted task does not move the session out of independent research while an automated task is still ready. Automated tasks are created only when `ZeroCostPolicy` allows that provider and the remaining request budget can pay for one request. ChatGPT and Claude are never automated under the current policy.

The engine can accept a question, select eligible participants, assign roles, create research tasks, record a supplied response as a claim, request a critique, record an objection, request a rebuttal, record verification, reassess within the round limit, and record a final conclusion without deleting earlier conclusions or open objections. Pause, resume, cancel, and a deadline stop the session. Follow-up questions are refused once the round limit is reached. Each transition writes a checkpoint. The engine does not loop without a round-limit check, and one `advance` call takes at most eight internal steps.

Milestone 004A routes the Council screen through `CouncilWorkflow`. Start, participant selection, assisted prompts, imports, critique, objections, rebuttals, verification, synthesis, pause, resume, and stop call the engine. An import that belongs to a task is stored once, as user-supplied and not authenticated.

## Roles

Roles are Moderator, Researcher, Analyst, Domain Specialist, Devil's Advocate, Evidence Verifier, and Final Synthesizer. A role is chosen for the session. It is not reserved for one provider.

Assignment considers question complexity, required capabilities, which providers the user selected, whether a participant is assisted, a known numeric quota of zero for an automated participant, the remaining request budget, claim authors, and verified model identity. A null quota is unknown and does not exclude anyone. An unknown model identity stays `unknown` and is not an independent review. Two providers with the same verified model are not independent reviewers.

Standard and Deep Research require a critique capability. Quick mode adds it when an assisted reviewer is available or the automated budget can pay for one. If no reviewer can be assigned, the session says so and does not invent an objection.

## Actions and budget

`CouncilProtocol` accepts only the six workflow actions: additional research, a challenge, verification, a rebuttal, a correction, and a synthesis recommendation. Every action is untrusted. The engine checks the session state, the actor's role, a non-empty body, and the remaining request budget before it writes the action. An accepted action does not change billing, credentials, or the budget ceiling, and it is not executed as code.

Research and follow-up tasks leave one automated request unused for an automated Devil's Advocate and one for an automated final synthesizer when those roles are automated. Assisted critique and synthesis do not spend the automated budget. The session also has a 60-minute local limit. Provider quotas are not invented.

## Milestone 004B

`OpenRouterDispatch` stores a stream only when its disposition is completed, truncated, or interrupted, and only when a generation id is present. A completed stream can become a message. A truncated or interrupted stream stays in `provider_completions` with that disposition and does not become a claim or a final answer. A second insert of the same generation id is a duplicate and does not consume the request budget again. Cancellation, failure, and an unknown outcome are not retried. Production inference authorization stays off, so `dispatchTask` still returns a policy refusal and writes nothing until a later milestone allows a call. Metadata reads use a separate confirmation and never call the chat endpoint.

`PreparedProviderDispatch` uses the same storage rules for Cloudflare and Gemini. Production authorization for both is off, so `dispatchPrepared` does not call the network. Two hosts of one verified model stay a same-model review.

Milestone 005D classifies each provider as a free automated API, free assisted chat, local inference, or unavailable. Production does not treat any API as protected, so active participants are assisted or omitted. A waiting assisted task does not block an unrelated automated task. Assisted prompts are framed as on-device preparation, and an import stays user-supplied. Cloudflare is free only when Workers Free is positively identified. Gemini billing is accepted only when the credential is bound to the project that was read. Neither check is sent by this build.

Milestone 005E keeps that rule. A Gemini binding accepts the project returned by key lookup and rejects a different typed project ID. Cloudflare product plan id `free` and entitlement feature keys do not name Workers Free. A local participant can be prepared only for an explicit loopback model with cloud execution disabled, and the dispatcher still does not send it. Two different verified local model ids are a cross-model review, and that label says agreement is not proof. Assisted ChatGPT and Claude stay available.

Milestone 005F prepares one Gemini request for `gemini-3.5-flash-lite` with a 128-token cap and no tools. The request stays unsent until metadata consent, inference consent, a ready OAuth token, and a looked-up project with billing disabled are all present. Android local traffic uses adb reverse to device loopback. The emulator alias `10.0.2.2` is rejected, and cleartext stays off.

Milestone 005G removes the permanent release flags. `GeminiOwnerSession` sends lookup and billing after metadata consent, then one generation after a second consent, a fresh billing read, and a model that is both listed with `generateContent` and present on the documented free-tier text list. Council dispatch still uses `GeminiAuthorization.PRODUCTION` and does not call the network.

Review labels are `SELF_CRITIQUE`, `SAME_MODEL_REVIEW`, `CROSS_MODEL_REVIEW`, and `INDEPENDENCE_UNVERIFIED`. The same participant reviewing its own role is reduced verification. Different hosts of the same verified model are not independent. Unknown or user-declared identity cannot establish independence. Cross-model agreement is not proof.

One OpenRouter account can hold several eligible catalog models. Each participant key is the provider plus the model id. The production source still emits one participant per configured provider. It does not invent model ids. Room schema version 4 adds `capabilityNote` and `provider_completions` through `Migrations.MIGRATION_3_4`.
