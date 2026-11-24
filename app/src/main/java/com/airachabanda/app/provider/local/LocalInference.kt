package com.airachabanda.app.provider.local

/**
 * Optional loopback inference. The Windows computer is not required for assisted chat.
 * This type never opens a socket and never selects a cloud model.
 */
object LocalInferencePolicy {
    const val MAX_CONTEXT_TOKENS = 4096
    const val MAX_TIMEOUT_MS = 120_000L
    val FIRST_MODEL = "gemma3:1b"
    val RECOMMENDED_MODELS = setOf(FIRST_MODEL, "llama3.2:1b", "llama3.2:3b")

    data class Selection(
        val modelId: String,
        val endpointHost: String,
        val cloudDisabled: Boolean,
        val contextTokens: Int,
        val timeoutMs: Long,
        val budgetRemaining: Int,
        val runtimeInstalled: Boolean = false,
    )

    data class Decision(
        val accepted: Boolean,
        val requestPermitted: Boolean,
        val modelId: String,
        val reason: String,
    )

    fun prepare(selection: Selection): Decision {
        val modelId = selection.modelId.trim()
        val host = selection.endpointHost.trim().lowercase()
        val cloudModel = modelId.contains("cloud", ignoreCase = true) || modelId.contains("://")
        val loopback = host == "127.0.0.1" || host == "localhost"
        val reason = when {
            modelId.isEmpty() -> "No local model was selected."
            cloudModel -> "Cloud model execution is rejected."
            modelId !in RECOMMENDED_MODELS -> "The model was not explicitly selected from the prepared local catalog."
            !loopback -> "The endpoint is not loopback. A public inference server is rejected."
            !selection.cloudDisabled -> "Ollama cloud execution is still enabled. Set OLLAMA_NO_CLOUD=1 before a local request."
            selection.contextTokens <= 0 || selection.contextTokens > MAX_CONTEXT_TOKENS ->
                "The prepared context cap is $MAX_CONTEXT_TOKENS tokens. A larger window was not measured on this computer."
            selection.timeoutMs <= 0 || selection.timeoutMs > MAX_TIMEOUT_MS -> "The timeout is outside the prepared range."
            selection.budgetRemaining <= 0 -> "The local request budget is exhausted."
            !selection.runtimeInstalled -> "No local inference runtime is installed."
            else -> "The local request is prepared for $modelId on loopback. Live inference is not authorized."
        }
        val prepared = reason.startsWith("The local request is prepared")
        return Decision(
            accepted = prepared,
            requestPermitted = false,
            modelId = if (prepared) modelId else "",
            reason = reason,
        )
    }
}
