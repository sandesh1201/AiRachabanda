package com.airachabanda.app.provider.openrouter

import com.airachabanda.app.core.model.ModelIdentity
import com.airachabanda.app.security.RedactedLog
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.CancellationException

/**
 * Production OpenRouter adapter. It builds official requests and parses official
 * response shapes. Live metadata and live inference stay off until a later
 * authorization proves the zero-cost conditions.
 */
class OpenRouterAuthorization(
    val metadataChecksAuthorized: Boolean = false,
    val inferenceAuthorized: Boolean = false,
) {
    companion object {
        val PRODUCTION = OpenRouterAuthorization()
    }
}

object OpenRouterEndpoints {
    const val ORIGIN = "https://openrouter.ai"
    const val KEY = "$ORIGIN/api/v1/key"
    const val MODELS = "$ORIGIN/api/v1/models"
    const val CHAT = "$ORIGIN/api/v1/chat/completions"
    const val FREE_ROUTER = "openrouter/free"
}

data class HttpExchange(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String?,
)

data class HttpResult(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
)

fun interface HttpTransport {
    suspend fun exchange(request: HttpExchange): HttpResult
}

class UrlConnectionTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) : HttpTransport {
    override suspend fun exchange(request: HttpExchange): HttpResult {
        val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
            requestMethod = request.method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = false
            request.headers.forEach { (name, value) -> setRequestProperty(name, value) }
            if (request.body != null) {
                doOutput = true
                outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
            }
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val headers = connection.headerFields
                .filterKeys { it != null }
                .mapKeys { it.key.lowercase() }
                .mapValues { it.value.joinToString(",") }
            return HttpResult(status, headers, body)
        } finally {
            connection.disconnect()
        }
    }
}

enum class OpenRouterFailure {
    NONE,
    POLICY,
    AUTHENTICATION,
    PAYMENT_REQUIRED,
    BUDGET_BLOCKED,
    RATE_LIMIT,
    TIMEOUT,
    CANCELLED,
    MALFORMED,
    INCOMPLETE,
    DUPLICATE,
    CONNECTION,
}

data class OpenRouterCandidate(
    val modelId: String,
    val pricing: Map<String, String>?,
    val fallbackModelIds: List<String> = emptyList(),
    val toolsRequested: Boolean = false,
    val pluginsRequested: Boolean = false,
    val allowProviderFallbacks: Boolean = false,
    val observedKeyLimit: Double? = null,
    val observedDailyRemaining: Int? = null,
    val sessionBudgetRemaining: Int = 0,
    val creditPurchaseRequested: Boolean = false,
)

data class OpenRouterDecision(
    val accepted: Boolean,
    val liveCallPermitted: Boolean,
    val reason: String,
)

data class DiscoveredModel(
    val id: String,
    val pricing: Map<String, String>,
    val capabilities: List<String>,
)

data class KeyObservation(
    val limit: Double?,
    val limitRemaining: Double?,
    val dailyUsed: Int?,
    val dailyLimit: Int?,
    val dailyRemaining: Int?,
    val freeTierFlag: Boolean?,
)

data class StreamParse(
    val generationId: String?,
    val modelId: String,
    val text: String,
    val completed: Boolean,
    val failure: OpenRouterFailure,
    val reason: String,
)

data class AdapterOutcome(
    val failure: OpenRouterFailure,
    val reason: String,
    val generationId: String? = null,
    val modelId: String = ModelIdentity.UNKNOWN,
    val text: String = "",
    val completed: Boolean = false,
    val request: HttpExchange? = null,
)

class OpenRouterAdapter(
    private val transport: HttpTransport,
    private val authorization: OpenRouterAuthorization = OpenRouterAuthorization.PRODUCTION,
) {
    fun assess(candidate: OpenRouterCandidate): OpenRouterDecision {
        if (candidate.creditPurchaseRequested) {
            return reject("Automatic credit purchases are rejected.")
        }
        if (candidate.toolsRequested || candidate.pluginsRequested) {
            return reject("Tools, plugins, and other billable add-ons are rejected.")
        }
        if (candidate.allowProviderFallbacks || candidate.fallbackModelIds.isNotEmpty()) {
            return reject("Fallback models and provider fallbacks are rejected. A paid model must not be substituted.")
        }
        val modelId = candidate.modelId.trim()
        if (!isEligibleId(modelId)) {
            return reject("The model is paid or is not an explicit free-model id. No model was substituted.")
        }
        val pricing = candidate.pricing
        if (pricing == null || pricing.isEmpty() || !pricing.containsKey("prompt") || !pricing.containsKey("completion")) {
            return reject("Model pricing is unknown. Unknown pricing is rejected.")
        }
        if (pricing.any { (_, value) -> value.toDoubleOrNull() != 0.0 }) {
            return reject("A non-zero price was found. Paid models and paid add-on prices are rejected.")
        }
        if (candidate.observedKeyLimit == null) {
            return reject("The key credit limit is unknown. A missing limit is not treated as zero.")
        }
        if (candidate.observedKeyLimit != 0.0) {
            return reject("The key credit limit is not numeric zero.")
        }
        if (candidate.observedDailyRemaining == null) {
            return reject("The free-model daily remaining count has not been observed. A zero credit limit is not sufficient proof.")
        }
        if (candidate.observedDailyRemaining <= 0) {
            return reject("The observed free-model daily quota is exhausted.")
        }
        if (candidate.sessionBudgetRemaining <= 0) {
            return reject("The session request budget is exhausted.")
        }
        return OpenRouterDecision(
            accepted = true,
            liveCallPermitted = false,
            reason = "The request shape matches the free-model rule. A zero credit limit is not sufficient proof that free models can be called, and live inference is not authorized.",
        )
    }

    fun participantsFor(models: List<DiscoveredModel>): List<com.airachabanda.app.orchestration.EligibleParticipant> {
        return models.mapNotNull { model ->
            val decision = assess(
                OpenRouterCandidate(
                    modelId = model.id,
                    pricing = model.pricing,
                    observedKeyLimit = 0.0,
                    observedDailyRemaining = 1,
                    sessionBudgetRemaining = 1,
                ),
            )
            if (!decision.accepted) return@mapNotNull null
            com.airachabanda.app.orchestration.EligibleParticipant(
                providerId = com.airachabanda.app.core.model.ProviderId.OPENROUTER,
                assisted = false,
                billing = com.airachabanda.app.core.model.BillingEligibility.UNKNOWN,
                verifiedModelId = model.id,
                participantKey = "OPENROUTER:${model.id}",
                capabilityNote = model.capabilities.joinToString(", ").ifBlank { "Text generation. Tools and plugins are not enabled." },
            )
        }
    }

    fun buildChatRequest(apiKey: String, modelId: String, prompt: String): HttpExchange {
        val message = JSONObject().put("role", "user").put("content", prompt)
        val provider = JSONObject()
            .put("allow_fallbacks", false)
            .put("max_price", JSONObject().put("prompt", 0).put("completion", 0))
        val body = JSONObject()
            .put("model", modelId)
            .put("stream", true)
            .put("messages", JSONArray().put(message))
            .put("provider", provider)
        return HttpExchange(
            method = "POST",
            url = OpenRouterEndpoints.CHAT,
            headers = mapOf(
                "Authorization" to "Bearer $apiKey",
                "Content-Type" to "application/json",
                "Accept" to "text/event-stream",
                "X-Title" to "AI Rachabanda",
            ),
            body = body.toString(),
        )
    }

    fun parseKey(body: String): KeyObservation {
        val data = JSONObject(body).getJSONObject("data")
        val daily = data.optJSONObject("free_model_daily_requests")
        return KeyObservation(
            limit = data.optionalDouble("limit"),
            limitRemaining = data.optionalDouble("limit_remaining"),
            dailyUsed = daily?.optionalInt("used"),
            dailyLimit = daily?.optionalInt("limit"),
            dailyRemaining = daily?.optionalInt("remaining"),
            freeTierFlag = if (data.has("is_free_tier") && !data.isNull("is_free_tier")) data.getBoolean("is_free_tier") else null,
        )
    }

    fun parseModels(body: String): List<DiscoveredModel> {
        val data = JSONObject(body).getJSONArray("data")
        return buildList {
            for (index in 0 until data.length()) {
                val row = data.getJSONObject(index)
                val pricingObject = row.optJSONObject("pricing")
                val pricing = mutableMapOf<String, String>()
                pricingObject?.keys()?.forEach { key ->
                    pricing[key] = pricingObject.opt(key)?.toString() ?: ""
                }
                val capabilities = mutableListOf<String>()
                row.optJSONArray("supported_parameters")?.let { parameters ->
                    for (parameter in 0 until parameters.length()) capabilities += parameters.getString(parameter)
                }
                add(DiscoveredModel(id = row.getString("id"), pricing = pricing, capabilities = capabilities))
            }
        }
    }

    fun parseStream(body: String): StreamParse {
        var generationId: String? = null
        var modelId = ModelIdentity.UNKNOWN
        val text = StringBuilder()
        var sawDone = false
        var sawUsage = false
        var finishReason: String? = null
        body.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith(":")) return@forEach
            if (!line.startsWith("data:")) return@forEach
            val payload = line.removePrefix("data:").trim()
            if (payload == "[DONE]") {
                sawDone = true
                return@forEach
            }
            val json = try {
                JSONObject(payload)
            } catch (invalid: org.json.JSONException) {
                return StreamParse(generationId, modelId, text.toString(), false, OpenRouterFailure.MALFORMED, "A stream chunk was not valid JSON.")
            }
            generationId = json.optString("id").ifBlank { generationId }
            val reported = json.optString("model")
            if (reported.isNotBlank()) modelId = reported
            if (json.has("usage") && !json.isNull("usage")) sawUsage = true
            val choices = json.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val choice = choices.getJSONObject(0)
                val finish = choice.optString("finish_reason")
                if (finish.isNotBlank() && finish != "null") finishReason = finish
                val delta = choice.optJSONObject("delta")
                val content = delta?.optString("content").orEmpty()
                if (content.isNotEmpty()) text.append(content)
            }
        }
        val terminal = finishReason == "stop" || finishReason == "length"
        if (!sawDone || !sawUsage || !terminal) {
            return StreamParse(
                generationId,
                modelId,
                text.toString(),
                completed = false,
                failure = OpenRouterFailure.INCOMPLETE,
                reason = "The stream did not finish with a terminal usage chunk. The partial text is not a completed response.",
            )
        }
        return StreamParse(
            generationId,
            modelId,
            text.toString(),
            completed = true,
            failure = OpenRouterFailure.NONE,
            reason = "The stream finished with a usage chunk and [DONE].",
        )
    }

    fun classifyHttp(status: Int, headers: Map<String, String>): AdapterOutcome {
        val retry = headers.entries.firstOrNull { it.key.equals("retry-after", ignoreCase = true) }?.value
        return when (status) {
            401 -> AdapterOutcome(OpenRouterFailure.AUTHENTICATION, "OpenRouter rejected the credential. The key was not logged.")
            402 -> AdapterOutcome(OpenRouterFailure.PAYMENT_REQUIRED, "OpenRouter returned payment required. The provider was not retried and no credit was purchased.")
            403 -> AdapterOutcome(OpenRouterFailure.BUDGET_BLOCKED, "OpenRouter returned a budget block. The request was not retried.")
            429 -> AdapterOutcome(
                OpenRouterFailure.RATE_LIMIT,
                "OpenRouter rate limited the request. Retry-After is ${retry ?: "absent"}. No automatic retry was sent.",
            )
            else -> AdapterOutcome(OpenRouterFailure.MALFORMED, "OpenRouter returned HTTP $status. The body was not treated as a completion.")
        }
    }

    fun classifyFailure(error: Throwable): AdapterOutcome = when (error) {
        is CancellationException -> AdapterOutcome(OpenRouterFailure.CANCELLED, "The request was cancelled. No partial text was stored as complete.")
        is SocketTimeoutException -> AdapterOutcome(OpenRouterFailure.TIMEOUT, "The connection timed out. The outcome is unknown, so it was not retried.")
        else -> AdapterOutcome(OpenRouterFailure.CONNECTION, "The connection failed. The outcome was not retried. ${RedactedLog.redact(error.message.orEmpty())}")
    }

    suspend fun readKey(apiKey: String): AdapterOutcome {
        if (!authorization.metadataChecksAuthorized) {
            return AdapterOutcome(
                OpenRouterFailure.POLICY,
                "Live key metadata is not authorized. Verify connection stays disabled.",
            )
        }
        return exchange(buildKeyRequest(apiKey))
    }

    suspend fun readModels(apiKey: String): AdapterOutcome {
        if (!authorization.metadataChecksAuthorized) {
            return AdapterOutcome(OpenRouterFailure.POLICY, "Live model discovery is not authorized. No catalog was hardcoded.")
        }
        return exchange(buildModelsRequest(apiKey))
    }

    suspend fun complete(apiKey: String, candidate: OpenRouterCandidate, prompt: String): AdapterOutcome {
        val decision = assess(candidate)
        if (!decision.accepted) {
            return AdapterOutcome(OpenRouterFailure.POLICY, decision.reason)
        }
        val request = buildChatRequest(apiKey, candidate.modelId, prompt)
        if (!authorization.inferenceAuthorized) {
            return AdapterOutcome(
                failure = OpenRouterFailure.POLICY,
                reason = decision.reason,
                request = request,
            )
        }
        return exchange(request)
    }

    private suspend fun exchange(request: HttpExchange): AdapterOutcome {
        val result = try {
            transport.exchange(redactForLog(request))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return classifyFailure(error)
        }
        if (result.status !in 200..299) return classifyHttp(result.status, result.headers)
        return if (request.url == OpenRouterEndpoints.CHAT) {
            val parsed = parseStream(result.body)
            AdapterOutcome(parsed.failure, parsed.reason, parsed.generationId, parsed.modelId, parsed.text, parsed.completed, request)
        } else {
            AdapterOutcome(OpenRouterFailure.NONE, "Metadata was read. It was not logged with the credential.", request = request)
        }
    }

    private fun buildKeyRequest(apiKey: String) = HttpExchange(
        method = "GET",
        url = OpenRouterEndpoints.KEY,
        headers = mapOf("Authorization" to "Bearer $apiKey", "Accept" to "application/json"),
        body = null,
    )

    private fun buildModelsRequest(apiKey: String) = HttpExchange(
        method = "GET",
        url = OpenRouterEndpoints.MODELS,
        headers = mapOf("Authorization" to "Bearer $apiKey", "Accept" to "application/json"),
        body = null,
    )

    private fun redactForLog(request: HttpExchange): HttpExchange {
        RedactedLog.d("OpenRouter", "Prepared ${request.method} ${request.url}")
        return request
    }

    private fun reject(reason: String) = OpenRouterDecision(accepted = false, liveCallPermitted = false, reason = reason)

    private fun isEligibleId(modelId: String): Boolean {
        if (modelId == OpenRouterEndpoints.FREE_ROUTER) return true
        if (!modelId.endsWith(":free")) return false
        val suffix = modelId.substringAfterLast(':')
        return suffix == "free" && !modelId.removeSuffix(":free").contains(':')
    }
}

private fun JSONObject.optionalDouble(name: String): Double? {
    if (!has(name) || isNull(name)) return null
    return when (val value = get(name)) {
        is Number -> value.toDouble()
        else -> null
    }
}

private fun JSONObject.optionalInt(name: String): Int? = optionalDouble(name)?.toInt()
