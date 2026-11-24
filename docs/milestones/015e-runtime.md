# Milestone 015E

LiteRT-LM 0.20.0 is the pinned Android release. The owner approved that runtime. CPU is the selected backend. The published POM also names a GPU artifact, and that artifact is excluded.

The CPU Gemma file is `Gemma3-1B-IT_multi-prefill-seq_q4_ekv4096.litertlm`, revision `a9bd85643f05cdb1e5cd5af7ba4e74b0e8210ca0`, 584,417,280 bytes. Hugging Face gates that repository on the Gemma license. No SHA-256 was published to an unauthenticated request, and none was invented. The Snapdragon NPU file is not used.

The Qwen file is `Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm`, revision `60257f9f1d00ca1f24cc5ce3b829b4139be1ca2b`, 1,597,922,856 bytes, SHA-256 `c4a9b7289365ab80fe599b844b77ad253eb3c53fb0e5d963d699146a6f01255e`.

The owner approved the LiteRT-LM 0.20.0 runtime only. The downloaded AAR is 9,850,771 bytes and its SHA-256 matches the published digest `9cd2699d796a7120ff567316de85e797250cc10b2b67cb616c48567b719e7ed8`. The APK contains `liblitertlm_jni.so` and `libLiteRt.so` for arm64-v8a and x86_64. The GPU artifact was not downloaded. On the emulator the CPU library loaded. Gemma and Qwen weights were not transferred. No model generated a response.

On the emulator, confirming Gemma reported that no trusted SHA-256 is pinned and nothing was downloaded. Confirming Qwen reported that free space is short of 1,597,922,856 bytes plus 64 MB. Enable stayed off. Those emulator measurements are not Fold 6 performance.
