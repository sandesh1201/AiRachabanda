package com.airachabanda.app.provider.gemini

/**
 * Text models whose standard free-tier input and output prices are both "Free of charge"
 * on the Gemini Developer API pricing page read 24 November 2026, and whose free tier
 * does not offer context caching or grounding. Discovery of a model name is not pricing proof.
 * Models with paid grounding after a free quota, image or audio output, live or transcribe
 * methods, deprecated automatic routing, or an unpublished price are excluded.
 */
object GeminiFreeCatalog {
    const val SOURCE = "Gemini Developer API pricing, read 24 November 2026"
    const val PREFERRED = "gemini-3.5-flash-lite"
    val TEXT_MODELS = listOf(PREFERRED, "gemini-3.1-flash-lite")

    const val PROTECTION =
        "Free-tier input and output for these models are free of charge. Context caching and grounding are not available on that tier. " +
            "Free-tier prompts and responses may be used to improve Google products. " +
            "Exhausting the free allowance returns a rate-limit error. This app does not retry and does not switch to another model."

    const val BILLING_LIMIT =
        "billingEnabled false describes the project at the time of the read. Google or the project owner can link billing afterward. " +
            "This app reads billing again before a later inference session, sends no paid fallback, and cannot see a change that happens after the read starts."

    fun eligible(modelId: String): Boolean = modelId in TEXT_MODELS

    fun select(discovered: List<GeminiModel>): String? = TEXT_MODELS.firstOrNull { id ->
        discovered.any { model ->
            model.id == id && model.methods.any { it.equals("generateContent", ignoreCase = true) }
        }
    }
}
