# Physical-device checklist — v0.15.6 Beta

Results below are pending. They are not emulator results, and they have not been filled in from a Galaxy Z Fold 6.

- [ ] Install `AI-Rachabanda-v0.15.6-beta.apk` and confirm the debug-signing warning.
- [ ] Confirm version 0.15.6.
- [ ] Folded layout.
- [ ] Unfolded layout, including controls away from a hinge.
- [ ] Light theme.
- [ ] Dark theme.
- [ ] Provider setup with an API key or official sign-in. Do not record the key.
- [ ] Local AI shows Gemma blocked and Qwen size, license, and SHA-256.
- [ ] Download Qwen only with enough free space and an explicit confirmation. Confirm the digest before Enable turns on.
- [ ] Leave Gemma blocked.
- [ ] Offline inference after the model is installed, including 17 × 23.
- [ ] Model switching and unloading.
- [ ] Council discussion with an eligible local model.
- [ ] Devil's Advocate, labeled self-critique when the same model reviews itself.
- [ ] Pause and Stop, including cancellation of local generation.
- [ ] Background behavior while a discussion is running.
- [ ] Document generation.
- [ ] Memory use during load and generation.
- [ ] Battery and thermal behavior. Severe thermal status should refuse a load.
- [ ] Crashes or native errors, including any failure that indicates the Kotlin 2.2.21 and LiteRT-LM 2.4 metadata combination is incompatible.

Emulator note already recorded: the CPU library loaded, and no model inference was run.
