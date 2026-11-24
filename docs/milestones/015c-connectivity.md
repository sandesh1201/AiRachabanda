# Milestone 015C

Connect now runs a consented check instead of only saving a key.

- Gemini saves the key and then starts the existing Google authorization. Lookup and billing run only after that authorization. The test question stays on its own confirmation.
- OpenRouter reads the key endpoint and the model catalog. A zero price is not enough, and account plugin defaults remain unresolved, so inference stays off.
- Groq reads the model list. That response does not name the Free or Developer plan.
- Cloudflare lists accounts for the token and reads subscriptions. Omitting WORKERS_PAID is not Workers Free.

Ready requires a stored credential, verified authentication, verified billing, an eligible model, and a completed connection test. No provider is marked Ready by saving a key.

No account was created and no live inference was sent.
