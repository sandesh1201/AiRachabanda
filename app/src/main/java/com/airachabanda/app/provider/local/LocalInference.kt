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
        val route = LocalTransportPolicy.classify(host)
        val reason = when {
            modelId.isEmpty() -> "No local model was selected."
            cloudModel -> "Cloud model execution is rejected."
            modelId !in RECOMMENDED_MODELS -> "The model was not explicitly selected from the prepared local catalog."
            route != LocalTransportPolicy.Route.ADB_REVERSE_LOOPBACK -> LocalTransportPolicy.reason(route)
            !selection.cloudDisabled -> "Ollama cloud execution is still enabled. Set OLLAMA_NO_CLOUD=1 before a local request."
            selection.contextTokens <= 0 || selection.contextTokens > MAX_CONTEXT_TOKENS ->
                "The prepared context cap is $MAX_CONTEXT_TOKENS tokens. A larger window was not measured on this computer."
            selection.timeoutMs <= 0 || selection.timeoutMs > MAX_TIMEOUT_MS -> "The timeout is outside the prepared range."
            selection.budgetRemaining <= 0 -> "The local request budget is exhausted."
            !selection.runtimeInstalled -> "No local inference runtime is installed."
            else -> "The local request is prepared for $modelId through adb reverse to Windows loopback. Live inference is not authorized."
        }
        val prepared = reason.startsWith("The local request is prepared for")
        return Decision(
            accepted = prepared,
            requestPermitted = false,
            modelId = if (prepared) modelId else "",
            reason = reason,
        )
    }
}

object LocalTransportPolicy {
    const val PORT = 11434

    enum class Route {
        ADB_REVERSE_LOOPBACK,
        EMULATOR_ALIAS,
        PUBLIC,
    }

    fun classify(host: String): Route = when (host.trim().lowercase().removePrefix("[").removeSuffix("]")) {
        "127.0.0.1", "localhost", "::1" -> Route.ADB_REVERSE_LOOPBACK
        "10.0.2.2" -> Route.EMULATOR_ALIAS
        else -> Route.PUBLIC
    }

    fun reason(route: Route): String = when (route) {
        Route.ADB_REVERSE_LOOPBACK ->
            "Use adb reverse tcp:$PORT tcp:$PORT. The Windows server stays bound to 127.0.0.1. This app still does not open cleartext."
        Route.EMULATOR_ALIAS ->
            "10.0.2.2 is the emulator alias for the host, not the chosen route. Keep the server on Windows loopback and use adb reverse."
        Route.PUBLIC ->
            "The endpoint is not device loopback. A public or LAN inference server is rejected. A future phone connection needs TLS and a credential, and cleartext stays off."
    }
}
