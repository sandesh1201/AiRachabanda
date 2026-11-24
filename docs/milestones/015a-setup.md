# Milestone 015A in-app provider setup

Each provider in the catalog opens a setup screen from Settings, AI providers. The screen offers only the access methods that provider supports. ChatGPT and Claude offer free assisted chat and an existing subscription used through assisted chat. A saved subscription is a declaration and does not authorize an API.

API fields are shown for Gemini, Cloudflare, OpenRouter, and Groq. Values are masked, can be pasted, and are stored through the existing Android Keystore path. The field is cleared after save. Stored values are not shown again. Groq can be saved and still cannot run inference until the Free plan is verified.

Official HTTPS links open from the screen. Gemini shows the package name and the signing certificate of the current install, with copy actions. That fingerprint is not stored as a constant. Google still requires an Android OAuth client registered for this package and certificate. The app does not contain a client secret.

No account was created and no live inference request was sent.
