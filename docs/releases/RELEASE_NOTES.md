# AI Rachabanda v0.15.6 Beta

Private testing build. The APK is signed with the existing Android debug certificate. It is not a Play Store release, and a new signing identity was not created.

## This build

- Package `com.airachabanda.app`, version 0.15.6, versionCode 29.
- LiteRT-LM 0.20.0 is packaged for CPU. The GPU artifact is excluded. ARM64 and x86_64 native libraries are included.
- The CPU library loaded on the test emulator. No local model generated a response.
- Gemma stays blocked until its license is accepted by the owner and a trusted SHA-256 is pinned.
- Qwen can be downloaded inside the app after an explicit confirmation, when free space covers 1,597,922,856 bytes plus 64 MB. The digest is checked before installation. This publication did not download the weights.
- Online providers can be configured with a key or official sign-in. None are operational for automated inference.
- Council controls remain Pause, Resume, Stop, Finish Now, Skip, and Add Instruction.

## Compatibility note

LiteRT-LM 0.20.0 ships Kotlin 2.4 metadata. The app compiler remains Kotlin 2.2.21 and uses `-Xskip-metadata-version-check`, with the Kotlin standard library held at 2.2.21. Native library loading does not establish full Kotlin compatibility. Inference against an installed model is the missing check.

## Install

Download `AI-Rachabanda-v0.15.6-beta.apk` from this release and compare it with `SHA256SUMS.txt`. Android will identify the certificate as a debug certificate.
