# Milestone 005C — Cloudflare and Gemini adapters

Baseline: `a07be3c88c168101e52b6e22f7a7a44ffea571d0`.

OpenRouter stays installed and fail-closed. ChatGPT and Claude stay assisted. No Worker was deployed, no Google Cloud project was created, and no live metadata or inference request was sent.

## Billing architecture

Each provider has its own policy. An OpenRouter numeric credit limit is not required.

`BillingSnapshot` records authentication, plan, billing eligibility, model eligibility, usage limits, remaining quota, additional-charge protection, verification source, and last verification. `VerificationSource.MANUAL_ONLY` is reserved for a human confirmation and is not produced by either programmatic parser. Unknown or insufficient protection blocks inference. Saving or removing a credential clears the last verification by replacing the stored secret and leaving last verification at Never.

Production authorization for Cloudflare and Gemini has metadata and inference set to false. A prepared request can be built in memory. The transport is not called.

## Cloudflare

Direct REST. No Worker.

- Model search: `GET https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/run` is the inference path. Model discovery is `GET https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/models/search`.
- Plan read: `GET https://api.cloudflare.com/client/v4/accounts/{account_id}/subscriptions`.
- Run: `POST https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/run/{model_name}` with `{"prompt","stream":true,"max_tokens":128}` and `Authorization: Bearer`.
- Token permissions for Workers AI: Workers AI Read and Workers AI Edit. The subscriptions read also needs Billing Read.
- A Workers AI token cannot prove Workers Free. Absence of a successful Billing Read stays `PlanState.UNKNOWN` and blocks inference.
- `rate_plan.id` of `WORKERS_PAID` or a `PARTNERS_WORKERS` plan is paid and blocks inference.
- If Billing Read succeeds and no paid Workers plan is listed, the Workers pricing page says the default plan is Free and further operations fail after 10,000 Neurons per day instead of charging. Remaining Neurons are still unknown, and this build does not send the request.
- Models named on the Workers AI pricing page on 23 November 2026 as requiring a paid billing method or prepaid AI Gateway credits are rejected. The list is dated. Absence from that list is not a live catalog.
- The adapter does not send an AI Gateway id.

## Gemini

- Model list: `GET https://generativelanguage.googleapis.com/v1beta/models` with header `x-goog-api-key`. The key is not placed in the URL.
- Stream: `POST https://generativelanguage.googleapis.com/v1beta/models/{model}:streamGenerateContent?alt=sse`. The body is `contents` only. No tools, grounding, search, or cached content.
- A successful model list does not set free-tier eligibility. The parser leaves `freeTierKnown` false.
- Billing linkage is `GET https://cloudbilling.googleapis.com/v1/projects/{projectId}/billingInfo`. The minimum permission is `billing.resourceAssociations.get`. A Gemini API key cannot call that API. This build does not request the permission and does not call it.
- `billingEnabled: false` is the documented sign that the project has no linked billing account. It is parsed only from a fixture in tests.
- Free-tier prompts and responses may be used to improve Google products. The Gemini screen says so.
- `finishReason` `MAX_TOKENS` is truncated. A stream that ends before `STOP` is interrupted. Neither becomes a completed claim.

## Owner setup

### A. Cloudflare Workers AI on Workers Free

1. Sign in at https://dash.cloudflare.com/. Do not add a payment method.
2. Open Workers AI at https://dash.cloudflare.com/?to=/:account/ai/workers-ai and confirm the account is Workers Free, not Workers Paid. The Workers pricing page is https://developers.cloudflare.com/workers/platform/pricing/. Workers AI pricing is https://developers.cloudflare.com/workers-ai/platform/pricing/.
3. Create a token from the Workers AI “Use REST API” path, or at https://dash.cloudflare.com/profile/api-tokens. Use the Workers AI API token template. If the token is custom, grant only Workers AI Read and Workers AI Edit. Add Billing Read only when a later milestone authorizes the subscriptions read.
4. Copy the Account ID from the Workers AI overview. It is 32 hexadecimal characters.
5. In AI Rachabanda, open Settings, then Cloudflare Workers AI. Enter the Account ID and token there. Do not paste them into chat.
6. Leave the account on Workers Free. Do not buy AI Gateway credits and do not set Unified billing.
7. Verify connection explains the two GET endpoints and does not send them in this build.
8. Remaining limit: the Workers AI REST API does not return the plan or the remaining Neuron count. Billing Read can show a paid Workers subscription. It does not, by itself, return “Workers Free” as a dedicated field.

### B. Gemini API without linked billing

1. Open https://aistudio.google.com/apikey. Create a key in a Google Cloud project that has no linked billing account. Do not choose Set up billing and do not prepay.
2. Billing behavior is documented at https://ai.google.dev/gemini-api/docs/billing and https://aistudio.google.com/docs/billing. The Free tier is the default when no Cloud Billing account is linked. Linking a billing account moves the project to a paid tier.
3. The API key needs access only to the Gemini API. Do not grant Cloud Billing access for this build.
4. In AI Rachabanda, open Settings, then Gemini API. Enter the key there. Do not paste it into chat.
5. Do not enable Grounding, Google Search, caching, or tools. The adapter does not send those fields.
6. Privacy: on the Free tier, Google may use prompts and responses to improve its products. The configuration screen states that.
7. Verify connection names the model-list endpoint and the Cloud Billing endpoint. It does not send either request. A successful key check would still not prove that billing is unlinked.

## First live inference recommendation

Cloudflare Workers Free is the first provider to authorize later. On that plan, the documented behavior after 10,000 Neurons per day is an error, not an overage charge. Gemini remains second because an API key cannot show that billing is unlinked, and Free-tier content may be used to improve Google products. Neither is enabled in this build. The owner still has to confirm Workers Free in the dashboard, and a later milestone has to authorize one Billing Read before inference.
