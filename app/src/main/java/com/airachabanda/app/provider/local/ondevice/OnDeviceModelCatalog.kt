package com.airachabanda.app.provider.local.ondevice

/**
 * Two instruction-tuned families. Sizes and speeds are the published measurements.
 * These models are not equivalent to frontier cloud models.
 */
object OnDeviceModelCatalog {
    val GEMMA = OnDeviceModel(
        id = "gemma3-1b-it-int4-1280",
        family = "Gemma",
        displayName = "Gemma 3 1B Instruct",
        fileName = "dynamic int4, block size 128, context 1280",
        repositoryUrl = "https://huggingface.co/litert-community/Gemma3-1B-IT",
        licenseName = "Gemma Terms of Use",
        licenseUrl = "https://ai.google.dev/gemma/terms",
        sizeBytes = 657L * 1024 * 1024,
        sizeLabel = "657 MB",
        contextTokens = 1280,
        publishedDevice = "Samsung Galaxy S24 Ultra (Snapdragon 8 Gen 3)",
        publishedDecode = "50 tokens/second on CPU, dynamic int4, context 1280",
        publishedPeakRamBytes = 982L * 1024 * 1024,
        publishedPeakRamLabel = "982 MB CPU RSS on that S24 Ultra measurement",
        qualityNote = "A 1 billion parameter instruction model. Useful for short on-device answers. It is not a frontier cloud model.",
    )

    val QWEN = OnDeviceModel(
        id = "qwen2.5-1.5b-instruct-q8-4096",
        family = "Qwen",
        displayName = "Qwen2.5 1.5B Instruct",
        fileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
        repositoryUrl = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct",
        licenseName = "Apache 2.0",
        licenseUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct",
        sizeBytes = 1598L * 1024 * 1024,
        sizeLabel = "1598 MB",
        contextTokens = 4096,
        publishedDevice = "Samsung Galaxy S25 Ultra",
        publishedDecode = "34.25 tokens/second on CPU, dynamic int8, context 1280 measurement. The file context is 4096.",
        publishedPeakRamBytes = 2216L * 1024 * 1024,
        publishedPeakRamLabel = "2216 MB peak RSS on CPU, context 4096, on that S25 Ultra measurement",
        qualityNote = "A 1.5 billion parameter instruction model from a different family than Gemma. It is not a frontier cloud model. The speed above was not measured on a Fold 6.",
    )

    val recommended: List<OnDeviceModel> = listOf(GEMMA, QWEN)

    fun find(id: String): OnDeviceModel? = recommended.firstOrNull { it.id == id }

    fun compatible(model: OnDeviceModel): Boolean =
        model.family.isNotBlank() &&
            model.sizeBytes > 0 &&
            model.contextTokens in 1..8192 &&
            model.licenseName.isNotBlank() &&
            model.id in recommended.map { it.id }
}

data class OnDeviceModel(
    val id: String,
    val family: String,
    val displayName: String,
    val fileName: String,
    val repositoryUrl: String,
    val licenseName: String,
    val licenseUrl: String,
    val sizeBytes: Long,
    val sizeLabel: String,
    val contextTokens: Int,
    val publishedDevice: String,
    val publishedDecode: String,
    val publishedPeakRamBytes: Long,
    val publishedPeakRamLabel: String,
    val qualityNote: String,
)
