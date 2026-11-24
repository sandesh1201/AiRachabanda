package com.airachabanda.app.provider.local.ondevice

import java.io.File

/**
 * Decides whether a local generation may start. It does not load a native library.
 */
object OnDevicePreflight {
    fun modelDirectory(filesDir: File): File = File(filesDir, "ondevice-models")

    fun verifiedFile(directory: File, model: OnDeviceModel): File? {
        val file = File(directory, model.fileName)
        if (!file.isFile) return null
        if (!file.name.endsWith(".litertlm") || file.name.contains("sm8650", ignoreCase = true)) return null
        val expected = model.sha256?.trim()?.lowercase().orEmpty()
        if (expected.isEmpty()) return null
        return if (OnDeviceModelManager.sha256(file) == expected) file else null
    }

    fun estimatedPromptTokens(prompt: String): Int = (prompt.length + 3) / 4

    fun refusal(
        model: OnDeviceModel?,
        file: File?,
        prompt: String,
        task: LocalTask,
        availableRamBytes: Long,
        thermal: ThermalLevel,
        accept: Boolean,
        scheduler: OnDeviceScheduler,
    ): LocalGenerationResult? {
        if (model == null) {
            return LocalGenerationResult("", "", fixture = false, cancelled = false, failure = "That model is not in the recommended catalog.")
        }
        if (!accept) {
            return LocalGenerationResult(model.id, "", fixture = false, cancelled = true, failure = "Generation was cancelled before a model ran. The late response was rejected.")
        }
        if (prompt.isBlank()) {
            return LocalGenerationResult(model.id, "", fixture = false, cancelled = false, failure = "The prompt was empty. No model file was read.")
        }
        if (file == null || !file.isFile) {
            return LocalGenerationResult(model.id, "", fixture = false, cancelled = false, failure = "LiteRT-LM ${OnDeviceRuntime.PINNED_VERSION} is packaged for CPU. No verified model file is installed, so no tokens were generated.")
        }
        val expected = model.sha256?.trim()?.lowercase().orEmpty()
        if (expected.isEmpty() || OnDeviceModelManager.sha256(file) != expected) {
            return LocalGenerationResult(model.id, "", fixture = false, cancelled = false, failure = "The model file is unverified. It was not loaded.")
        }
        if (thermal == ThermalLevel.SEVERE) {
            return LocalGenerationResult(model.id, "", fixture = false, cancelled = false, failure = "The device is thermally limited. Generation was not started.")
        }
        val scheduled = scheduler.schedule(model.id, availableRamBytes, thermal)
        if (!scheduled.loaded) {
            return LocalGenerationResult(model.id, "", fixture = false, cancelled = false, failure = scheduled.detail)
        }
        val bounds = scheduler.limitsFor(model.id, task, estimatedPromptTokens(prompt))
        if (!bounds.accepted) {
            scheduler.unload(model.id)
            return LocalGenerationResult(model.id, "", fixture = false, cancelled = false, failure = bounds.detail)
        }
        return null
    }
}
