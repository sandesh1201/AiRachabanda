# Milestone 015 provider feasibility

Official pages were read on 24 November 2026. No account was created. No credential was entered in Cursor chat. No metadata request and no inference request were sent.

## Classification

| Provider | Classification | Automated access |
|---|---|---|
| ChatGPT | Free assisted consumer chat. The API usage page lists a Free tier with a monthly usage limit, and paid tiers start after credit purchases. A ChatGPT subscription is not API access. | Off |
| Claude | Free assisted consumer chat at claude.ai. The Claude Platform documents spend caps, not a zero-charge API. | Off |
| Gemini | Free assisted chat at gemini.google.com, plus a separate Developer API. Standard input and output for `gemini-3.5-flash-lite` and `gemini-3.1-flash-lite` are free of charge. Context caching and grounding are not available on that free tier. Paid tiers start when a billing account is linked and prepaid. | Off until the stored key is bound to a project whose billingEnabled is false, and the owner gives separate inference consent |
| Cloudflare Workers AI | Free automated API only on a positively identified Workers Free plan, 10,000 Neurons per day, then the request fails. Workers Paid bills above that allocation. Named models require Workers Paid or prepaid AI Gateway credits. | Off. A missing paid subscription is not Workers Free |
| OpenRouter | Free models exist, with 50 requests per day and 20 per minute until credits are purchased. A negative balance blocks free models. Fallback routing and plugins can select a billed path. A `:free` suffix is not sufficient. | Off |
| Groq | A Free plan is listed at $0. Developer requires a payment method and bills tokens. The API responses used by this app do not prove which plan the organization is on. | Off. No new adapter |
| Grok, Perplexity, DeepSeek, Copilot, Qwen, Mistral, Kimi, Meta AI | Free assisted consumer chat where an official chat site exists. Consumer login is not API access. | Off |
| Local model | Not installed. | Off |

## Owner actions still required

1. Create or choose one Google Cloud project for Gemini. Leave billing disabled on that project. Do not add a payment method for this app.
2. Create an API key in Google AI Studio for that project. Enter it in Settings, Gemini API. Do not paste it into Cursor chat.
3. Register an Android OAuth client for package `com.airachabanda.app` and this install's signing certificate. Then tap Check project billing and approve the Google screen.
4. Read the billing result in the app. Send the one free-tier question only after that result says billing is disabled and you confirm the prompt.
5. Cloudflare, OpenRouter, and Groq stay unused until their existing eligibility checks can be satisfied without a paid plan, credits, or a payment method.

## Pages

- https://ai.google.dev/gemini-api/docs/pricing
- https://aistudio.google.com/docs/billing
- https://developers.cloudflare.com/workers-ai/platform/pricing/
- https://openrouter.ai/pricing
- https://console.groq.com/settings/billing/plans
- https://console.groq.com/docs/billing-faqs
- https://developers.openai.com/api/docs/guides/rate-limits
- https://platform.claude.com/docs/en/api/rate-limits
