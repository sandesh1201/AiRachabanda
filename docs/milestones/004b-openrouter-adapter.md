# Milestone 004B — Android Keystore and OpenRouter preparation

Marker `END-004B-INSTRUCTIONS` was in the milestone prompt. Baseline commit `ff4018a4ec4c9920a6cfa7b620b7e2e6612ab3c1`. Version 0.4.2, versionCode 6. No live OpenRouter request was sent.

## Android Keystore

`AndroidKeystoreCredentialTest` ran on the Pixel 9a emulator and used the `AndroidKeyStore` provider. The secrets were synthetic. Three tests passed:

- Create a key, encrypt and decrypt with AES-GCM, replace the credential, keep OpenRouter and Groq separate, delete one account, and read the surviving ciphertext with a new gateway and a new key source.
- Reject a flipped ciphertext as corrupted, reject a deleted keystore alias as invalidated, and keep a bearer token and a secret field out of the redacted log line.
- Deleting one account leaves the other account's credential in place.

The restart check opens a new keystore session against the same private directory. It does not kill the instrumentation process, because that process cannot resume the same test method.

## OpenRouter adapter

`OpenRouterAdapter` builds requests for the documented endpoints and classifies responses. Production authorization has `metadataChecksAuthorized` and `inferenceAuthorized` set to false. `complete()` returns the built request and does not call the transport. Tests use a fake transport and local fixtures. There is no production mock reply.

Eligible ids are exactly `openrouter/free` or a catalog id that ends in `:free` with no extra suffix. Pricing must include prompt and completion, and every present price must be zero. Missing pricing is rejected. The request sets `provider.allow_fallbacks` to false and `provider.max_price` prompt and completion to 0. It sends one model id, one user message, and `stream: true`. It does not send a `models` array, tools, or plugins.

A stream counts as complete only when it ends with `data: [DONE]`, includes a usage object, and has finish reason `stop` or `length`. Anything else stays incomplete and is not stored as a finished answer. HTTP 401, 402, 403, and 429 map to authentication, payment required, budget blocked, and rate limit. A rate-limit response is not retried. Cancellation and timeout are not stored and are not retried. The same generation id is stored once.

## Official pages re-read on 23 November 2026

- https://openrouter.ai/docs/api/api-reference/api-keys/get-current-api-key
- https://openrouter.ai/docs/api_reference/limits
- https://openrouter.ai/docs/api/api-reference/models/list-all-models-and-their-properties
- https://openrouter.ai/docs/guides/routing/model-variants/overview
- https://openrouter.ai/docs/guides/routing/model-variants/free
- https://openrouter.ai/docs/api/api-reference/chat/create-a-chat-completion
- https://openrouter.ai/docs/api_reference/streaming
- https://openrouter.ai/docs/guides/routing/provider-selection
- https://openrouter.ai/docs/guides/features/plugins

`GET /api/v1/key` is metadata. A null `limit` means unlimited. `free_model_daily_requests` is the daily counter and does not include the per-minute limit. A negative credit balance can return HTTP 402 even for a free model. A `:free` id followed by a paid model in `models` is billed at each model's own price. Account-level plugins can still apply when a request omits `plugins`. The published pages do not prove that a key whose numeric limit is 0 can complete a free-model call.

## Zero-cost enforcement

The adapter rejects paid models, unknown pricing, unverified billing, paid fallbacks, automatic credit purchases, unapproved routing, billable tools or plugins, a request over the observed daily remaining count, and a request over the session budget. A zero credit limit is not treated as proof. Live inference stays off.

## Multiple models and review labels

`participantsFor` can return several eligible catalog models for one OpenRouter account. Each has its own participant key, provider, model id, role grant, and capability note. Production does not invent those ids. Paid catalog rows are dropped.

Review labels are `SELF_CRITIQUE`, `SAME_MODEL_REVIEW`, `CROSS_MODEL_REVIEW`, and `INDEPENDENCE_UNVERIFIED`. A single assisted participant that also holds Devil's Advocate is reduced verification. Different hosts of the same verified model are not independent. Unknown or user-declared identity cannot establish independence. Cross-model agreement is not proof.

## Tests and device

Unit tests: 28, 0 failures, including five OpenRouter adapter tests. Android lint: no errors or warnings. Instrumentation: 3 tests on Pixel_9a, 0 failures. Portrait remains 1080×2424 at 420 dpi, with `display_size_forced` unset. Screenshots are in `docs/milestones/004b-screenshots/`. The synthetic key used for the masked-input screenshot was removed before this report.

## Project owner instructions

1. Create an OpenRouter account yourself at https://openrouter.ai. Do not paste any key into Cursor chat.
2. Create an API key with a custom credit limit of 0. Do not buy credits. Do not enable automatic credit purchases. If the console will not accept a limit of 0, do not use that key in this app.
3. Confirm the account credit balance is not negative. The limits page says a negative balance can reject even a free model.
4. In OpenRouter privacy settings, disallow providers that may train on free-model prompts. Leave web search and other plugins off.
5. On the phone, open Settings, then OpenRouter. Type the key into the masked field and tap Save credential. The app stores it in Android Keystore and does not show it again.
6. Leave Verify connection disabled. A metadata-only check is a separate authorization. This build will not send `GET /api/v1/key` or `GET /api/v1/models` until that authorization exists.
7. Do not start a free inference test yet. The first such test waits until a metadata check records a numeric limit of 0, a positive daily remaining count, and at least one catalog id with all-zero pricing, and until a later milestone explicitly authorizes one completion.

## Proposed Milestone 005

Not started. After the owner authorizes it, the app may call `GET /api/v1/key` and `GET /api/v1/models` with the stored key and record `limit`, `limit_remaining`, `free_model_daily_requests`, and which returned ids have all-zero pricing. If `limit` is null or not zero, if the balance path reports a block, if pricing is missing or non-zero, or if plugin defaults are unknown, inference stays off. A chat completion is out of scope until that metadata exists and a later instruction names one exact free model id.
