# Milestone 015E

LiteRT-LM 0.20.0 is the pinned Android release. Its AAR digest is recorded and the artifact is not in this build. CPU is the selected backend. The published POM also names a GPU artifact, and that artifact is not installed.

The CPU Gemma file is `Gemma3-1B-IT_multi-prefill-seq_q4_ekv4096.litertlm`, revision `a9bd85643f05cdb1e5cd5af7ba4e74b0e8210ca0`, 584,417,280 bytes. Hugging Face gates that repository on the Gemma license. No SHA-256 was published to an unauthenticated request, and none was invented. The Snapdragon NPU file is not used.

The Qwen file is `Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm`, revision `60257f9f1d00ca1f24cc5ce3b829b4139be1ca2b`, 1,597,922,856 bytes, SHA-256 `c4a9b7289365ab80fe599b844b77ad253eb3c53fb0e5d963d699146a6f01255e`.

No runtime or model file was transferred. No live inference was sent.

On the emulator, confirming Gemma reported that no trusted SHA-256 is pinned and nothing was downloaded. Confirming Qwen reported that free space is short of 1,597,922,856 bytes plus 64 MB. Enable stayed off. Those emulator measurements are not Fold 6 performance.
