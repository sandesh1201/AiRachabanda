package com.airachabanda.app.provider.gemini

import com.airachabanda.app.core.model.ModelIdentity
import com.airachabanda.app.provider.billing.GeminiBillingPolicy
import com.airachabanda.app.provider.openrouter.HttpExchange
import com.airachabanda.app.provider.openrouter.HttpTransport
import com.airachabanda.app.provider.openrouter.OpenRouterFailure
import com.airachabanda.app.provider.openrouter.ResponseDisposition
import com.airachabanda.app.security.RedactedLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CancellationException

class GeminiAuthorization(
    val metadataAuthorized: Boolean = false,
    val inferenceAuthorized: Boolean = false,
) {
    companion object {
        val PRODUCTION = GeminiAuthorization()
    }
}

data class GeminiModel(
    val id: String,
    val displayName: String,
    val inputTokenLimit: Int?,
    val outputTokenLimit: Int?,
    val methods: List<String>,
    val freeTierKnown: Boolean,
)

data class GeminiOutcome(
    val failure: OpenRouterFailure,
    val disposition: ResponseDisposition,
    val reason: String,
    val modelId: String = ModelIdentity.UNKNOWN,
    val text: String = "",
    val generationId: String? = null,
    val completed: Boolean = false,
    val request: HttpExchange? = null,
)

class GeminiAdapter(
    private val transport: HttpTransport,
    private val authorization: GeminiAuthorization = GeminiAuthorization.PRODUCTION,
) {
    fun verifyMetadata(apiKey: String): GeminiOutcome {
        if (!authorization.metadataAuthorized || apiKey.isBlank()) {
            return GeminiOutcome(
                OpenRouterFailure.POLICY,
                ResponseDisposition.FAILED,
                "No Gemini metadata request was sent. A successful key check would not prove the Free tier, and live metadata is not authorized.",
            )
        }
        return GeminiOutcome(OpenRouterFailure.POLICY, ResponseDisposition.FAILED, "Live Gemini metadata is not authorized.")
    }

    suspend fun complete(
        apiKey: String,
        modelId: String,
        prompt: String,
        sessionBudgetRemaining: Int,
        billingLinked: Boolean? = null,
        freeTierKnown: Boolean = false,
        paidModel: Boolean = false,
        quotaExhausted: Boolean = false,
        credentialBoundToProject: Boolean = false,
    ): GeminiOutcome {
        val decision = GeminiBillingPolicy.assess(
            billingLinked = billingLinked,
            freeTierKnown = freeTierKnown,
            paidModel = paidModel,
            toolsOrGrounding = false,
            quotaExhausted = quotaExhausted,
            sessionBudgetRemaining = sessionBudgetRemaining,
            credentialBoundToProject = credentialBoundToProject,
        )
        if (!decision.accepted) {
            return GeminiOutcome(OpenRouterFailure.POLICY, ResponseDisposition.FAILED, decision.reason)
        }
        if (!MODEL_ID.matches(modelId)) {
            return GeminiOutcome(OpenRouterFailure.POLICY, ResponseDisposition.FAILED, "The model ID was rejected before a request was built.")
        }
        val part = JSONObject()
        part.put("text", prompt)
        val parts = JSONArray()
        parts.put(part)
        val content = JSONObject()
        content.put("parts", parts)
        val contents = JSONArray()
        contents.put(content)
        val body = JSONObject()
        body.put("contents", contents)
        val request = HttpExchange(
            method = "POST",
            url = "https://generativelanguage.googleapis.com/v1beta/models/$modelId:streamGenerateContent?alt=sse",
            headers = mapOf(
                "x-goog-api-key" to apiKey,
                "Content-Type" to "application/json",
                "Accept" to "text/event-stream",
            ),
            body = body.toString(),
        )
        if (!authorization.inferenceAuthorized) {
            RedactedLog.d("Gemini", "Prepared a streamed generateContent request. The key was not logged.")
            return GeminiOutcome(OpenRouterFailure.POLICY, ResponseDisposition.FAILED, decision.reason, request = request)
        }
        val result = try {
            transport.exchange(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: java.net.SocketTimeoutException) {
            return GeminiOutcome(OpenRouterFailure.TIMEOUT, ResponseDisposition.OUTCOME_UNKNOWN, "The Gemini request timed out. It was not retried.")
        } catch (error: Exception) {
            return GeminiOutcome(OpenRouterFailure.CONNECTION, ResponseDisposition.OUTCOME_UNKNOWN, "The Gemini connection failed. It was not retried.")
        }
        if (result.status == 401 || result.status == 403) {
            return GeminiOutcome(OpenRouterFailure.AUTHENTICATION, ResponseDisposition.FAILED, "Gemini rejected the credential. The key was not logged.")
        }
        if (result.status == 429) {
            return GeminiOutcome(OpenRouterFailure.RATE_LIMIT, ResponseDisposition.FAILED, "Gemini rate limited the request. No automatic retry was sent.")
        }
        if (result.status !in 200..299) {
            return GeminiOutcome(OpenRouterFailure.MALFORMED, ResponseDisposition.FAILED, "Gemini returned HTTP ${result.status}.")
        }
        return parseStream(modelId, result.body)
    }

    fun prepareFirstInference(gate: GeminiLiveGate.Input): GeminiOutcome {
        val decision = GeminiLiveGate.inference(gate)
        if (!decision.permitted) {
            return GeminiOutcome(OpenRouterFailure.POLICY, ResponseDisposition.FAILED, decision.reason, GeminiLiveGate.FREE_MODEL)
        }
        val request = HttpExchange(
            method = "POST",
            url = "https://generativelanguage.googleapis.com/v1beta/models/${gate.modelId}:streamGenerateContent?alt=sse",
            headers = mapOf("Content-Type" to "application/json", "Accept" to "text/event-stream"),
            body = GeminiLiveGate.requestBody().toString(),
        )
        RedactedLog.d("Gemini", "Prepared one free-tier request. No key or OAuth token is in this log.")
        return GeminiOutcome(
            OpenRouterFailure.POLICY,
            ResponseDisposition.FAILED,
            "The free-tier request is prepared and was not sent.",
            gate.modelId,
            request = request,
        )
    }

    fun parseModels(body: String): List<GeminiModel> {
        val models = JSONObject(body).optJSONArray("models") ?: return emptyList()
        return buildList {
            for (index in 0 until models.length()) {
                val row = models.optJSONObject(index) ?: continue
                val name = row.optString("name").removePrefix("models/")
                if (name.isBlank()) continue
                val methods = buildList {
                    val listed = row.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                    for (method in 0 until listed.length()) add(listed.optString(method))
                }
                add(
                    GeminiModel(
                        id = name,
                        displayName = row.optString("displayName"),
                        inputTokenLimit = if (row.has("inputTokenLimit")) row.optInt("inputTokenLimit") else null,
                        outputTokenLimit = if (row.has("outputTokenLimit")) row.optInt("outputTokenLimit") else null,
                        methods = methods,
                        freeTierKnown = false,
                    ),
                )
            }
        }
    }

    fun parseBillingInfo(body: String): Boolean? {
        val root = try {
            JSONObject(body)
        } catch (invalid: org.json.JSONException) {
            return null
        }
        if (!root.has("billingEnabled") || root.isNull("billingEnabled")) return null
        return root.optBoolean("billingEnabled")
    }

    fun parseStream(modelId: String, body: String): GeminiOutcome {
        val text = StringBuilder()
        var finish: String? = null
        var sawChunk = false
        body.lineSequence().forEach { raw ->
            val line = raw.trim().removePrefix("data:").trim()
            if (line.isBlank() || line.startsWith(":")) return@forEach
            val json = try {
                JSONObject(line)
            } catch (invalid: org.json.JSONException) {
                return GeminiOutcome(OpenRouterFailure.MALFORMED, ResponseDisposition.FAILED, "A Gemini stream chunk was not valid JSON.", modelId, text.toString())
            }
            sawChunk = true
            val candidates = json.optJSONArray("candidates") ?: return@forEach
            if (candidates.length() == 0) return@forEach
            val candidate = candidates.getJSONObject(0)
            val reported = candidate.optString("finishReason")
            if (reported.isNotBlank()) finish = reported
            val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: return@forEach
            for (index in 0 until parts.length()) text.append(parts.getJSONObject(index).optString("text"))
        }
        if (finish == "MAX_TOKENS") {
            return GeminiOutcome(OpenRouterFailure.INCOMPLETE, ResponseDisposition.TRUNCATED, "Gemini stopped at the token limit. The text is not a complete final answer.", modelId, text.toString(), generationId = "gemini:$modelId", completed = false)
        }
        if (finish == "STOP" && sawChunk) {
            return GeminiOutcome(OpenRouterFailure.NONE, ResponseDisposition.COMPLETED, "Gemini finished with STOP.", modelId, text.toString(), generationId = "gemini:$modelId", completed = true)
        }
        if (!sawChunk) {
            return GeminiOutcome(OpenRouterFailure.INCOMPLETE, ResponseDisposition.OUTCOME_UNKNOWN, "Gemini returned no stream chunk. The outcome is unknown and was not retried.", modelId)
        }
        return GeminiOutcome(OpenRouterFailure.INCOMPLETE, ResponseDisposition.INTERRUPTED, "The Gemini stream ended before STOP. The partial text is not a final answer.", modelId, text.toString(), generationId = "gemini:$modelId", completed = false)
    }

    private companion object {
        val MODEL_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,80}$")
    }
}
