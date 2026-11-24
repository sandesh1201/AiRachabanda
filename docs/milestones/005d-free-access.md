# Milestone 005D — Free chat and free API

Baseline: `03b0f24ed53d59d92587be696bf0954610e1fdda`.

Access modes are `FREE_AUTOMATED_API`, `FREE_ASSISTED_CHAT`, `LOCAL_INFERENCE`, and `UNAVAILABLE`. A protected free API would be preferred. None is protected in this build, so official free chat is assisted and paid inference is not offered. No provider is classified as local inference.

Assisted chat uses the provider's own site. The app copies a prompt only when the user taps Copy and open chat, then opens the site. It does not read the clipboard, cookies, or a session. Imported text stays user-supplied. The last conversation and task are stored so a shared response can return to that task.

Cloudflare subscriptions can show `WORKERS_PAID`. They do not positively name Workers Free. Minimum reads, still not sent: model search with Workers AI Read, and subscriptions with Billing Read. Gemini model list does not show billing. Binding a key to a project and reading `billingEnabled` needs Cloud Billing permission `billing.resourceAssociations.get` on a credential other than the Gemini API key. Send metadata check stays disabled.

## Owner actions for the first automated response

1. Confirm the Cloudflare account is Workers Free in the dashboard and do not add a payment method.
2. Create a token with Workers AI Read plus Billing Read. Enter it only in the app.
3. A later milestone must authorize the subscriptions read and must obtain a positive Workers Free signal. Excluding `WORKERS_PAID` is not enough, so the first automated response is not ready in this build.
