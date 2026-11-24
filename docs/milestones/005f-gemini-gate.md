# Milestone 005F — Gemini consent gate

Baseline: `9c9a43dde7eee7cca79c3a9e133f6780785a0fa5`.

No live Google request was sent. The Android OAuth client is not registered, and `billingEnabled` has not been read for a real project.

The prepared model is `gemini-3.5-flash-lite`. Official pricing lists its free-tier input and output as free of charge, and says free-tier content may be used to improve Google products. The prepared call caps output at 128 tokens and includes no tools, grounding, search, or cache.

OAuth uses an Android client for package `com.airachabanda.app`. Android clients do not have a client secret. The debug SHA-1 is `E0:6F:67:07:E9:76:16:19:F1:65:95:C0:DF:E4:B2:8A:F7:25:D5:4B`. The scope is `https://www.googleapis.com/auth/cloud-platform.read-only`. The account needs `apikeys.keys.lookup` and `resourcemanager.projects.get` on the project that owns the key.

Local development uses `adb reverse tcp:11434 tcp:11434` so the app’s `127.0.0.1` reaches Windows loopback. `10.0.2.2` is rejected. The manifest still forbids cleartext. A future phone connection needs TLS and a credential on the local network.
