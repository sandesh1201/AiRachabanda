# Milestone 005B — Metadata wait and provider feasibility

Marker `END-005B-INSTRUCTIONS` was in the milestone prompt. Baseline commit `3d84f09228fc568fc93653e619d37a262fc6d371`. Documentation was checked on 23 November 2026. No live OpenRouter request was sent. No account was created. No model was downloaded. No runtime was installed.

The portrait emulator was still 1080×2424 at 420 dpi, with `display_size_forced` unset. The OpenRouter screen read `NOT_CONFIGURED`. No credential was stored, so Verify connection was not used.

## OpenRouter billing

These statements are limited to the pages listed at the end of this note.

- A negative account balance can return HTTP 402, including for free models. The limits page tells the reader to add credits to bring the balance above zero. This app must not do that.
- A per-key `limit` of null is unlimited. Exhausting `limit_remaining` returns HTTP 402. The pages do not say that a numeric limit of 0 can successfully call a `:free` model.
- `:free` is a separate catalog entry with its own pricing. A fallback array that continues to a paid model is billed at that model's price. `openrouter/free` falls back only among free models, and it is still automatic routing, so it is not the model for the first explicit test.
- `provider.max_price` can cap provider prompt, completion, request, and image prices. If no provider is at or below the cap, the request fails. The same page does not say that `max_price` covers plugin, web-search, or file-processing charges.
- `provider.allow_fallbacks` defaults to true. Default routing can retry another provider. The first test must set it to false.
- Account plugin defaults apply when a request omits `plugins`. A named plugin can be disabled with `enabled: false`. If Prevent overrides is on for that plugin, the request cannot disable it. `GET /api/v1/key` does not report plugin defaults or Prevent overrides.
- Free-model rate limits on the limits page are 20 requests per minute, and 50 or 1,000 per day depending on all-time purchased credits. `free_model_daily_requests.remaining` is the daily counter when the limit applies. It is not the per-minute remaining count, and the daily ceiling is not the remaining count.

Inference stays disabled. A zero credit limit, a zero `max_price`, and a manual plugin setting are not together a documented guarantee against every additional charge.

## First inference request

Not sent. No eligible model id was discovered, because the owner has not confirmed a metadata check. The shape below is ready for a later authorization and is not permission to send it.

- Method and URL: `POST https://openrouter.ai/api/v1/chat/completions`
- One request. No automatic retry.
- `model`: the exact catalog id chosen from a future authorized metadata result. It is not selected in this note.
- `stream`: true
- `max_tokens`: 128
- One user message: `What is 17 multiplied by 23? Return the result and a brief explanation.`
- No `models` array, no `tools`, no attachments, and no `openrouter/free`.
- `provider.allow_fallbacks`: false
- `provider.max_price`: prompt 0, completion 0, request 0, and image 0
- `plugins`: `web`, `response-healing`, and `context-compression`, each with `enabled` false

A finish reason of `length` would stay truncated. An unknown outcome would not be retried.

## Other providers

GitHub Models is retired. The official page says that on 30 July 2026 the playground, catalog, inference API, and bring-your-own-key access were removed. It is not an integration candidate. GitHub Copilot is a separate product and is not a substitute.

Cloudflare Workers AI includes 10,000 Neurons per day on the Workers Free plan. The pricing page says further operations fail when a listed limit is exceeded, and the paid overage rate applies on Workers Paid. Some named models require Workers Paid or prepaid AI Gateway credits. The safeguard is an account that remains on Workers Free, a model that is not on that paid-required list, and a client that stops on the error. Authentication is an account ID plus a Bearer API token. The daily allowance renews at 00:00 UTC. This pass did not restate a training policy.

The Gemini Developer API has a Free tier that does not require a linked billing account. Paid tiers start when a billing account is linked. The pricing pages list some models as free of charge on the Free tier and others as not available there. Free-tier prompts and responses may be used to improve Google products. Rate limits are per project, vary by model, and the rate-limits page says to read the live values in Google AI Studio rather than treat a copied table as permanent. Exceeding a limit returns an error. Spend-based rate limits are marked N/A on the Free tier. The zero-cost rule is: no billing account linked, an explicit model whose pricing row says free of charge, no Grounding or other paid tool, and a stop on HTTP 429. The live quota still has to be read in AI Studio before enablement.

Groq lists a Free plan at $0 and a Developer plan that requires a payment method and bills token use. Exceeding a rate limit returns HTTP 429. The public rate-limit table read for this note is labeled as Developer-plan base limits, so it is not recorded here as the Free-plan quota. Response headers expose remaining requests and tokens, not whether the organization is on the Free plan. A Developer organization can incur charges. Groq stays blocked until the organization plan can be shown to be Free.

Hugging Face Inference Providers gives free accounts $0.10 of monthly credits, and the pricing page says that amount is subject to change. PRO and paid organization plans include more. Usage beyond the credits is pay-as-you-go and, for a free account, requires a credit purchase. Automatic recharge can charge a saved payment method. Routed requests use provider prices with no Hugging Face markup, so a short request can still spend the credit. This is a small prepaid balance, not a hard daily rejection on a free plan.

Cerebras documents a one-time $5 promotional credit after a payment method is added. The credit expires 30 days after activation. The pricing page says the account is not automatically enrolled in paid usage, and API access pauses when the credit expires or is exhausted unless credits are purchased. The listed models have nonzero token prices. That is promotional spending, not a renewable zero-cost allowance.

## Rank

1. Cloudflare Workers AI. The Free plan is documented to fail instead of charging, if the account is not Workers Paid and the model is not on the paid-required list.
2. Gemini API on the Free tier. A billing account is not required, and paid use is a separate step. The privacy cost is that free-tier content may be used to improve Google products, and the exact quota must be read before enablement.
3. Groq Free, only after the plan can be distinguished from Developer. It is not one of the next two.
4. Hugging Face Inference Providers. The included amount is small and further use is designed as paid credit.
5. Cerebras. A payment method and a priced promotional credit do not meet the zero-cost rule.
6. GitHub Models. Retired.

The next two automated integrations to prepare are Cloudflare Workers AI and the Gemini API Free tier. Neither is enabled by this note. ChatGPT and Claude remain mandatory assisted participants. Their automated inference stays off.

## Local inference

This is a feasibility note. Nothing was installed or downloaded.

Google's current on-device LLM path is LiteRT-LM. The MediaPipe LLM Inference API page says that API is maintenance-only and points new work to LiteRT-LM. llama.cpp remains a widely used local runtime for GGUF models. Ollama's Windows and Linux builds can run a local model, and the same project also offers cloud models that require an API key. A local integration must refuse the cloud path.

Public specifications for the Galaxy Z Fold 6 list 12 GB of RAM and a Snapdragon 8 Gen 3. This pass did not measure speed, heat, or battery on that phone. Small quantized models are the only plausible on-device fits. An 8B-class model is a tight fit beside Android itself, and a leading cloud model should not be assumed. Ollama's quickstart recommends about 8 GB of VRAM or unified memory for a Gemma 4 E2B download of about 7.2 GB, and it says a machine with less VRAM can use system RAM more slowly. A Windows computer with about 16 GB of RAM can be assessed for a small local model later. It was not tested here.

On-device inference works offline after the model file is already stored. Calling Ollama on the Windows computer from the phone is a local-network service and would need authentication. It must not be exposed on the public internet. Model licenses differ and have to be checked for each file. Local inference stays optional. It does not replace ChatGPT, Claude, or a verified free cloud provider, and it does not remove a future authorized automation path for those assisted providers.

## Owner actions

1. Revoke the OpenRouter key that was pasted into Cursor chat. Do not paste a replacement here.
2. Create a new key with a custom credit limit of 0. Do not buy credits and do not turn on automatic credit purchases.
3. Confirm the account balance is not negative. Leave plugins off, including web search and file processing.
4. On the phone, open Settings, tap OpenRouter, enter the new key, and tap Save credential.
5. Tap Verify connection. Read the confirmation. Tap Send metadata check only if you want `GET /api/v1/key` and `GET /api/v1/models` sent. Cancel sends nothing.
6. Do not start the inference test. It waits for a later instruction after that metadata exists.

## Pages read

- https://openrouter.ai/docs/api_reference/limits
- https://openrouter.ai/docs/guides/routing/model-variants/free
- https://openrouter.ai/docs/guides/routing/provider-selection
- https://openrouter.ai/docs/guides/features/plugins
- https://ai.google.dev/gemini-api/docs/rate-limits
- https://aistudio.google.com/docs/pricing
- https://aistudio.google.com/docs/billing
- https://console.groq.com/docs/rate-limits
- https://console.groq.com/docs/billing-faqs
- https://console.groq.com/settings/billing/plans
- https://inference-docs.cerebras.ai/support/rate-limits
- https://www.cerebras.ai/pricing
- https://huggingface.co/docs/inference-providers/main/en/pricing
- https://developers.cloudflare.com/workers-ai/platform/pricing/
- https://docs.github.com/en/github-models
- https://developers.google.com/edge/litert-lm/overview
- https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference
- https://docs.ollama.com/quickstart
