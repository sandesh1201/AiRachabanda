package com.airachabanda.app.provider

/**
 * Routing rules for a future OpenRouter call.
 * A numeric key limit of zero is necessary and is not treated as proven.
 * This object does not contact OpenRouter.
 */
object OpenRouterRoutePolicy {
    const val FREE_ROUTER = "openrouter/free"
    const val PROVEN_BY_LIVE_CHECK = false

    data class Decision(
        val modelAccepted: Boolean,
        val limitAccepted: Boolean,
        val automatedCallPermitted: Boolean,
        val reason: String,
    )

    fun evaluate(modelId: String, fallbackModelIds: List<String>, observedKeyLimit: Double?): Decision {
        if (fallbackModelIds.isNotEmpty()) {
            return Decision(
                modelAccepted = false,
                limitAccepted = false,
                automatedCallPermitted = false,
                reason = "A fallback model list can select a paid model. Send one free model id only.",
            )
        }
        val modelAccepted = modelId == FREE_ROUTER || modelId.endsWith(":free")
        if (!modelAccepted) {
            return Decision(
                modelAccepted = false,
                limitAccepted = false,
                automatedCallPermitted = false,
                reason = "The model id is not openrouter/free and does not end with :free.",
            )
        }
        val limitAccepted = observedKeyLimit == 0.0
        if (!limitAccepted) {
            return Decision(
                modelAccepted = true,
                limitAccepted = false,
                automatedCallPermitted = false,
                reason = "The key credit limit is not a confirmed numeric zero. A null limit is unlimited and is rejected.",
            )
        }
        return Decision(
            modelAccepted = true,
            limitAccepted = true,
            automatedCallPermitted = false,
            reason = "The limit and model id match the free-route rule. Live key metadata has not been tested, so the call stays blocked.",
        )
    }
}
