# AI Rachabanda — project requirements

Status: active. These requirements are mandatory for every milestone.
Source: project owner instructions, the Milestone 002 architecture review, and the Milestone 003 implementation decision.
Last updated: 23 November 2026.

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

## Toolchain pin for Milestone 003

The installed Android SDK includes platforms 34 through 36 and does not include platform 37. Compose BOM 2026.08.00 and later require compile SDK 37, so this project uses Compose BOM 2026.06.01 with compile SDK 36. Android Gradle Plugin 8.13.2 and Gradle 8.14 match that SDK and the installed Android Studio 2025.3.2 build. Newer plugin releases are not required for this milestone.
