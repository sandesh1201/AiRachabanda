# Milestone 003 — Android foundation

Date: 23 November 2026.
Status: PARTIAL.
Scope: native Android foundation, assisted Claude and ChatGPT participation, provider contracts, local persistence, and zero-cost policy. No accounts were created. No live inference request was sent. No remote repository was created.

## 1. Executive summary

AI Rachabanda now has a native Kotlin and Jetpack Compose application that builds a debug APK, stores conversations on the device, and walks a user through assisted Claude and ChatGPT participation. Automated inference stays off for every provider. Claude remains a mandatory Phase 1 provider through the official site and user-imported responses. ChatGPT Sign in with ChatGPT is prepared from the official plan-usage documents and is not executed.

The Pixel 9a emulator booted, the debug APK installed, and the main flows were exercised: navigation, dark appearance, conversation create/rename/persist, Claude assisted prompt, pasted import, declared paid plan, zero-cost screen, and the activity log. A foldable device and a foldable emulator were not available. Compact bottom navigation was not captured because the emulator window was wide.

## 2. Architecture implemented

One Gradle module, `:app`, package `com.airachabanda.app`. Manual construction in `AppContainer`. No Hilt.

Package boundaries:

- `core.model` — conversations, messages, providers, billing, participation, research tasks.
- `provider` — registry, verified plan names, OpenRouter route policy, ChatGPT plan-usage preparation, Claude assisted links.
- `security` — redacted logging and a credential-store interface.
- `persistence` — Room database `airachabanda.db` version 1, repository, DataStore preferences.
- `orchestration` — zero-cost policy and assisted import rules.
- `ui` — Material 3 theme, five destinations, council, history, activity, settings, import.

Participation mode is separate from account choice. Modes are Automated, Assisted, and Unavailable. Assisted work uses `AWAITING_USER_RESPONSE`. Skip and defer do not create a message. Import stores `userSupplied = true` and `authenticated = false`, with message source `USER_SUPPLIED_PROVIDER_ATTRIBUTED`.

Zero-Cost Mode is mandatory. There is no control that turns on paid inference. `ZeroCostPolicy` allows automated calls only for `VERIFIED_FREE` or `INCLUDED_SUBSCRIPTION_VERIFIED`, and even those states return false for Claude and ChatGPT in this version.

## 3. Exact dependency versions

Process environment only. `JAVA_HOME` was `C:\Program Files\Android\Android Studio\jbr` (OpenJDK 21.0.9). `ANDROID_HOME` and `ANDROID_SDK_ROOT` were `C:\Users\sande\AppData\Local\Android\Sdk`. These paths are not written into committed Gradle files. `local.properties` holds `sdk.dir` and is gitignored.

| Component | Version |
| --- | --- |
| Android Gradle Plugin | 8.13.2 |
| Gradle wrapper | 8.14 bin |
| Kotlin | 2.2.21 |
| KSP | 2.2.21-2.0.5 |
| Compose BOM | 2026.06.01 |
| compileSdk / targetSdk | 36 |
| minSdk | 26 |
| JVM target | 17 |
| versionName / versionCode | 0.3.0 / 3 |
| core-ktx | 1.16.0 |
| activity-compose | 1.10.1 |
| lifecycle | 2.8.7 |
| navigation-compose | 2.8.9 |
| Room | 2.7.2 |
| DataStore preferences | 1.1.7 |
| coroutines | 1.10.2 |
| browser (Custom Tabs) | 1.8.0 |
| JUnit | 4.13.2 |
| Robolectric | 4.14.1 |
| AndroidX test core | 1.6.1 |
| AndroidX test JUnit | 1.2.1 |
| Espresso | 3.6.1 |

Compose BOM 2026.08 and later need compile SDK 37, which is not installed. Newer Android Gradle Plugin 9.x is outside the installed Android Studio 2025.3.2 line. The pin is recorded in `docs/PROJECT_REQUIREMENTS.md`. Lint checks that only announce newer versions are disabled for that reason.

## 4. Files created or modified

Preserved:

- `docs/PROJECT_REQUIREMENTS.md` (updated for the Milestone 003 decision and toolchain pin; requirements body kept).
- `docs/milestones/002-feasibility-report.md` (status note only; findings unchanged).

Added: the Gradle project (`settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, wrapper), the `:app` source under `app/src/main/java/com/airachabanda/app`, Room schema `app/schemas/.../1.json`, unit tests, resources, `.gitignore`, and this report. `local.properties` exists on this machine and is ignored.

## 5. Claude integration status

Assisted participation is implemented. Official site: `https://claude.ai`. Actions present: Open Claude, Prepare Research Prompt, Copy Prompt, Import Response, Add to AI Council, Subscription Plan, Integration Status, and Usage & Limits.

Opening the site uses an HTTPS Custom Tab. The confirmation text says opening a page does not verify a login. Imported text is user-supplied and provider-attributed. The app registers an `ACTION_SEND` `text/plain` share target. A share from the Claude app itself was not performed on the emulator. Manual paste was used and linked to conversation `Council notes`, provider Claude, round 1, and the waiting research task. After import the task state was "Response imported".

Automated Claude inference is off. The official linked-billing article describes a Claude Console organization that pays Anthropic directly. Subscription plans cannot be linked, and a zero spend cap is still a billing path. That path is not enabled. Sources: `https://code.claude.com/docs/en/legal-and-compliance` and `https://support.claude.com/en/articles/18467148-use-your-claude-platform-account-in-a-third-party-app`.

No consumer OAuth extraction, cookie extraction, private endpoint, browser automation, DOM scraping, accessibility automation, Claude Code impersonation, or subscription bypass was implemented.

## 6. ChatGPT integration status

ChatGPT stays mandatory and Assisted. Official site: `https://chatgpt.com`. The same assisted actions as Claude are available. The emulator pass used Claude for the paste; the import screen also offers ChatGPT.

`ChatGptPlanUsage` builds the documented authorization URL: PKCE S256, `dynamic_agent_client`, loopback `http://127.0.0.1:<port>/auth/callback`, and the documented scopes and resource. `assertLiveExchangeDisabled()` fails closed. No token request and no `/v1/responses` call were made. The app will not switch to a billed OpenAI API key. Automation is disabled until credit safeguards exist. It is not classified as impossible.

## 7. Other provider statuses

| Provider | Kind | Mode | Billing state | Automated calls |
| --- | --- | --- | --- | --- |
| ChatGPT | Consumer product | Assisted | Unknown until configured | Disabled |
| Claude | Consumer product | Assisted | Unknown until configured | Disabled |
| Gemini | Inference service | Unavailable | User configuration required | Disabled |
| Groq | Inference service | Unavailable | User configuration required | Disabled |
| OpenRouter | Inference service | Unavailable | User configuration required | Disabled |
| Cloudflare Workers AI | Inference service | Unavailable | User configuration required | Disabled |
| Grok, Perplexity, DeepSeek, Microsoft Copilot, Qwen, Mistral, Kimi, Meta AI | Consumer product | Unavailable | Unknown | Disabled |

OpenRouter automation requires a numeric key limit of 0 and a model id of `openrouter/free` or an id ending in `:free`, with an empty fallback list. `PROVEN_BY_LIVE_CHECK` is false, so a matching shape still returns `automatedCallPermitted = false`. No billing-verification endpoint was invented. No live metadata call was made.

Verified paid plan names shown after the user selects Paid Subscription: ChatGPT Go, Plus, Pro, Business; Claude Pro, Max, Ultra, Team, Enterprise. Other providers show that no verified catalog is stored. Saving a plan writes a declaration and does not change billing eligibility or the automated-inference flag.

## 8. UI implementation status

Five destinations: Home, AI Council, Activity, History, Settings. Light, dark, and system appearance. Wide windows use a navigation rail. Compact windows use a bottom bar in code; that compact window was not captured on the emulator. The Pixel 9a window was 1920×1080, so the rail and the two-pane council were what ran. Galaxy Z Fold 6 was not tested. No foldable emulator is installed.

Home shows all 14 providers. ChatGPT and Claude are in a primary section with Open and AI Council actions. Inference services are labeled separately and offer Account.

AI Council includes New Chat, research mode chips (Quick, Standard, Deep Research, Custom), a message field, a disabled Attach action, discussion, Final Answer empty state, and assisted tasks. The empty automated-discussion sentence is on Home.

Settings includes every requested section. Zero-Cost Mode has no paid toggle. Notifications say local notifications are not available. About states version 0.3.0 and that this build does not send live AI requests.

Emulator evidence is in `docs/milestones/003-screenshots/`.

## 9. Persistence implementation status

Room version 1, `exportSchema = true`, no destructive migration. Tables: conversations, messages, provider_configs, research_sessions, research_tasks, imported_responses, workflow_events, attachment_metadata. Imported responses reference a task id. Import of the response, message, task completion, and workflow event runs in one Room transaction. A new research session copies the conversation's research mode.

History supports create, open, rename, and delete. The emulator created "Council check", renamed it to "Council notes", stored a local user message, stored the Claude import, and showed those rows again after a force-stop and reinstall.

DataStore stores appearance, default research mode, and a maximum-round preference. Changing the round preference does not start research. Attachment metadata has a table and no writer.

## 10. Security implementation status

`CredentialStore` isolates aliases as `airachabanda.{provider}.{account}`. `AndroidKeystoreGateway.write` throws and does not store a secret. `read` returns null. No provider credential is stored in this version. `RedactedLog` redacts bearer tokens, common secret fields, and `sk-` keys. Network security config denies cleartext and trusts system anchors. `usesCleartextTraffic` is false. Backup and device-transfer rules exclude database, shared preferences, files, and external storage. `allowBackup` is false. Deleting local data removes conversations, events, and declared preferences, then reseeds provider rows. A future ChatGPT loopback listener is not implemented, so no cleartext exception was added.

## 11. Exact build commands and results

Environment for every command:

```
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "C:\Users\sande\AppData\Local\Android\Sdk"
$env:ANDROID_SDK_ROOT = "C:\Users\sande\AppData\Local\Android\Sdk"
```

Wrapper check:

```
.\gradlew.bat --version
```

Result: Gradle 8.14. Launcher JVM 21.0.9 (JetBrains s.r.o. 21.0.9+-14787801-b1163.94). Daemon JVM is the Android Studio JBR.

First combined build compiled the app and passed unit tests, then failed lint because `local.properties` did not escape the Windows path. That file was corrected to `sdk.dir=C\:\\Users\\sande\\AppData\\Local\\Android\\Sdk`.

Final APK and lint, after the council scroll and navigation-rail fixes:

```
.\gradlew.bat :app:assembleDebug :app:lintDebug
```

Result: `BUILD SUCCESSFUL`. Lint report: `No issues found.`

## 12. Exact test commands and results

```
.\gradlew.bat :app:testDebugUnitTest
```

Final run on the source that produced the APK: `BUILD SUCCESSFUL in 17s`. 8 tests, 0 failures, 0 errors, 0 skipped.

`CouncilRepositoryTest` (Robolectric SDK 33):

- `assistedImportIsUserSuppliedAndSkipDoesNotInventAResponse`
- `conversationRenameDeleteAndAccountDeclarationDoNotEnableAutomation`

`PolicyTest`:

- `chatGptAuthorizationIsPreparedAndLiveExchangeIsDisabled`
- `openRouterRejectsPaidFallbacksAndUnprovenZeroLimit`
- `verifiedFreeCanBeEligibleExceptMandatoryAssistedProviders`
- `zeroCostBlocksUnknownPaidAndUnverifiedSubscriptionStates`
- `assistedImportRejectsBlankTextAndUnsupportedProviders`
- `logsRedactCredentials`

No `androidTest` instrumentation suite was added. The emulator session below covered the UI flows.

## 13. Emulator test results

Performed on the installed Pixel 9a AVD. Emulator 37.2.12.0. First launch reached `sys.boot_completed=1`, then the process exited with code 1 while a Gradle build and the emulator were competing for memory. The emulator warned that the suggested host RAM is 16384 MiB and 16075 MiB was available. The retry used `-memory 2048 -no-snapshot -gpu swiftshader_indirect` and reported boot completed in 57987 ms.

`adb install -r` of the debug APK returned Success. `com.airachabanda.app/.MainActivity` launched.

Exercised on device:

- Home rail, ChatGPT and Claude cards, unavailable inference-service cards.
- History empty state, create "Council check", rename to "Council notes", persistence across force-stop.
- Council two-pane layout, local message "Local note", Claude prompt, task state "Waiting for your response", copy/import/skip/defer after the pane was made scrollable.
- Import screen, paste "Claude reply via paste", stored as user-supplied and attributed to Claude. Task became "Response imported".
- Dark appearance, persisted on the next screen.
- Claude paid declaration showing Pro, Max, Ultra, Team, and Enterprise. Activity recorded that the declaration does not verify a subscription or enable automation.
- Zero-Cost Mode screen with no paid control.
- Activity list containing only stored workflow events.

System UI showed "System UI isn't responding" once during the first moments. The app process remained the resumed activity afterward. The navigation rail was missing on an open conversation until that route was treated as the Council destination; the reinstall confirmed Home and Settings stay visible beside "Council notes".

Galaxy Z Fold 6 was not tested. No foldable emulator exists. The compact bottom bar was not shown because the window width was expanded.

## 14. APK path and size

`C:\Users\sande\Desktop\AIAPP\AIRachabanda\app\build\outputs\apk\debug\app-debug.apk`

20,434,743 bytes. The APK is gitignored.

## 15. Known limitations

- Automated inference is off for every provider, including candidates with a documented free tier.
- OpenRouter's numeric limit of 0 is a rule, and it is unproven until a live key-metadata read is authorized.
- The Keystore gateway refuses writes. It is the storage boundary, and it does not encrypt a secret yet.
- The emulator window was wide. Compact bottom navigation and a folded phone layout were not captured.
- No foldable hardware test.
- A share originating in the Claude or ChatGPT app was not sent into AI Rachabanda. The share target and paste path exist.
- Attachments have a disabled button and an empty metadata table.
- Final Answer has an empty state. No consensus or document generation runs.
- The activity log can contain more than one account-declaration event if the user saves more than once. That matches the saves performed during the emulator pass.
- Host RAM is below the emulator's suggested 16 GiB. Software rendering was required.

## 16. Unimplemented functionality

- Live ChatGPT OAuth, token storage, and `/v1/responses`.
- Live Gemini, Groq, OpenRouter, and Cloudflare calls, including key-metadata reads.
- An automated discussion engine that continues while an assisted response is pending.
- Document generation, image input, and attachment bytes.
- Local notifications and background provider work.
- Backup and restore as a user feature. Cloud backup is excluded.
- Production Keystore encryption of a real credential.
- Instrumentation tests.
- A foldable layout verified on Galaxy Z Fold 6 hardware.

## 17. Screenshots

Captured from the Pixel 9a emulator during this milestone:

- `docs/milestones/003-screenshots/01-home.png`
- `docs/milestones/003-screenshots/02-history.png`
- `docs/milestones/003-screenshots/03-council.png`
- `docs/milestones/003-screenshots/04-import.png`
- `docs/milestones/003-screenshots/05-imported.png`
- `docs/milestones/003-screenshots/06-appearance-dark.png`
- `docs/milestones/003-screenshots/07-activity.png`
- `docs/milestones/003-screenshots/08-claude-plans.png`
- `docs/milestones/003-screenshots/09-council-with-rail.png`

## 18. Actions required from the project owner

- Keep ChatGPT credit use off, the app limit below 100 percent, and automatic credit purchase off before any future plan-usage test.
- Decide whether the application will be open source before a Sign in with ChatGPT build is distributed. The documented public flow is for open-source or personal local use.
- Leave Gemini billing unlinked if the free tier is the intended path.
- Confirm Groq Free and Cloudflare Workers Free in those consoles before any later automation milestone.
- Create an OpenRouter key with a numeric limit of 0 only when a metadata read is explicitly authorized.
- Do not authorize a live inference request until a later milestone says which provider and which request are allowed.
- A physical Galaxy Z Fold 6, or a foldable emulator, is required before folded and unfolded behavior can be reported as tested.

## 19. Recommended next milestone

Milestone 004 should stay inside the zero-cost rule. Recommended scope: instrument the compact window and a real `ACTION_SEND` import; implement Keystore encryption only for a credential the owner has authorized the app to hold; and, only with a separate owner authorization, perform one non-inference metadata check such as OpenRouter `GET /api/v1/key` or a Gemini free-tier confirmation. ChatGPT `/v1/responses` stays disabled until the application can refuse credit spend. Do not start that milestone from this report.

## 20. Final status

PARTIAL.

The Android foundation, debug APK, unit tests, lint, local persistence, assisted Claude path, assisted ChatGPT path, and zero-cost policy are in place and the Pixel 9a emulator ran the main flows. Automated inference, live OAuth, Keystore encryption of a real secret, compact-window evidence, and foldable-device evidence are still outstanding.
