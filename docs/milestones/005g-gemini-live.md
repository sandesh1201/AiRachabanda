# Milestone 005G — Gemini owner authorization

Baseline: `6fc714b46a829a88f2269cb9e46c810e59f6d333`.

The app requests one OAuth scope, `https://www.googleapis.com/auth/cloud-platform.read-only`, through Play services `AuthorizationClient`. The Android client is public. No client secret is embedded.

After the owner confirms in the app, the metadata check calls:

- `GET https://apikeys.googleapis.com/v2/keys:lookupKey`
- `GET https://cloudbilling.googleapis.com/v1/projects/{project}/billingInfo`
- `GET https://cloudresourcemanager.googleapis.com/v1/projects/{project}` only when the billing project id differs from the lookup parent

`billingEnabled` must be false. The generation model must be `gemini-3.5-flash-lite` or `gemini-3.1-flash-lite`, both listed with free input and output on the Gemini pricing page read 24 November 2026, and the live model list must offer `generateContent`. The call caps output at 128 tokens and sends no tools, grounding, search, or cache. It is not retried.

A billing read can go stale if the project is linked to billing afterward. The app reads billing again before a later generation and has no paid fallback. Local inference is not installed.
