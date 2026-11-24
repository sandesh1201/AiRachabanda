# AI Rachabanda — project requirements

Status: active. These requirements are mandatory for every milestone.
Source: project owner instructions through Milestone 005A.
Last updated: 24 November 2026.

## Identity

- Application name: AI Rachabanda.
- Android package: `com.airachabanda.app`.
- Tagline: Multiple Perspectives. Collective Intelligence.
- Interface language: professional English.
- Platform: native Android.
- Initial release target: version 1.0.0.
- Distribution: a directly installable Android APK.

The application name is final. The interface uses professional English labels and the official names of AI providers.

## Cost and infrastructure

- Additional development cost: $0.
- Additional operating cost: $0.
- No dedicated online server.
- No paid API calls initiated by the application.
- No automatic credit purchases.
- No paid cloud hosting, paid database, paid storage, or paid notification service.
- No mandatory paid SDK.
- No mandatory Firebase dependency.
- No mandatory external backend.
- No subscription upgrade inside the application.
- No advertisements.
- No mandatory central application account.

Existing subscriptions may be used only through a documented, authorized integration.
Renewable free allowances may be used only after billing eligibility is verified.
Unknown billing eligibility blocks automated requests.
One-time promotional credits, prepaid balances, and pay-as-you-go billing are not used automatically.
The application never silently switches to a separately billed API key.

If a feature cannot be built inside these limits, the limitation is reported and a free alternative is proposed. The application does not invent a success state.

## Product behavior

- Local-first architecture. Conversations and research state persist on the device.
- Genuine AI responses only. The interface shows generated discussion text from a completed provider response.
- The application never fabricates AI activity, participation, or integration success.
- A provider is shown as participating only after an authorized request receives a documented successful response.
- A website login is not proof of automated communication.
- Consumer subscription access and developer API access stay separate.
- The application does not force consensus.
- Conclusions may remain disputed or supported by insufficient evidence.
- The application does not generate arbitrary confidence percentages.
- The application does not collect provider passwords or session cookies.
- The application does not call private or undocumented provider endpoints.
- The application does not impersonate Codex, Claude Code, or any other product. Its Sign in with ChatGPT identity is AI Rachabanda.
- Claude is a mandatory Phase 1 provider. Consumer Claude credentials are not used for automated inference. Phase 1 participation is assisted: the official site or app handles login, and the user imports the response.
- ChatGPT is a mandatory provider. Official Sign in with ChatGPT plan usage remains the intended automated path. Automated inference stays disabled until zero-additional-cost safeguards are verified. Assisted participation is the current fallback. The application does not classify that automation as impossible.
- A provider participates in one of three modes: Automated, Assisted, or Unavailable. Account status is stored separately from participation mode.
- The application never generates a response for an assisted participant.
- Zero-Cost Mode is mandatory in Phase 1. The application has no control that enables paid inference.
- A Devil's Advocate review is a mandatory Phase 1 orchestration role. It examines claims already in the conversation. It does not invent disagreement, evidence, or citations, and it does not assign a confidence percentage. The role is available to assisted and automated participants. Claude is eligible through the assisted workflow. A critique is not described as independent verification unless a different underlying model identity is on record. The workflow, data model, and benchmark plan are in `docs/architecture/devils-advocate.md`.

## Capabilities the product must grow to include

These remain requirements. Milestone 002 does not implement them.

- Text, image, and document input.
- Persistent conversations.
- Advanced document generation as real files.
- Background processing and local notifications, with express consent before background use of a connected plan.
- Samsung Galaxy Z Fold 6, folded and unfolded, including a two-pane layout on the unfolded width.
- Officially supported use of an existing subscription.
- Verified free AI integrations.

## Architecture constraints already accepted

- Kotlin.
- Jetpack Compose.
- Material 3.
- Provider-independent orchestration.
- Room for local persistence.
- Modular provider registry, so a new provider does not require an orchestration rewrite.

## Current gate

Milestone 002 is accepted as partial. Its feasibility findings remain in force and are not reopened by later milestones.

Milestone 003 builds the Android application foundation. The implementation record is `docs/milestones/003-implementation-report.md`. Milestone 003 does not authorize a live inference request. Automated requests stay blocked while billing eligibility is unverified, and ChatGPT plan usage stays blocked while credit spend cannot be refused by the application.

Milestone 003A corrects the phone display. The application lays out from the Android window size, in density-independent pixels. A forced guest display size is not part of the application. The Devil's Advocate role is specified in `docs/architecture/devils-advocate.md`, tested in `docs/TEST_PLAN.md`, and scheduled in `docs/ROADMAP.md`. Live inference remains unauthorized.

Milestone 004 keeps the verified Pixel 9a portrait window at 1080×2424 and 420 dpi. Provider credentials are encrypted with an Android Keystore AES-256-GCM key per provider account. They are not stored in Room, DataStore, logs, or the repository. Unknown model identity is stored as `unknown`. A user-declared model name is not an independently verified model identity.

Milestone 004A connects the Council screen to `OrchestrationEngine`. Room schema version 3 adds the protocol-action record through a non-destructive migration from version 2. Role assignment also considers provider availability, model identity, known quotas, user selection, discussion progress, remaining budget, and reviewer independence. Standard and Deep Research attempt a critical review before synthesis. A manually imported response is not authenticated. The engine validates untrusted workflow actions before accepting them, and it does not send a provider request.

Milestone 004B adds Android Keystore instrumentation, an OpenRouter adapter that stays fail-closed, and reviewer classifications. Room schema version 4 records provider completions and a capability note. A numeric key limit of zero is not proof that free models can be called.

Milestone 005A distinguishes truncated and interrupted streams from completed answers. Room schema version 5 records that disposition. OpenRouter metadata can be requested only after an in-app confirmation, and only for the key and models endpoints. A stored key and a successful authentication check do not unlock chat completions.

Milestone 005B does not send that metadata check until the owner confirms it in the app. A zero credit limit, `max_price`, and a manual plugin setting are not treated as a complete zero-cost guarantee. ChatGPT and Claude remain assisted. GitHub Models is retired.

Milestone 005C adds Cloudflare Workers AI and Gemini API adapters with separate billing rules. Neither adapter treats a successful credential as proof of a free plan. Live metadata and live inference stay off. OpenRouter remains installed and is not required for these adapters.

Milestone 005D classifies free chat separately from a free API. Providers with an official free chat can participate by assisted prompt and import. Automated inference stays off until billing protection is positively established. A missing paid subscription is not treated as Workers Free.

Milestone 005E prepares a Gemini key lookup that accepts only the project returned for that key, and keeps Cloudflare disabled because no subscription or entitlement field names Workers Free. An optional loopback local provider is present and does not run. No runtime was installed and no model was downloaded.

Milestone 005F keeps Gemini metadata and the one prepared free-tier request unsent until an Android OAuth client is registered and billingEnabled is false for the looked-up project. Android reaches a Windows Ollama server only through adb reverse to device loopback. Cleartext stays off.

Milestone 005G uses the Android AuthorizationClient for cloud-platform.read-only and sends lookup, billing, and one free-tier generation only after separate in-app confirmations. The eligible text models come from the pricing page, not from model discovery alone. No source flag is required after those checks pass. Local inference stays uninstalled.

Milestone 006A extends the Council with structured participant requests, role assignment from the current session, Devil's Advocate objections only when they name a recorded claim, and a synthesis assembled from stored records. Assisted providers still require the user to import responses. No live provider request is added.

Milestone 006B adds a deterministic discussion plan, a task dependency graph, and concurrency limits. Participant text cannot raise the budget or authorize billing, credentials, network access, or code execution. The recorded synthesis remains a stored-record fallback. No live provider request is added.

Milestone 007 records claim kind, local arithmetic checks, objection status changes, and evidence notes on the existing Room schema. A calculation is verified only when the local result matches the claimed result. A locator is not treated as support, and live web retrieval is not performed. Fixture scores are not a measurement of live AI accuracy.

Milestone 008 retrieves public sources only after consent. Wikimedia, arXiv, and the Federal Register are the approved connectors. A user may also submit one public HTTPS URL. A successful response is stored as retrieved and not assessed. It is not treated as proof of a claim. No AI inference request is added.

Milestone 009 copies selected or shared files into app storage and extracts text locally. PDF, DOCX, XLSX, PPTX, TXT, CSV, JSON, Markdown, JPEG, PNG, and WEBP are the supported formats. Extracted text is untrusted document content. OCR is not installed. A file is not uploaded, and extracted text enters a prompt only after consent. No AI inference request is added. Document generation remains deferred.

Milestone 010 renders PDF, DOCX, XLSX, PPTX, CSV, TXT, Markdown, and JSON on the device from a structured document. The files are real documents, not renamed text. Formulas that pass a local allow-list can store a locally calculated cache. Macros, external workbook connections, and arbitrary code are not created. A generated file is not an AI provider response. No AI inference request is added.

Milestone 011 keeps a discussion going when one provider is unavailable, disabled, or waiting. Unfinished work can move to another eligible participant. Pause, resume, stop, finish now, and skip stay on the discussion screen. Room schema version 9 records the control generation and the fault. A stopped or paused discussion is not started again by itself. No AI inference request is added.

Milestone 012 keeps that behavior and presents it as the main conversation. Light, dark, and system appearance share one Material 3 theme. Home starts a discussion from a question. Stop asks for confirmation. Custom mode shows the participants and round limit the engine uses. Activity and history read stored records. The discussion foreground service stays a user-started special-use service and does not continue after a force stop. No AI inference request is added. Physical foldable hardware has not been tested.

Milestone 013 keeps schema version 9. Quick, Standard, and Deep Research apply separate round, AI-request, research-request, and source limits. Custom limits are saved and clamped, and they are not raised automatically. Public research connects to the address it approved. PDF characters outside the embedded Helvetica font are omitted and named in the report. Conversation text is not encrypted. OCR is not installed. No AI inference request is added.

Milestone 014 keeps schema version 9. Layout follows the current window width class and any fold feature WindowManager reports. Compact uses one column and bottom navigation. Medium uses the navigation rail. Expanded can show the conversation beside one supporting pane. Changing the window does not start a provider request. A physical Galaxy Z Fold 6 was not tested.

Milestone 015 keeps schema version 9 and the billing gates. Provider settings name the reason automated access is off. Gemini, Cloudflare, OpenRouter, and Groq stay blocked until the owner completes authorization inside the app and the existing checks accept the account. ChatGPT and Claude stay assisted. No live inference request is added by the milestone itself.

Milestone 015A keeps schema version 9. Each provider has an in-app setup screen. Credentials are entered there and stored in Android Keystore. A subscription declaration does not authorize an API. No live inference request is added by the milestone itself.

Milestone 015B keeps schema version 9. The provider screen asks for an API key or official sign-in, then Connect. Technical diagnostics stay under Advanced details. Billing gates stay in place. No live inference request is added by the milestone itself.

Milestone 015C keeps schema version 9. Connect can read Groq models, OpenRouter key limits, Cloudflare accounts and subscriptions, and Gemini project billing after consent. A stored key is not Ready. No live inference request is added by the milestone itself.

## Toolchain pin for Milestone 003

The installed Android SDK includes platforms 34 through 36 and does not include platform 37. Compose BOM 2026.08.00 and later require compile SDK 37, so this project uses Compose BOM 2026.06.01 with compile SDK 36. Android Gradle Plugin 8.13.2 and Gradle 8.14 match that SDK and the installed Android Studio 2025.3.2 build. Newer plugin releases are not required for this milestone.
