# Milestone 002 — integration feasibility

Status note added in Milestone 003: this report is accepted as partial. The findings below are unchanged.

Date: 23 November 2026.
Scope: investigation only. No accounts were created. No inference request was sent. No application code was written.
Decision: ChatGPT plan usage is a real, documented local OAuth integration. Automated ChatGPT requests stay disabled because the application cannot guarantee that purchased credits will not be consumed. The four free-provider candidates have documented renewable allowances, and each one still needs a verification step before any automated call.

The sentence in the review that began with "Execute" was cut off. The environment audit below covers every item listed before that word.

## 1. ChatGPT plan usage

### Sources read

- https://developers.openai.com/siwc/quickstart
- https://developers.openai.com/siwc/token-sharing-open-source
- https://developers.openai.com/siwc/token-sharing-open-source/sign-in
- https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions
- https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference
- https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations
- https://developers.openai.com/siwc/token-sharing-open-source/errors-and-recovery
- https://developers.openai.com/siwc/token-sharing-open-source/token-reference
- https://developers.openai.com/siwc/request-client-id
- https://developers.openai.com/cookbook/articles/sign-in-with-chatgpt
- https://openai.com/policies/sign-in-with-chatgpt-terms/ (effective 4 November 2026)
- https://help.openai.com/en/articles/20001542-using-your-chatgpt-plan-in-other-apps-and-sites
- https://auth.openai.com/.well-known/openid-configuration

Codex app-server is documented and is out of scope. AI Rachabanda will not embed or impersonate Codex.

### Qualification

| Question | Finding |
| --- | --- |
| Does a locally running native Android app qualify? | Not confirmed. The open-source plan-usage docs cover a local runtime: the app opens the system browser, listens on loopback, and calls `https://api.openai.com/v1` from that runtime. The cookbook also lists "personal projects that run locally." The examples are desktop and self-hosted VM installs. Android is not named. A phone install is a local runtime, which matches the terms requirement that requests originate from the user's local runtime. Qualification for a native Android APK remains untested. |
| Is a public repository or a specific license required? | No specific license identifier is stated in the pages above. The Terms require compliance with "the applicable license," and the fetched terms page did not preserve that link target. Dynamic registration does not ask for a repository URL. Plan usage is described for open-source partners, personal projects that run locally, and selected private clients. A closed APK distributed to other people is outside those stated categories unless OpenAI selects it. Personal local use is the category that can be claimed without a public repository. Redistribution should wait until the project is published as open source or OpenAI approves it. |
| Is prior approval or registration required? | The open-source flow does not use a partner API key or a pre-issued client secret. First sign-in uses `client_id=dynamic_agent_client`. OpenAI returns the issued client ID, of the form `oaiapp_...`, on the callback. `https://developers.openai.com/siwc/request-client-id` is a separate page for integrations that request a client ID. It is not the open-source dynamic flow. Paid or remotely hosted apps use an interest form. `force_reconsent=true` may be sent only after OpenAI confirms deployment for the integration. |
| Which browser mechanism is documented? | The system browser. Authorization URL: `https://auth.openai.com/api/accounts/authorize`. On Android the matching mechanism to test is a Chrome Custom Tab. That test has not been run. |
| Redirect URI | HTTP loopback on `127.0.0.1` only. Example: `http://127.0.0.1:1455/auth/callback`. A later attempt may change the port. Scheme, host, and path stay fixed. `localhost` is not accepted. `/callback` does not match `/auth/callback`. The same URI is sent to the authorize and token endpoints. |
| Loopback on Android | Plausible and untested. The app would bind `127.0.0.1` before opening the Custom Tab, because the phone browser's loopback is the phone. Failure modes to test: the Custom Tab not delivering the query to the local listener, port collision, the listener dying in the background, and Samsung Internet behaving differently from Chrome. A custom scheme or HTTPS App Link is not the documented redirect. It will not be substituted. |
| PKCE | Required. `code_challenge_method=S256`. The challenge is the base64url-encoded SHA-256 of the verifier, without padding. Discovery lists only `S256`. The token endpoint accepts public clients (`token_endpoint_auth_methods_supported` includes `none`). No client secret is stored. |
| State and nonce | Fresh random values for every attempt. `state` is checked before the code is exchanged. `error=access_denied` stops the attempt after `state` is checked. The ID token `nonce` must match the value stored for that attempt. |
| Host ID | Generated and persisted before the first sign-in. One host ID per installation. Opaque. It is not an email, user ID, or credential. Preferred form: `urn:ietf:params:oauth:jwk-thumbprint:...` from RFC 9278. Accepted alternatives: `urn:uuid:<UUIDv4>` and `did:key:`. OpenAI does not verify possession of the private key. Restart and re-login reuse the same host ID. Logout keeps the host ID. The application name sent as `agent_name_hint` is `AI Rachabanda`, on the first registration only, and it is display metadata the user can edit. |
| Token exchange | Form POST to `https://auth.openai.com/api/accounts/oauth/token` with `grant_type=authorization_code`, the issued `client_id`, `code`, `code_verifier`, the same `redirect_uri`, and `resource=https://api.openai.com/v1`. `dynamic_agent_client` is never sent to the token endpoint. A new-registration callback that omits the issued client ID is incomplete. A callback client ID that differs from the saved registration is rejected. |
| ID token | Verify RS256 against `https://auth.openai.com/.well-known/jwks.json`. Issuer `https://auth.openai.com`. Audience is the issued client ID. Check expiration and nonce. `sub` is the account identity. Email and `sub` do not identify the workspace. The issued client ID stays with that verified identity. Discovery's `scopes_supported` lists only `openid`, `profile`, `email`, and `offline_access`. The plan-usage scopes below come from the sign-in guide, which is the authority for this flow. |
| Scopes | Identity: `openid profile email`. Plan usage: `offline_access resource.invoke chatgpt.tokens.use.direct`. Resource: `https://api.openai.com/v1`. A valid ID token without `chatgpt.tokens.use.direct` is sign-in only. Inference stays off. |
| Access token storage | Local, under the user's control. The terms forbid remote or managed storage. Android storage will be app-private and wrapped by the Android Keystore. Tokens are excluded from logs, backups that leave the device, URLs, and source control. |
| Refresh token storage | Same local record as the access token. The refresh token is not in the browser callback. It is replaced atomically with the new access token, expiry, and granted scopes. Refreshes for one session are serialized. Lifetime: access token 3600 seconds; refresh token 30 days, rotated on each successful refresh. The token response also includes `earliest_refresh_at`. The pages read do not define a separate enforcement rule for that field. |
| Renewal | POST `grant_type=refresh_token`, the saved issued `client_id`, the saved `refresh_token`, and `resource=https://api.openai.com/v1`. Omit `scope`. Terminal refresh failures (`invalid_grant`, `invalid_refresh_token`, `token_expired`, `refresh_token_expired`, `refresh_token_invalidated`, `refresh_token_reused`) clear the unusable tokens and require a new authorization with the saved client ID. |
| Logout and revocation | Stop requests. POST the refresh token to the revocation endpoint from discovery, currently `https://auth.openai.com/api/accounts/oauth/revoke`, as form fields `token`, `token_type_hint=refresh_token`, and the issued `client_id`. HTTP 200 with an empty body is success, including for an already-invalid token. Then clear the local access, refresh, and ID tokens. Keep the client mapping and host ID. If revocation is not confirmed, clear the local tokens and tell the user. OpenAI does not notify the app when the user disconnects it in ChatGPT settings. Revocation does not delete the registered client. |
| Model discovery | `GET https://api.openai.com/v1/models` with the same access token. The documented response contains a `models` array. Display entries with `visibility` equal to `list`, in server order. Show `display_name`. Send `slug` as `model`. Refresh the list when the account changes. Example slugs in the docs, including `gpt-6.1-sol` and `gpt-6.2-sol`, are examples. They are not an allowlist and will not be hardcoded. |
| Inference | `POST https://api.openai.com/v1/responses`. Bearer access token. `store` false. `stream` true. `input` is an array. Instructions use the `instructions` field or developer messages. An item with `{type: "message", role: "system"}` is rejected. Success is only the event `response.completed`. `response.failed`, `response.incomplete`, and a stream that ends without `response.completed` are failures. `maxRetries` in the official examples is 0. |
| Background processing | The terms require express consent before background use. Another user's activity must not trigger a request. The Responses field `background` is unsupported and will be omitted. A local notification after work that continues while the app is backgrounded requires that consent. Without consent, the request is cancelled when the app leaves the foreground. |
| Usage limits | The app cannot read the plan balance. ChatGPT Settings, then Usage, is the control surface. For Plus, a five-hour usage total is shared across apps. That five-hour limit does not apply to Pro. An app weekly limit is a cap on the shared allowance, not a separate pool. `subscription_sharing_usage_limit_exceeded` (HTTP 429) pauses plan-usage requests. The app must not invent a reset time from that code. `subscription_sharing_usage_unavailable` (HTTP 503) retries later with bounded backoff. `subscription_sharing_user_not_eligible` (HTTP 403) is not retried and does not loop OAuth. |

### Zero additional charges

The application cannot enforce this by itself.

Documented behavior:

- Included plan allowance and credits are different. The help article says eligible requests use included allowance or applicable credits.
- For Go, Plus, and Pro, apps use available credits after plan limits only when the user has explicitly allowed it. That preference is off by default.
- An app limit below 100 percent prevents credit use for that app. Setting the limit to 100 percent does not, by itself, enable credit use.
- Allowing credit use and buying credits are different actions. If automatic credit purchases are enabled, continued usage can create charges. The help article says the user may not receive a separate notice when included usage runs out.
- No Responses parameter in the pages above tells OpenAI to refuse credit consumption.
- No token claim in the pages above reports whether the user has credit use or automatic purchase enabled.
- `response.completed` does not document a field that distinguishes included allowance from credits.
- OpenAI does not silently move a failed plan-usage request onto an API key. The errors guide tells a coding harness that it may offer an API key as a separate choice. AI Rachabanda will not offer that choice and will not switch to one.

Controls that exist only in the user's ChatGPT account:

1. Leave "allow apps to use credits" off.
2. Set this app's weekly limit below 100 percent.
3. Leave automatic credit purchases off.
4. Use Settings, then Usage, to confirm the saved values.

Because the app cannot read or lock those settings, a completed response can consume credits if the account is configured that way. That violates the zero-additional-cost rule.

**Decision: fail closed.** Automated ChatGPT inference stays disabled. Identity sign-in can be designed later. It will not be treated as inference access. There is no API-key fallback.

### Capability matrix

| Feature | Status | Basis |
| --- | --- | --- |
| Dynamic OAuth client registration | Supported | Sign-in guide. Untested on this device. |
| PKCE S256 public client | Supported | Sign-in guide and OIDC discovery. |
| System-browser authorization | Supported | Sign-in guide. |
| Android Custom Tab loopback | Untested | Required redirect is loopback. Android delivery is not documented. |
| Native Android qualification | Untested | Local runtime matches the terms. Android is not named. |
| Public-repo or named-license prerequisite | Unsupported as a stated prerequisite | No identifier in the reviewed pages. Redistribution of a closed APK is outside the stated categories. |
| Pre-issued partner client ID | Unsupported for this flow | Dynamic registration is the documented OSS path. |
| Password or cookie collection | Unsupported | Terms, section 1. |
| Private `backend-api` endpoints | Unsupported | Models and inference guide. |
| Codex app-server or Codex impersonation | Unsupported | Project rule. The Codex page will not be implemented. |
| ID token validation | Supported | Sign-in guide plus JWKS from discovery. |
| Granted-scope gate | Supported | Inference requires `chatgpt.tokens.use.direct`. |
| Keystore-backed local token storage | Supported by the terms | Remote storage is forbidden. Android Keystore is the local design. Untested. |
| Refresh and rotation | Supported | Profiles and token reference. |
| Revocation | Supported | Profiles guide and discovery `revocation_endpoint`. |
| Remote disconnect notification | Unsupported | OpenAI does not push a disconnect event. |
| Live model list | Supported | `GET /v1/models`. The account catalog is untested. |
| Streaming Responses API | Supported | `store: false`, `stream: true`, success on `response.completed`. |
| Non-streaming or stored responses | Unsupported | Preview limitations. |
| System-role message items | Unsupported | Preview limitations. |
| `previous_response_id` over HTTP | Unsupported | Send history in `input`. |
| `background`, `temperature`, `top_p`, `max_output_tokens`, `metadata`, `user`, and the other omitted fields listed in preview limitations | Unsupported | Preview limitations. |
| Text input | Supported | Preview limitations, when the selected model accepts it. |
| Image input | Supported | Same condition. Untested on Android. |
| File input | Supported | Same condition. The Files upload API is unsupported, so this is inline file input only. Untested. |
| Audio or video input | Unsupported | Preview limitations. |
| Transcription API | Unsupported | Preview limitations. |
| Image generation, file search, Code Interpreter, computer use, hosted MCP, `tool_search` | Unsupported | Preview limitations. |
| Web search | Supported only under model and account policy | Preview limitations. Untested. |
| Function tools in namespaces or `additional_tools` | Supported | Preview limitations. Untested. |
| Persistent server-side conversation | Unsupported | `store` must be false. |
| Reading plan balance or reset time | Unsupported | Usage UI is the source. The 429 code does not include a reset time. |
| App-side refusal of credit spend | Unsupported | No documented request control. Fail closed. |
| Silent API-key fallback | Unsupported | Project rule. |
| Express consent for background use | Supported as a product requirement | Terms section 3.1. The API `background` field remains unsupported. |
| Using one account for another user | Unsupported | Terms section 3.1. |

## 2. Billing classes

Only a verified renewable free allowance, or an eligible existing subscription allowance that cannot spend extra credits, may run automatically.

| Source | Class | Automatic use |
| --- | --- | --- |
| Gemini API on a project with no billing account, free-tier models only | Renewable free allowance | Blocked until the setup procedure below is confirmed. HTTP 402 disables the provider. |
| Gemini API after a billing account is linked, including prepaid credits | Pay-as-you-go or prepaid | Blocked. Linking billing is also what moves the project off the Free tier. |
| Groq Free plan | Renewable free allowance | Blocked until the plan can be verified. No plan field was found in the reviewed API docs. |
| Groq Developer plan | Pay-as-you-go | Blocked. |
| OpenRouter key whose credit limit is 0, model `openrouter/free` or a `:free` id | Renewable free allowance | Eligible after `GET /api/v1/key` shows a numeric limit of 0. Not executed in this milestone. |
| OpenRouter key with a null or positive limit, or any non-free model | Prepaid or pay-as-you-go | Blocked. A fallback list that includes a paid model is billed. |
| Cloudflare Workers Free, at or under 10,000 Neurons/day, model not on the paid-required list | Renewable free allowance | Blocked until the account is confirmed to be Workers Free and the model is absent from the current paid-required note. |
| Cloudflare Workers Paid overage, or AI Gateway prepaid credits | Pay-as-you-go or prepaid | Blocked. |
| ChatGPT included plan allowance | Existing subscription allowance | Blocked. Credit use cannot be excluded by the app. |
| ChatGPT credits and automatic credit purchase | Prepaid, and possibly automatic charges | Blocked. |
| Cerebras $5 trial after a payment method | One-time promotional credit | Blocked. |
| Provider API keys in general | Pay-as-you-go, prepaid, or unknown | Blocked. Unknown status blocks the request. |

## 3. Free-provider validation

Model ids and quotas below are observations from the pages fetched on 23 November 2026. They are not values to hardcode. Each implementation must re-read the linked page.

### Gemini API

| Topic | Official page | Finding |
| --- | --- | --- |
| Authentication | https://ai.google.dev/gemini-api/docs/api-key | API key. Keys created in AI Studio from 28 May 2026 are authorization keys. Unrestricted standard keys are rejected. |
| Free-tier eligibility | https://aistudio.google.com/docs/billing and https://ai.google.dev/gemini-api/docs/pricing | New projects start on the Free tier. Free means limited models and free input and output tokens. Some projects need verification. |
| Billing | https://aistudio.google.com/docs/billing | Moving to a paid tier requires a linked billing account and a prepaid balance, with a documented minimum of $5. A zero prepaid balance returns HTTP 402 and stops keys on that billing account. |
| Models | https://ai.google.dev/gemini-api/docs/pricing | Per-model free availability is on this page. Examples in other docs, including `gemini-4-hydrogen`, are not an allowlist. `gemini-omni-1.2-flash` and `genie-4` are documented as not supported on the Free tier. |
| Rate limits | https://aistudio.google.com/docs/rate-limits | RPM, TPM, and RPD are per project and per model. The live numbers are shown in AI Studio. RPD resets at midnight Pacific time. Specified limits are not guaranteed. |
| Input types | https://ai.google.dev/gemini-api/terms | Terms describe prompts, system instructions, and files such as images, videos, and documents. The exact free-tier model that accepts each type must be read from the current pricing and model pages before use. |
| Streaming | https://ai.google.dev/gemini-api/docs/text-generation | Streaming is documented. The request shape must be copied from that page at implementation time. |
| Errors | https://aistudio.google.com/docs/rate-limits and the billing page | Limit exhaustion is HTTP 429 `RESOURCE_EXHAUSTED`. Exhausted prepaid balance is HTTP 402. |
| Quota reset | https://aistudio.google.com/docs/rate-limits | Daily request quotas reset at midnight Pacific time. A missing reset header stays unknown. |
| Privacy | https://ai.google.dev/gemini-api/terms | Unpaid Services: Google uses submitted content and generated responses to provide, improve, and develop Google products and machine learning technologies. Human reviewers may read them. The data is disconnected from the account, key, and project before review. Sensitive, confidential, or personal information must not be submitted. EEA, Switzerland, and UK accounts follow the paid-service data terms even on unpaid quota. |
| Training | Same terms page | Unpaid content may be used to develop machine learning technologies. Paid Services: prompts and responses are not used to improve products. Linking an active billing account makes Gemini API access a Paid Service for that data rule, and it also creates the spending path this project forbids. |
| Paid fallback | Billing page | The app cannot select a paid model or attach billing. A project that later gains a billing account can start spending. HTTP 402 disables the provider. There is no documented "stay on free tier" request flag. |
| Programmatic free-tier check | Not found | No endpoint in the reviewed pages returns the usage tier for an API key. |

Google's key guide says production client apps should proxy keys through a backend. This project has no server. The acceptable design is the user's own key, entered on the device and stored in the Keystore, never compiled into the APK and never committed. A shared project key will not be embedded. That still leaves extraction from the user's own device as a residual risk.

Safe setup, required before any Gemini call:

1. Create or import a project in Google AI Studio.
2. Confirm the project shows the Free tier and has no linked billing account.
3. Create an AI Studio authorization key restricted to the Gemini API.
4. Read the current pricing page and record, with the date, which models are on the Free tier.
5. Store the key only in the local Keystore.
6. On HTTP 402 or any billing error, disable Gemini and keep completed work.

Until that confirmation exists, Gemini billing status is unknown and automated requests are blocked.

### Groq

| Topic | Official page | Finding |
| --- | --- | --- |
| Authentication | https://console.groq.com/docs/overview | Bearer API key. OpenAI-compatible base URL `https://api.groq.com/openai/v1`. |
| Free-tier eligibility | https://console.groq.com/docs/rate-limits and https://console.groq.com/settings/billing/plans | A Free plan is published. The Developer plan is pay per token. The rate-limit page says the table is a base summary and the console Limits page is authoritative for the organization. |
| Billing | Plans page | Free is $0. Developer is pay per token. The app must not upgrade the organization. |
| Models | Rate-limit page | The free base table is model-specific and changes. It will be re-read before enablement. It will not be copied into source as a permanent list. |
| Rate limits | https://console.groq.com/docs/rate-limits | Organization-wide. Headers include `retry-after`, remaining requests, and remaining tokens. Cached tokens are excluded. Exceeding a limit returns HTTP 429. |
| Input types | Overview and rate-limit pages | Text inference and audio transcription are both in the documented surface. Image input was not established for the free catalog in this review. |
| Streaming | Not confirmed in the pages read | The overview example is a non-streaming Responses call. Streaming stays untested until the current Groq request reference is read. |
| Errors | Rate-limit page | HTTP 429 when a limit is exceeded. The free plan, as described by Groq, pauses rather than charging for the overage. |
| Quota reset | Rate-limit headers | `x-ratelimit-reset-requests` refers to the daily request window. `x-ratelimit-reset-tokens` refers to the per-minute token window. If a header is absent, the reset time stays unknown. |
| Privacy | https://console.groq.com/docs/your-data | Inference customer data is not retained by default. Groq may temporarily keep inputs and outputs for up to 30 days for reliability or abuse investigation unless Zero Data Retention is enabled. Usage metadata is always retained and does not include inputs or outputs. Retained data is in Google Cloud in the United States. |
| Training | Not stated on the Groq data page | This review does not claim that Groq trains or that Groq never trains. OpenRouter's provider table separately lists Groq as not training; that is OpenRouter's routing record, not Groq's own sentence. |
| Paid fallback | Plans page | A Free organization is not documented as falling over onto Developer billing. An organization already on Developer is pay-as-you-go. The app cannot see that distinction in the reviewed API pages. |
| Programmatic plan check | Not found | Rate-limit headers describe remaining quota. They do not identify the Free plan versus the Developer plan. |

Safe setup:

1. In the Groq console, confirm the organization plan is Free and that no payment method is required for the key in use.
2. Re-read the rate-limit page and the console Limits page on the day of enablement.
3. Enable Zero Data Retention if the console still offers it.
4. On any payment-required or billing error, disable Groq.
5. On HTTP 429, honor `retry-after` once for a per-minute limit. A daily-quota exhaustion opens the circuit until the reported reset. No reset time means the circuit stays open.

Until step 1 is recorded, Groq billing status is unknown and automated requests are blocked.

### OpenRouter

| Topic | Official page | Finding |
| --- | --- | --- |
| Authentication | https://openrouter.ai/docs/api_reference/limits | Bearer API key. `GET https://openrouter.ai/api/v1/key` returns key metadata and does not run inference. |
| Free-tier eligibility | https://openrouter.ai/docs/guides/routing/model-variants/free and https://openrouter.ai/openrouter/free | A model id ending in `:free`, or exactly `openrouter/free`. The free router selects among free models and falls back only to free models. |
| Billing | https://openrouter.ai/docs/api_reference/limits | Account credits are prepaid. A key `limit` of null is unlimited. A key limit of 0 cannot spend. `is_free_tier` reports whether the account has purchased credits before. Purchasing at least 10 credits raises the free-model daily cap; the app must never purchase credits. |
| Models | Free-variant guide | The live models API is the catalog. Only `:free` ids or `openrouter/free` are eligible. |
| Rate limits | Limits page | Free variants: 20 requests per minute. 50 requests per day when all-time purchased credits are under the documented threshold, and 1,000 per day after the higher tier. `free_model_daily_requests` reports the daily counter. The per-minute limit is not in that object. Reset of the daily counter is UTC. |
| Input types | Free router page | The router filters for features such as image understanding when the request needs them. A request that no free model can serve must fail. It must not fall through to a paid model. |
| Streaming | Not re-verified on a dedicated page in this review | Implementation must follow the current OpenRouter streaming reference. Absence from this report is not a claim that streaming is missing. |
| Errors | Limits page | Credit exhaustion is HTTP 402. Budget blocks can be HTTP 403. Rate limits are HTTP 429 with `X-RateLimit-*` headers. A negative credit balance can 402 even a free model. |
| Quota reset | Key metadata | `free_model_daily_requests` is the daily free counter. Budget `resets_at` is a separate spend window and is irrelevant for a limit of 0. |
| Privacy | https://openrouter.ai/docs/guides/privacy/logging | Each upstream provider has its own retention and training policy. Account settings can opt out of providers that train, separately for free and paid models. The app should set the request data policy to providers that do not train, and abort if no such free endpoint can serve the request. |
| Training | Same privacy page | Possible, depending on the upstream provider, unless the no-training filter is applied. The published table includes providers marked "May train." |
| Paid fallback | Free-variant guide | A `:free` model followed by a paid model in the `models` array is billed when the free model is skipped. Prevention: send one model id, never a fallback array, and reject any id that is not `openrouter/free` and does not end in `:free`. |
| Programmatic check | `GET /api/v1/key` | This is the verification. Automated use is allowed only when `data.limit` is the number 0. `limit: null` is unlimited and blocks automation. This metadata call was not made, because no key has been authorized. |

Safe setup:

1. Create a key with a custom credit limit of 0.
2. In privacy settings, disallow providers that may train on free-model prompts.
3. After the owner authorizes it, the app reads `GET /api/v1/key` and stores the observed limit.
4. Send only `openrouter/free` or a single `:free` id.
5. On HTTP 402, disable the key.

Milestone 004B re-read the key, models, chat, streaming, limits, free-variant, routing, and plugin pages on 23 November 2026. A numeric limit of 0 is still necessary and is still not proof that a free model can be called. The adapter rejects paid models, unknown pricing, paid fallbacks, and live calls. The metadata call in step 3 has not been made.

### Cloudflare Workers AI

| Topic | Official page | Finding |
| --- | --- | --- |
| Authentication | https://developers.cloudflare.com/workers-ai/get-started/rest-api/ | Account ID plus a Bearer API token with Workers AI Read and Edit. Endpoint shape: `https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/run/{model}`. |
| Free-tier eligibility | https://developers.cloudflare.com/workers-ai/platform/pricing/ | Workers Free includes 10,000 Neurons per day. Workers Paid includes the same 10,000 and then charges $0.011 per 1,000 Neurons. |
| Billing | Same pricing page | Overage on the Free plan fails. Overage on the Paid plan costs money. Some models require Workers Paid or prepaid AI Gateway credits. Prepaid credits are a separate spending path. |
| Models | Same pricing page | The catalog is large and changes. The paid-required note on the fetched page names `@cf/moonshotai/kimi-k2.6`, `@cf/moonshotai/kimi-k2.7-code`, `@cf/zai-org/glm-5.2`, `@cf/zai-org/glm-5.3`, `@cf/zai-org/glm-5.3-flash`, `@cf/deepseek-ai/deepseek-v4-flash-0731`, `@cf/deepseek-ai/deepseek-v4.1-flash-1017`, `@cf/deepseek-ai/deepseek-v4-pro-0813`, and `@cf/thinkingmachines/inkling-2-small`. That note is a dated snapshot, not a permanent denylist. Re-read the page before enablement. The REST quickstart's example model is not permission to hardcode it. |
| Rate limits | Pricing page | The documented free ceiling is the daily Neuron allocation. Per-model request limits were not given a separate stable number on this page. |
| Input types | REST quickstart | The sample is a text `prompt`. Vision and other modalities depend on the model page and were not certified here. |
| Streaming | Not confirmed on the pages read | The REST quickstart returns a single JSON body. Streaming stays untested. |
| Errors | Pricing page | Exceeding the free allocation fails the operation. The page does not give a stable error code in the section that was read. The app must preserve the HTTP status and body and disable the provider on any payment or credit error. |
| Quota reset | Pricing page | Daily at 00:00 UTC. |
| Privacy | Not established from a Workers AI data-policy page in this review | OpenRouter's provider table lists Cloudflare as zero retention and not training. That table describes Cloudflare as an OpenRouter upstream, not this direct API. Direct Workers AI training and retention remain unverified until the current Cloudflare data page is read. |
| Training | Unverified for the direct API | See the privacy row. |
| Paid fallback | Pricing page | The Free plan does not bill the overage. A Paid plan does. A paid-required model can pull the account onto paid or prepaid billing. Both are blocked by the denylist and by the plan check. |
| Programmatic plan check | Not found | Neuron balance is visible in the dashboard. No reviewed endpoint returns "Workers Free" versus "Workers Paid." |

Safe setup:

1. Confirm the account is on the Workers Free plan and that AI Gateway unified prepaid billing is not attached.
2. Re-read the pricing page. Refuse every model in the current "requires a paid billing method" note.
3. Stop at the daily Neuron error. Do not upgrade the plan.
4. On any payment or credit error, disable Cloudflare.

Until step 1 is recorded, Cloudflare billing status is unknown and automated requests are blocked.

## 4. Development environment

Inspected on this computer on 23 November 2026. No packages were installed.

| Item | Result |
| --- | --- |
| Operating system | Microsoft Windows 11 Home, version 10.0.26200, 64-bit. |
| Processor | AMD64. |
| Memory | 15.7 GB. |
| Project directory | `C:\Users\sande\Desktop\AIAPP\AIRachabanda` |
| Existing files before this milestone | None. The directory was empty and was not a Git repository. |
| Files added by this milestone | `docs/PROJECT_REQUIREMENTS.md` and this report. |
| Git | Installed. `git version 2.44.0.windows.1` at `C:\Program Files\Git\cmd\git.exe`. The project is not a repository. |
| Java on PATH | Absent. `java` and `javac` are not recognized. |
| JAVA_HOME | Unset. |
| JDK that is present | Android Studio JBR, OpenJDK 21.0.9, at `C:\Program Files\Android\Android Studio\jbr\bin\java.exe`. |
| Android Studio | Installed. `C:\Program Files\Android\Android Studio\bin\studio64.exe`. Build `AI-253.30387.90.2532.14935130`. Data directory name `AndroidStudio2025.3.2`. `studio` and `studio64` are not on PATH. |
| Android SDK | Present at `C:\Users\sande\AppData\Local\Android\Sdk`. |
| ANDROID_HOME | Unset. |
| ANDROID_SDK_ROOT | Unset. |
| SDK platforms | `android-34`, `android-35`, `android-36`. |
| Build tools | 35.0.0, 35.0.1, 36.0.0, 37.0.0. |
| Platform tools | Revision 37.0.0. |
| Command-line tools | `cmdline-tools\latest\bin\sdkmanager.bat` is present. |
| Gradle on PATH | Absent. |
| Gradle distributions already downloaded | 8.12, 8.14, and 9.2.0 under the user Gradle wrapper cache. No project wrapper exists yet. |
| ADB | `C:\Users\sande\AppData\Local\Android\Sdk\platform-tools\adb.exe`, Android Debug Bridge 1.0.41, version 37.0.0-14910828. Not on PATH. |
| Connected devices | None. `adb devices -l` listed no attached devices. |
| Emulators | One AVD: `Pixel_9a`. It was not running. Image `system-images\android-37.2\google_apis_playstore_ps16k\x86_64`. ABI x86_64. Play Store enabled. 16 KB page size. Configured display 1080 by 2424 at 420 dpi. GPU mode in the generated hardware profile is SwiftShader. |
| Other system images | `android-36\google_apis_playstore\x86_64` is installed. `android-35\google_apis_playstore` is present as a directory. No foldable AVD is installed. |

### Missing dependencies and compatibility

- Shell builds cannot see Java, the SDK, ADB, or Gradle until `JAVA_HOME`, `ANDROID_HOME`, and PATH are set. Android Studio itself can build without those user environment variables. A later milestone should set them for the shell without changing global Git config.
- The project has no Gradle wrapper, no manifest, and no Git history.
- No physical phone is connected, so Galaxy Z Fold 6 folded and unfolded behavior cannot be verified on hardware.
- The only emulator is a Pixel 9a on a 16 KB page-size image. That does not represent a foldable. A foldable AVD is a later test dependency, and it is a free SDK component.
- SwiftShader will make emulator UI tests slower than a hardware GPU.
- JBR 21 is sufficient for current Android Gradle Plugin toolchains. The app runtime is still the Android SDK, so desktop Java libraries, including newer Apache POI releases that require a full Java 17 runtime, remain an open compatibility risk from Milestone 001.
- Google's Gemini key guidance prefers a backend proxy. Adding one would violate the no-server rule. The local user-owned key is the chosen limit, and it stays disabled until the Gemini setup procedure is confirmed.

## 5. What this milestone does not authorize

- No ChatGPT sign-in implementation.
- No inference call to any provider.
- No account creation.
- No API key embedded in source.
- No Gradle project.
- No Git commit.

The next implementation milestone should be authorized separately. The first safe network call, when authorized, is an OpenRouter key-metadata read or a Gemini free-tier confirmation. It is not a ChatGPT inference request.
