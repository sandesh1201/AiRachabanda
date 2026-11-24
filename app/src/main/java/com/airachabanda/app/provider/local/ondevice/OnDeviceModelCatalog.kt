package com.airachabanda.app.provider.local.ondevice

/**
 * Two instruction-tuned families. Sizes and speeds are the published measurements.
 * These models are not equivalent to frontier cloud models.
 */
object OnDeviceModelCatalog {
    val GEMMA = OnDeviceModel(
        id = "gemma3-1b-it-q4-ekv4096",
        family = "Gemma",
        displayName = "Gemma 3 1B Instruct",
        fileName = "Gemma3-1B-IT_multi-prefill-seq_q4_ekv4096.litertlm",
        repositoryUrl = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/a9bd85643f05cdb1e5cd5af7ba4e74b0e8210ca0/Gemma3-1B-IT_multi-prefill-seq_q4_ekv4096.litertlm",
        licenseName = "Gemma Terms of Use",
        licenseUrl = "https://ai.google.dev/gemma/terms",
        sizeBytes = 584_417_280L,
        sizeLabel = "584,417,280 bytes",
        contextTokens = 4096,
        revision = "a9bd85643f05cdb1e5cd5af7ba4e74b0e8210ca0",
        sha256 = null,
        publishedDevice = "Samsung Galaxy S24 Ultra (Snapdragon 8 Gen 3)",
        publishedDecode = "50 tokens/second on CPU was published for a different int4, context-1280 measurement.",
        publishedPeakRamBytes = 982L * 1024 * 1024,
        publishedPeakRamLabel = "982 MB CPU RSS was published for a different int4, context-1280 measurement, not for this file.",
        qualityNote = "A 1 billion parameter instruction model. It is not a frontier cloud model. The Snapdragon NPU file is not used.",
    )

    val QWEN = OnDeviceModel(
        id = "qwen2.5-1.5b-instruct-q8-4096",
        family = "Qwen",
        displayName = "Qwen2.5 1.5B Instruct",
        fileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
        repositoryUrl = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/60257f9f1d00ca1f24cc5ce3b829b4139be1ca2b/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
        licenseName = "Apache 2.0",
        licenseUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct",
        sizeBytes = 1_597_922_856L,
        sizeLabel = "1,597,922,856 bytes",
        contextTokens = 4096,
        revision = "60257f9f1d00ca1f24cc5ce3b829b4139be1ca2b",
        sha256 = "c4a9b7289365ab80fe599b844b77ad253eb3c53fb0e5d963d699146a6f01255e",
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
    val revision: String,
    val sha256: String?,
    val publishedDevice: String,
    val publishedDecode: String,
    val publishedPeakRamBytes: Long,
    val publishedPeakRamLabel: String,
    val qualityNote: String,
)
