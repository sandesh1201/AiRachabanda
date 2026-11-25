# Latest testing APK

This folder holds one replaceable file, `AI-Rachabanda-Latest.apk`. A later version replaces that file. Versioned copies stay on GitHub Releases. Replacing the tracked file still leaves the previous blob in Git history, so the repository grows by about the size of each APK.

| Field | Value |
| --- | --- |
| Application | AI Rachabanda |
| Package | `com.airachabanda.app` |
| Version | 0.15.7 |
| Version code | 30 |
| File | `AI-Rachabanda-Latest.apk` |
| Size | 58,324,803 bytes |
| SHA-256 | `e0a8163a1b6b60edf1263cdbdd46ea82238930d8d749e65b8c94cf492d0dd093` |
| Signing | Debug-signed with the existing Android debug certificate. APK Signature Scheme v2. This is not a Play Store signature, and no new signing identity was created. |

The previous testing build remains at [v0.15.6 Beta](https://github.com/sandesh1201/AiRachabanda/releases/tag/v0.15.6-beta). Its SHA-256 is `42a31a4a7f96b6687431a58c67cd434990481a968152965b2c373781eac0cf91`.

## Install

1. Download `AI-Rachabanda-Latest.apk` from this folder.
2. Compare its SHA-256 with the value above.
3. On the phone, allow installation from your browser or file manager.
4. Install the file. Android warns that the certificate is a debug certificate.

Android 8.0 or newer is required. A Galaxy Z Fold 6 needs the ARM64 libraries in this APK. Folded and unfolded use of that phone has not been tested.

## Known limitations

- LiteRT-LM 0.20.0 is packaged for CPU. The GPU artifact is excluded. The CPU library loaded on an emulator. No local model has generated a response.
- Loading the native library does not prove full compatibility. The runtime was built with Kotlin 2.4 metadata, and this app compiles with Kotlin 2.2.21 using `-Xskip-metadata-version-check`.
- Gemma stays blocked until its license is accepted and a trusted SHA-256 is pinned.
- Qwen can be downloaded inside the app after confirmation when free space covers 1,597,922,856 bytes plus 64 MB. This APK does not contain model weights.
- No online provider is operational for automated inference.
- The physical-device checklist for this testing line is still pending.
