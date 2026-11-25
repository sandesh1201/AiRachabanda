# AI Rachabanda

Multiple Perspectives. Collective Intelligence.

**Download the latest testing APK:** [AI-Rachabanda-Latest.apk](https://github.com/sandesh1201/AiRachabanda/raw/master/APK/AI-Rachabanda-Latest.apk)

Versioned APKs stay on [GitHub Releases](https://github.com/sandesh1201/AiRachabanda/releases), including the existing [v0.15.6 Beta](https://github.com/sandesh1201/AiRachabanda/releases/tag/v0.15.6-beta). The file in `APK/` is the current testing build and is replaced in place. Each replacement still increases Git history size.

AI Rachabanda is a local-first native Android app. It keeps a council of AI participants on the device, shows assisted and automated access separately, and can load one on-device LiteRT-LM model at a time. Version 0.16.1 does not send paid API calls, and it does not treat automated online access as operational.

## Overview

Conversations, research state, and credentials stay on the phone. The app does not require an account, show ads, or use a dedicated server. A provider is shown as participating only after an authorized request returns a real response. The app does not invent consensus, citations, or confidence.

## Implemented in 0.16.1

- Provider setup for an API key or the official sign-in path, with secrets stored in the Android Keystore.
- Assisted chat for consumer providers, kept separate from automated API use.
- Connect checks that read account metadata and do not mark unverified access as Ready.
- Home, Chat, Chats, and Settings. Discussion controls appear only while a discussion is running or paused.
- Research, documents, and layouts for compact, medium, and expanded windows, including a reported fold.
- LiteRT-LM 0.20.0 packaged for CPU. The native library loaded on an emulator. No local model has generated a response.

## Supported providers

ChatGPT, Claude, Gemini, Grok, Perplexity, DeepSeek, Microsoft Copilot, Qwen, Mistral, Kimi, Meta AI, Groq, OpenRouter, Cloudflare Workers AI, and a local model.

## Automated and assisted participation

Assisted participation opens the official chat. The app does not generate that response. Automated participation requires a stored credential, a verified free or included plan, and a completed connection test. In 0.16.1 every online provider remains short of Ready. An installed local model is asked inside the chat. ChatGPT opens in the browser, and that sign-in does not connect an automatic reply. A stored key alone does not enable automated inference. There is no paid fallback.

## Local AI

The runtime is LiteRT-LM 0.20.0 on CPU. The GPU artifact is not included.

- Gemma 3 1B Instruct, dynamic int4, stays blocked. Its license has to be accepted by the owner before a trusted SHA-256 can be recorded. The app does not accept that license.
- Qwen2.5 1.5B Instruct, dynamic int8, context 4096, can be downloaded inside the app after you confirm the license, size, and digest. The file is checked before it is installed. The download needs the file size plus 64 MB of free space.
- Model weights are not inside the APK. One model is resident at a time. Enable stays off until the installed file matches its digest.

Native library loading was tested. Actual local model inference was not.

## Zero-cost policy

Zero-cost mode stays on. The app does not purchase credits, call a paid route, or switch to a billed key. Unknown billing blocks automated requests.

## Installation

The testing APK is debug-signed. It is not a Play Store release and it uses the existing Android debug signing identity. A new signing identity was not created.

1. Download [AI-Rachabanda-Latest.apk](https://github.com/sandesh1201/AiRachabanda/raw/master/APK/AI-Rachabanda-Latest.apk) and compare it with `APK/README.md`.
2. The versioned copy remains on [GitHub Releases](https://github.com/sandesh1201/AiRachabanda/releases).
3. On the phone, allow installation from your browser or file app.
4. Install the APK. Android may warn that the certificate is a debug certificate.

Package: `com.airachabanda.app`. Version: 0.16.1. Version code: 32.

## Android requirements

- Android 8.0 or newer (minSdk 26), targeting Android 16 (API 36).
- ARM64 for a Galaxy Z Fold 6. The APK also contains the x86_64 CPU libraries used by the emulator.
- Private app storage large enough for a model before that model can be downloaded.

## Galaxy Z Fold 6

The layout uses window-size classes and keeps controls off a reported hinge. Folded and unfolded use of a physical Fold 6 has not been tested. Emulator measurements are not Fold 6 performance. The phone's Snapdragon 8 Gen 3 class is the intended device for a later on-device test.

## Build

The pinned toolchain is Android Gradle Plugin 8.13.2, Gradle 8.14, Kotlin 2.2.21, and JDK 17. Set `JAVA_HOME` and `ANDROID_HOME`, then:

```
./gradlew :app:assembleRelease
```

The release build is signed with the debug key so a tester can install it. `local.properties` is not part of the repository.

LiteRT-LM 0.20.0 was compiled with Kotlin 2.4 metadata. This project keeps the Kotlin 2.2.21 compiler and passes `-Xskip-metadata-version-check`. Loading the native library shows that the JNI library can start. It does not prove that every Kotlin API in the runtime is compatible with the 2.2.21 standard library. A model inference test is still required before that compatibility is treated as established.

## Known limitations

- No online provider is operational for automated inference.
- No local model has produced a response. The 17 × 23 check has not been run.
- Gemma cannot be downloaded until a trusted SHA-256 is pinned.
- Qwen is about 1.6 GB plus margin and did not fit on the test emulator.
- The testing APK is debug-signed.
- Physical Fold 6 results are pending.
- Office visual compatibility and OCR are not included.

## Privacy and security

Credentials are encrypted with the Android Keystore and are not shown again after they are saved. Backup is disabled. Cleartext traffic is disabled. Logs redact tokens and API keys. Local generation cannot run device code, open arbitrary files, or make its own network requests. Research sends only a query you approve. Do not send passwords, API keys, or one-time codes through an assistant chat.
