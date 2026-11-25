# Latest testing APK

This folder holds one replaceable file, `AI-Rachabanda-Latest.apk`. A later version replaces that file. Versioned copies stay on GitHub Releases. Replacing the tracked file still leaves the previous blob in Git history, so the repository grows by about the size of each APK.

| Field | Value |
| --- | --- |
| Application | AI Rachabanda |
| Package | `com.airachabanda.app` |
| Version | 0.16.1 |
| Version code | 32 |
| File | `AI-Rachabanda-Latest.apk` |
| Size | 58,341,183 bytes |
| SHA-256 | `8cbc1eb1df08d54d6989b70ed30bae6d6bbd163eae6daf64fac8d6f85a19361a` |
| Signing | Debug-signed with the existing Android debug certificate. APK Signature Scheme v2. This is not a Play Store signature, and no new signing identity was created. |

Earlier testing builds remain on GitHub Releases, including [v0.16.0 Beta](https://github.com/sandesh1201/AiRachabanda/releases/tag/v0.16.0-beta), [v0.15.7 Beta](https://github.com/sandesh1201/AiRachabanda/releases/tag/v0.15.7-beta), and [v0.15.6 Beta](https://github.com/sandesh1201/AiRachabanda/releases/tag/v0.15.6-beta).

## Install

1. Download `AI-Rachabanda-Latest.apk` from this folder.
2. Compare its SHA-256 with the value above.
3. On the phone, allow installation from your browser or file manager.
4. Install the file. Android warns that the certificate is a debug certificate.

Android 8.0 or newer is required. A Galaxy Z Fold 6 needs the ARM64 libraries in this APK. The 0.16.1 layout was exercised on an emulator. It has not been retested on the physical Fold 6.

## Known limitations

- No automatic online provider answers inside the chat. ChatGPT opens in the browser. That sign-in does not connect an automatic reply, and this app does not use a ChatGPT plan.
- An installed, checksum-verified Qwen model is asked inside the chat. No model file was downloaded while building this version, so no live local reply was recorded.
- Gemma stays blocked until its license is accepted and a trusted SHA-256 is pinned.
- Qwen can be downloaded inside the app after confirmation when free space covers 1,597,922,856 bytes plus 64 MB. This APK does not contain model weights.
