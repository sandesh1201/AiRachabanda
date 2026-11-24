package com.airachabanda.app.provider.gemini

import com.airachabanda.app.security.RedactedLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * Decides whether a Gemini metadata read or the one free-tier inference call may leave the device.
 * Consent, a ready token, and a verified unlinked project are sufficient. No source flag is required.
 */
object GeminiLiveGate {
    const val FREE_MODEL = GeminiFreeCatalog.PREFERRED
    const val PROMPT = "What is 17 multiplied by 23? Return the result and a brief explanation."
    const val MAX_OUTPUT_TOKENS = 128
    const val EXPECTED_PRODUCT = 391
    const val OAUTH_SCOPE = GeminiProjectBinding.OAUTH_SCOPE

    enum class OauthState {
        MISSING,
        CANCELLED,
        EXPIRED,
        PERMISSION_DENIED,
        READY,
    }

    data class Input(
        val metadataConsent: Boolean,
        val inferenceConsent: Boolean,
        val oauthState: OauthState,
        val binding: GeminiProjectBinding.Binding,
        val modelId: String,
        val freeTierKnown: Boolean,
        val sessionBudgetRemaining: Int,
        val quotaExhausted: Boolean,
    )

    data class Decision(val permitted: Boolean, val reason: String)

    fun metadata(input: Input): Decision {
        val reason = when {
            !input.metadataConsent -> "Metadata consent was not given. No Google request was sent."
            input.oauthState == OauthState.CANCELLED -> "Google authorization was cancelled. No lookup was sent."
            input.oauthState == OauthState.MISSING -> "Read-only Google authorization is missing. No lookup was sent."
            input.oauthState == OauthState.EXPIRED -> "The Google authorization has expired. No lookup was sent."
            input.oauthState == OauthState.PERMISSION_DENIED -> "The Google authorization is missing apikeys.keys.lookup or resourcemanager.projects.get. No lookup was sent."
            else -> "Metadata may be read once for the key lookup and that project's billing."
        }
        return Decision(reason.startsWith("Metadata may be read"), reason)
    }

    fun inference(input: Input): Decision {
        val reason = when {
            !input.inferenceConsent -> "Inference consent was not given. No Gemini generation request was sent."
            input.oauthState != OauthState.READY -> "Google authorization is not ready. No generation request was sent."
            !input.binding.credentialBoundToProject -> "The API key is not bound to the project whose billing was read."
            !input.binding.billingUnlinked -> "Billing is not shown to be disabled for the key's project. No generation request was sent."
            !GeminiFreeCatalog.eligible(input.modelId) -> "The model is not on the documented free-tier text list."
            !input.freeTierKnown -> "Free-tier pricing for this model is unknown. No generation request was sent."
            input.quotaExhausted -> "The free-tier allowance is exhausted. No further request is sent."
            input.sessionBudgetRemaining <= 0 -> "The session request budget is exhausted."
            else -> "One free-tier generation request may be sent. It is not retried."
        }
        return Decision(reason.startsWith("One free-tier"), reason)
    }

    fun requestBody(): JSONObject {
        val part = JSONObject()
        part.put("text", PROMPT)
        val parts = JSONArray()
        parts.put(part)
        val content = JSONObject()
        content.put("role", "user")
        content.put("parts", parts)
        val contents = JSONArray()
        contents.put(content)
        val generation = JSONObject()
        generation.put("maxOutputTokens", MAX_OUTPUT_TOKENS)
        generation.put("temperature", 0)
        val body = JSONObject()
        body.put("contents", contents)
        body.put("generationConfig", generation)
        return body
    }

    fun safeLog(message: String): String = RedactedLog.redact(message)

    fun grade(responseText: String): String {
        val found = Regex("""(?<!\d)$EXPECTED_PRODUCT(?!\d)""").containsMatchIn(responseText)
        return if (found) "The returned text contains 391." else "The returned text does not contain 391."
    }
}
