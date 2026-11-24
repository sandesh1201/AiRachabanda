# Milestone 005E — Automated path

Baseline: `4f66b7d74c488390c4e27342a53334acf2dc0ffc`.

No live request was sent. No account was created. No runtime was installed. No model was downloaded.

## Gemini

`GET https://apikeys.googleapis.com/v2/keys:lookupKey` returns `parent` and `name` for a key string. The published OAuth scope is `https://www.googleapis.com/auth/cloud-platform.read-only`. The method requires `apikeys.keys.lookup` on the parent project. The narrower role that includes that permission is `roles/serviceusage.apiKeysViewer`. The Gemini API key is not the caller.

Billing for the looked-up project is `GET https://cloudbilling.googleapis.com/v1/projects/{projectId}/billingInfo`. The method page requires `resourcemanager.projects.get`. `billingEnabled: false` means the project cannot use paid services. A typed project ID that differs from `parent` is rejected. Both requests stay unsent. Free-tier content may be used to improve Google products.

## Cloudflare

Subscriptions can list `WORKERS_PAID` and can also list a product rate plan id `free`. That product id is not Workers Free. `GET /accounts/{account_id}/entitlements` needs Account Read and returns feature allocations, not the Workers plan name. The pricing page says the default plan is Workers Free and that the free Neuron allocation fails instead of charging. That default is not this account's API identity. The adapter stays disabled.

## Local

The prepared provider accepts `gemma3:1b`, `llama3.2:1b`, and `llama3.2:3b` on `127.0.0.1` or `localhost` only, with cloud execution disabled and a context cap of 4096 tokens. `requestPermitted` stays false. The phone app does not require the Windows computer for assisted chat.

## Council test that is not yet live

When a later milestone authorizes local inference, use two verified model ids, `gemma3:1b` and `llama3.2:3b`, plus assisted ChatGPT and Claude. The engine already labels different verified ids as a cross-model review and says agreement is not proof. Do not invent the answers.
