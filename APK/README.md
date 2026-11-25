# Latest testing APK

This folder holds one replaceable file, `AI-Rachabanda-Latest.apk`. A later version replaces that file. Versioned copies stay on GitHub Releases. Replacing the tracked file still leaves the previous blob in Git history, so the repository grows by about the size of each APK.

| Field | Value |
| --- | --- |
| Application | AI Rachabanda |
| Package | `com.airachabanda.app` |
| Version | 0.16.0 |
| Version code | 31 |
| File | `AI-Rachabanda-Latest.apk` |
| Size | 58,341,187 bytes |
| SHA-256 | `98113846303a89456cfcadac7e1d6c647e18c737188f7050506af556424606b5` |
| Signing | Debug-signed with the existing Android debug certificate. APK Signature Scheme v2. This is not a Play Store signature, and no new signing identity was created. |

Earlier testing builds remain on GitHub Releases, including [v0.15.7 Beta](https://github.com/sandesh1201/AiRachabanda/releases/tag/v0.15.7-beta) and [v0.15.6 Beta](https://github.com/sandesh1201/AiRachabanda/releases/tag/v0.15.6-beta).

## Install

1. Download `AI-Rachabanda-Latest.apk` from this folder.
2. Compare its SHA-256 with the value above.
3. On the phone, allow installation from your browser or file manager.
4. Install the file. Android warns that the certificate is a debug certificate.

Android 8.0 or newer is required. A Galaxy Z Fold 6 needs the ARM64 libraries in this APK. The 0.16.0 layout was exercised on an emulator, including a widened window. It has not been retested on the physical Fold 6.

## Known limitations

- Home, Chat, Chats, and Settings replace the previous five-tab layout. Discussion controls appear while a discussion is running or paused. Finish now is in the chat menu.
- LiteRT-LM 0.20.0 is packaged for CPU. The GPU artifact is excluded. No local model has generated a response.
- Loading the native library does not prove full compatibility. The runtime was built with Kotlin 2.4 metadata, and this app compiles with Kotlin 2.2.21 using `-Xskip-metadata-version-check`.
- Gemma stays blocked until its license is accepted and a trusted SHA-256 is pinned.
- Qwen can be downloaded inside the app after confirmation when free space covers 1,597,922,856 bytes plus 64 MB. This APK does not contain model weights.
- No online provider is operational for automated inference.
- The physical Fold 6 checklist for this version has not been filled in.
