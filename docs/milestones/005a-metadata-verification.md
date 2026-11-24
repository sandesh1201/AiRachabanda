# Milestone 005A — Metadata confirmation and stream status

Marker `END-005A-INSTRUCTIONS` was in the milestone prompt. Baseline commit `086228c183e69a53e20a4071f307e202eb687fe8`. Version 0.5.0, versionCode 7.

No live OpenRouter request was sent. A key pasted into Cursor chat was not stored, logged, or used. The owner must revoke that key and enter a replacement only in the app.

## Streaming

A finish reason of `length` is `TRUNCATED`. A stream that starts and does not reach a completed `stop` response is `INTERRUPTED`. An empty body or a timeout is `OUTCOME_UNKNOWN`. Cancellation is `CANCELLED`. HTTP errors and malformed chunks are `FAILED`. Only `stop` plus a usage object plus `data: [DONE]` is `COMPLETED`.

Truncated and interrupted text can be stored in `provider_completions` with that disposition. It is not inserted as a council message and is not promoted into a claim or a final answer. The same generation id is stored once and consumes the session budget once. Unknown outcomes are not retried. Room schema version 5 adds the disposition column through `Migrations.MIGRATION_4_5`.

## Metadata confirmation

Verify connection is enabled after a credential is saved. It opens a confirmation that names OpenRouter, `GET https://openrouter.ai/api/v1/key`, and `GET https://openrouter.ai/api/v1/models`. The dialog says the stored key authenticates the request, that no conversation or research prompt is sent, and that no inference is performed. Cancel sends nothing. Save credential does not send a request. Startup does not send a request.

The production chat adapter still has inference unauthorized. The consented adapter used after confirmation can call only the two GET endpoints. `POST /api/v1/chat/completions` stays off.

## Account and model rules

Parsed key fields are the documented ones: `limit`, `limit_remaining`, `limit_reset`, `free_model_daily_requests` (`limit`, `remaining`, `used`), `is_free_tier`, `usage`, `usage_daily`, `usage_weekly`, `usage_monthly`, `expires_at`, `is_management_key`, `is_provisioning_key`, and the deprecated `rate_limit.requests`. A missing remaining count is shown as Unknown. The daily limit is not copied into the remaining count. `is_free_tier` is not zero-cost eligibility. A null `limit` means unlimited and is blocked. A non-zero limit is blocked. A numeric zero limit stays `ZERO_COST_ELIGIBILITY_UNVERIFIED`.

Model rows record id, name, `canonical_slug`, context length, input and output modalities, supported parameters, and pricing. A row is eligible only when prompt and completion prices are present and every pricing value is numeric zero. The `:free` suffix is not enough. `openrouter/free` and the `:online`, `:nitro`, `:floor`, and `:exacto` shortcuts are excluded. No catalog is hardcoded.

## Plugins

The plugins guide documents `plugins: [{ "id": "...", "enabled": false }]` as the per-request way to turn off an account default. The ids named in the official guides used here are `web`, `response-healing`, and `context-compression`. Omitting the array does not disable defaults. Prevent overrides cannot be read from `GET /api/v1/key`, and that response has no plugin field. Manual plugin settings are labeled as manual, not as programmatic verification. The prepared chat body would send those three disables plus `allow_fallbacks: false` and `max_price` 0, but this milestone does not send it. Inference stays blocked.

## Credential restart

`KeystoreProcessRestartTest` stored a synthetic secret, read it in a later instrumentation process with a different process id, then deleted that account. The AndroidX test orchestrator starts each method in a new process, and `clearPackageData` is false. Six instrumentation tests passed on Pixel 9a. The synthetic UI key used for screenshots was removed afterward. The screen returned to `NOT_CONFIGURED`.

## Official pages re-read on 23 November 2026

- https://openrouter.ai/docs/api/api-reference/api-keys/get-current-api-key
- https://openrouter.ai/docs/api/api-reference/models/list-all-models-and-their-properties
- https://openrouter.ai/docs/guides/features/plugins
- https://openrouter.ai/docs/guides/features/message-transforms
- https://openrouter.ai/docs/api_reference/limits
- https://openrouter.ai/docs/guides/routing/model-variants/free
- https://openrouter.ai/docs/guides/routing/provider-selection

## Tests

Unit tests: 29, 0 failures. Android lint: no errors or warnings. Instrumentation: 6 tests, 0 failures. Portrait remained 1080×2424 at 420 dpi. Screenshots are in `docs/milestones/005a-screenshots/`.

## Project owner actions

1. Revoke the OpenRouter key that was pasted into Cursor chat. Treat it as exposed. Do not paste a replacement into chat.
2. Create a new API key with a custom credit limit of 0. Do not buy credits and do not enable automatic credit purchases. If the console will not accept 0, do not use that key.
3. Confirm the account credit balance is not negative.
4. In OpenRouter privacy settings, disallow providers that may train on free prompts. In Settings, Plugins, turn off web search, PDF processing, response healing, and any other plugin. Leave Prevent overrides off only if you want a later request to be able to send `enabled: false`. This manual step is not a programmatic proof.
5. On the phone, open Settings, then OpenRouter. Type the new key into the masked field and tap Save credential.
6. Tap Verify connection, read the confirmation, and tap Send metadata check only if you want those two GET requests sent. Cancel sends nothing.
7. Do not expect chat completions to turn on. A zero limit, a successful authentication, and a model list do not establish zero-cost eligibility while plugin defaults cannot be read from the key endpoint.

## Proposed first inference test

Not started. After the in-app metadata check has recorded a numeric limit of 0, a known positive daily remaining count, and at least one explicit catalog id whose pricing fields are all zero, a later milestone may authorize one streamed completion to that exact id. The request must set provider fallbacks off, max price 0, and the documented plugin disables. It must not use `openrouter/free`, a `models` array, tools, or a paid substitute. A `length` finish is truncated, not final. An unknown outcome is not retried.
