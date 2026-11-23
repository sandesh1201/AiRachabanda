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

The visible **Prepare Claude critique** action still uses the assisted-task path. It was exercised on the phone. The engine is covered by unit tests and is not yet the path behind that button.

## Roles

Roles are Moderator, Researcher, Analyst, Domain Specialist, Devil's Advocate, Evidence Verifier, and Final Synthesizer.

Assignment uses only question complexity and required capabilities:

- Low complexity requires research and synthesis.
- Moderate complexity adds analysis and critique.
- High complexity adds domain review and evidence verification.
- A caller can add a capability. Low complexity plus critique assigns a Devil's Advocate.
- The Devil's Advocate is a different participant from the final synthesizer when more than one participant is eligible.
- A moderator is assigned whenever the session has a participant.

The Milestone 004 instruction ended at "Required capabilities," so no further assignment factors were added.
