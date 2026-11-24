package com.airachabanda.app.provider.local.ondevice

/**
 * One on-device runtime. The native library is not packaged and is not downloaded here.
 * LiteRT-LM is selected because Google publishes an Android artifact, CPU and GPU backends,
 * a Snapdragon 8 Gen 3 measurement for Gemma, and LiteRT conversions for a second model family.
 */
object OnDeviceRuntime {
    const val NAME = "LiteRT-LM"
    const val MAVEN_COORDINATE = "com.google.ai.edge.litertlm:litertlm-android"
    const val MINIMUM_VERSION = "0.18.0"
    const val PINNED_VERSION = "0.20.0"
    const val AAR_SHA256 = "9cd2699d796a7120ff567316de85e797250cc10b2b67cb616c48567b719e7ed8"
    const val LICENSE_NAME = "Apache-2.0"
    const val BACKEND = "CPU"
    const val PACKAGED = false
    const val OWNER_DOWNLOAD_APPROVED = false
    const val NETWORK_PERMITTED = false
    const val TARGET_DEVICE = "Samsung Galaxy Z Fold 6"
    const val TARGET_RAM_BYTES = 12L * 1024 * 1024 * 1024
    const val MAX_OUTPUT_TOKENS = 256
    const val GENERATION_TIMEOUT_MS = 30_000L
    const val STORAGE_MARGIN_BYTES = 64L * 1024 * 1024
    const val RAM_HEADROOM_BYTES = 512L * 1024 * 1024

    enum class Candidate { LITERT_LM, EXECUTORCH, LLAMA_CPP }

    data class Assessment(val candidate: Candidate, val selected: Boolean, val reason: String)

    val assessments: List<Assessment> = listOf(
        Assessment(
            Candidate.LITERT_LM,
            selected = true,
            reason = "Official Android Maven artifact, CPU and GPU backends, cancelProcess, and published Gemma and Qwen LiteRT files. The SM8650 table matches the Fold 6 SoC.",
        ),
        Assessment(
            Candidate.EXECUTORCH,
            selected = false,
            reason = "The Android LLM Java API is experimental. Published phone numbers are mainly Llama on CPU. A second family would be a separate export path.",
        ),
        Assessment(
            Candidate.LLAMA_CPP,
            selected = false,
            reason = "MIT-licensed and broad GGUF coverage. The Android binding is an example the app would have to build and maintain, and its documented acceleration is CPU rather than a packaged GPU or NPU backend.",
        ),
    )

    val selected: Assessment = assessments.first { it.selected }
}
