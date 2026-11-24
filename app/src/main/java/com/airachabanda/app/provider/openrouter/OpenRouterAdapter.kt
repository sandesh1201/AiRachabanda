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

enum class ResponseDisposition {
    COMPLETED,
    TRUNCATED,
    INTERRUPTED,
    CANCELLED,
    FAILED,
    OUTCOME_UNKNOWN,
}

enum class ConnectionState {
    NOT_CONFIGURED,
    CREDENTIAL_STORED,
    VERIFICATION_REQUIRED,
    AUTHENTICATION_FAILED,
    METADATA_VERIFIED,
    ZERO_COST_ELIGIBILITY_UNVERIFIED,
    ZERO_COST_ELIGIBLE,
    BLOCKED,
}

/**
 * Plugin ids named by the official plugins and message-transforms guides.
 * Omitting the plugins array does not disable account defaults. Each entry
 * uses the documented enabled=false switch. Prevent-overrides cannot be read
 * from GET /api/v1/key, so this list is not proof that charges are impossible.
 */
object OpenRouterPluginSafety {
    val REQUEST_DISABLE_IDS = listOf("web", "response-healing", "context-compression")
    const val UNRESOLVED = "Account plugin defaults are not in GET /api/v1/key. Disabling plugins in the OpenRouter settings page is a manual step, not a programmatic verification. A request can set enabled false only for plugin ids the guides name. Prevent overrides cannot be detected here. The deprecated web plugin and the web-search server tool can add charges. Inference stays off."
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
    val name: String = "",
    val underlyingModelId: String = ModelIdentity.UNKNOWN,
    val contextLength: Int? = null,
    val inputModalities: List<String> = emptyList(),
    val outputModalities: List<String> = emptyList(),
    val pricingKnown: Boolean = false,
)

data class KeyObservation(
    val limit: Double?,
    val limitRemaining: Double?,
    val limitReset: String?,
    val dailyUsed: Int?,
    val dailyLimit: Int?,
    val dailyRemaining: Int?,
    val freeTierFlag: Boolean?,
    val usage: Double?,
    val usageDaily: Double?,
    val usageWeekly: Double?,
    val usageMonthly: Double?,
    val expiresAt: String?,
    val managementKey: Boolean?,
    val provisioningKey: Boolean?,
    val deprecatedRateLimitRequests: Int?,
    val pluginDefaultsPresent: Boolean = false,
)

data class MetadataReport(
    val failure: OpenRouterFailure,
    val state: ConnectionState,
    val credentialValid: Boolean,
    val summary: String,
    val usageLimits: String,
    val remainingQuota: String,
    val eligibleModels: List<DiscoveredModel>,
    val restrictions: String,
)

data class StreamParse(
    val generationId: String?,
    val modelId: String,
    val text: String,
    val completed: Boolean,
    val failure: OpenRouterFailure,
    val reason: String,
    val disposition: ResponseDisposition,
)

data class AdapterOutcome(
    val failure: OpenRouterFailure,
    val reason: String,
    val generationId: String? = null,
    val modelId: String = ModelIdentity.UNKNOWN,
    val text: String = "",
    val completed: Boolean = false,
    val request: HttpExchange? = null,
    val disposition: ResponseDisposition = ResponseDisposition.OUTCOME_UNKNOWN,
    val responseBody: String? = null,
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
        if (isAutomaticRoute(modelId)) {
            return reject("Automatic routing is rejected. No model was substituted.")
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

    fun eligibleModels(models: List<DiscoveredModel>): List<DiscoveredModel> {
        return models.filter { model ->
            model.pricingKnown &&
                !isAutomaticRoute(model.id) &&
                model.pricing.containsKey("prompt") &&
                model.pricing.containsKey("completion") &&
                model.pricing.values.all { value -> value.toDoubleOrNull() == 0.0 }
        }
    }

    fun participantsFor(models: List<DiscoveredModel>): List<com.airachabanda.app.orchestration.EligibleParticipant> {
        return eligibleModels(models).map { model ->
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
        val plugins = JSONArray()
        OpenRouterPluginSafety.REQUEST_DISABLE_IDS.forEach { id ->
            plugins.put(JSONObject().put("id", id).put("enabled", false))
        }
        val body = JSONObject()
            .put("model", modelId)
            .put("stream", true)
            .put("messages", JSONArray().put(message))
            .put("provider", provider)
            .put("plugins", plugins)
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
        val deprecated = data.optJSONObject("rate_limit")
        return KeyObservation(
            limit = data.optionalDouble("limit"),
            limitRemaining = data.optionalDouble("limit_remaining"),
            limitReset = data.optionalString("limit_reset"),
            dailyUsed = daily?.optionalInt("used"),
            dailyLimit = daily?.optionalInt("limit"),
            dailyRemaining = daily?.optionalInt("remaining"),
            freeTierFlag = data.optionalBoolean("is_free_tier"),
            usage = data.optionalDouble("usage"),
            usageDaily = data.optionalDouble("usage_daily"),
            usageWeekly = data.optionalDouble("usage_weekly"),
            usageMonthly = data.optionalDouble("usage_monthly"),
            expiresAt = data.optionalString("expires_at"),
            managementKey = data.optionalBoolean("is_management_key"),
            provisioningKey = data.optionalBoolean("is_provisioning_key"),
            deprecatedRateLimitRequests = deprecated?.optionalInt("requests"),
            pluginDefaultsPresent = false,
        )
    }

    fun parseModels(body: String): List<DiscoveredModel> {
        val data = JSONObject(body).getJSONArray("data")
        return buildList {
            for (index in 0 until data.length()) {
                val row = data.getJSONObject(index)
                val pricingObject = row.optJSONObject("pricing")
                val pricing = mutableMapOf<String, String>()
                var pricingKnown = pricingObject != null
                pricingObject?.keys()?.forEach { key ->
                    if (pricingObject.isNull(key)) {
                        pricingKnown = false
                    } else {
                        val raw = pricingObject.opt(key)?.toString().orEmpty()
                        pricing[key] = raw
                        if (raw.toDoubleOrNull() == null) pricingKnown = false
                    }
                }
                if (pricingObject == null || !pricing.containsKey("prompt") || !pricing.containsKey("completion")) {
                    pricingKnown = false
                }
                val capabilities = mutableListOf<String>()
                row.optJSONArray("supported_parameters")?.let { parameters ->
                    for (parameter in 0 until parameters.length()) capabilities += parameters.optString(parameter)
                }
                val architecture = row.optJSONObject("architecture")
                add(
                    DiscoveredModel(
                        id = row.getString("id"),
                        pricing = pricing,
                        capabilities = capabilities,
                        name = row.optString("name"),
                        underlyingModelId = row.optionalString("canonical_slug") ?: ModelIdentity.UNKNOWN,
                        contextLength = row.optionalInt("context_length"),
                        inputModalities = architecture?.stringList("input_modalities").orEmpty(),
                        outputModalities = architecture?.stringList("output_modalities").orEmpty(),
                        pricingKnown = pricingKnown,
                    ),
                )
            }
        }
    }

    fun classifyMetadata(key: KeyObservation, models: List<DiscoveredModel>): MetadataReport {
        val eligible = eligibleModels(models)
        val usage = usageText(key)
        val remaining = remainingText(key)
        val restrictions = buildString {
            append(OpenRouterPluginSafety.UNRESOLVED)
            if (key.limit == null) append(" The credit limit is null, which means unlimited spending.")
            if (key.limit != null && key.limit != 0.0) append(" The credit limit is not numeric zero.")
            if (key.managementKey == true) append(" This is a management key.")
            if (key.provisioningKey == true) append(" This is a provisioning key.")
        }
        val state = when {
            key.limit == null || key.limit != 0.0 -> ConnectionState.BLOCKED
            key.pluginDefaultsPresent &&
                key.dailyRemaining != null &&
                key.dailyRemaining > 0 &&
                eligible.isNotEmpty() -> ConnectionState.ZERO_COST_ELIGIBLE
            else -> ConnectionState.ZERO_COST_ELIGIBILITY_UNVERIFIED
        }
        val summary = when (state) {
            ConnectionState.BLOCKED -> "The credential authenticated, and the account is blocked for zero-cost use. Authentication is not zero-cost eligibility."
            ConnectionState.ZERO_COST_ELIGIBLE -> "Pricing, quota, and plugin configuration were all observed. Inference is still not enabled by this milestone."
            else -> "The credential authenticated. A numeric limit of zero is not sufficient proof, and account plugins were not verified. Inference stays off."
        }
        return MetadataReport(
            failure = OpenRouterFailure.NONE,
            state = state,
            credentialValid = true,
            summary = summary,
            usageLimits = usage,
            remainingQuota = remaining,
            eligibleModels = eligible,
            restrictions = restrictions,
        )
    }

    fun parseStream(body: String): StreamParse {
        var generationId: String? = null
        var modelId = ModelIdentity.UNKNOWN
        val text = StringBuilder()
        var sawDone = false
        var sawChunk = false
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
                return streamResult(
                    generationId,
                    modelId,
                    text.toString(),
                    OpenRouterFailure.MALFORMED,
                    ResponseDisposition.FAILED,
                    "A stream chunk was not valid JSON. The partial text is not a final answer.",
                )
            }
            sawChunk = true
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
        if (finishReason == "length") {
            return streamResult(
                generationId,
                modelId,
                text.toString(),
                OpenRouterFailure.INCOMPLETE,
                ResponseDisposition.TRUNCATED,
                "The finish reason is length. The answer was truncated and is not a complete final answer.",
            )
        }
        if (finishReason != null && finishReason != "stop") {
            return streamResult(
                generationId,
                modelId,
                text.toString(),
                OpenRouterFailure.MALFORMED,
                ResponseDisposition.FAILED,
                "The finish reason was $finishReason. The text is not a complete final answer.",
            )
        }
        if (sawDone && sawUsage && finishReason == "stop") {
            return streamResult(
                generationId,
                modelId,
                text.toString(),
                OpenRouterFailure.NONE,
                ResponseDisposition.COMPLETED,
                "The stream finished with stop, a usage chunk, and [DONE].",
                completed = true,
            )
        }
        if (!sawChunk && !sawDone) {
            return streamResult(
                generationId,
                modelId,
                text.toString(),
                OpenRouterFailure.INCOMPLETE,
                ResponseDisposition.OUTCOME_UNKNOWN,
                "The response had no stream chunks. The outcome is unknown and was not retried.",
            )
        }
        return streamResult(
            generationId,
            modelId,
            text.toString(),
            OpenRouterFailure.INCOMPLETE,
            ResponseDisposition.INTERRUPTED,
            "The stream ended before a completed stop response. The partial text is not a final answer.",
        )
    }

    fun classifyHttp(status: Int, headers: Map<String, String>): AdapterOutcome {
        val retry = headers.entries.firstOrNull { it.key.equals("retry-after", ignoreCase = true) }?.value
        return when (status) {
            401 -> AdapterOutcome(OpenRouterFailure.AUTHENTICATION, "OpenRouter rejected the credential. The key was not logged.", disposition = ResponseDisposition.FAILED)
            402 -> AdapterOutcome(OpenRouterFailure.PAYMENT_REQUIRED, "OpenRouter returned payment required. The provider was not retried and no credit was purchased.", disposition = ResponseDisposition.FAILED)
            403 -> AdapterOutcome(OpenRouterFailure.BUDGET_BLOCKED, "OpenRouter returned a budget block. The request was not retried.", disposition = ResponseDisposition.FAILED)
            429 -> AdapterOutcome(
                OpenRouterFailure.RATE_LIMIT,
                "OpenRouter rate limited the request. Retry-After is ${retry ?: "absent"}. No automatic retry was sent.",
                disposition = ResponseDisposition.FAILED,
            )
            else -> AdapterOutcome(OpenRouterFailure.MALFORMED, "OpenRouter returned HTTP $status. The body was not treated as a completion.", disposition = ResponseDisposition.FAILED)
        }
    }

    fun classifyFailure(error: Throwable): AdapterOutcome = when (error) {
        is CancellationException -> AdapterOutcome(OpenRouterFailure.CANCELLED, "The request was cancelled. No partial text was stored as complete.", disposition = ResponseDisposition.CANCELLED)
        is SocketTimeoutException -> AdapterOutcome(OpenRouterFailure.TIMEOUT, "The connection timed out. The outcome is unknown, so it was not retried.", disposition = ResponseDisposition.OUTCOME_UNKNOWN)
        else -> AdapterOutcome(OpenRouterFailure.CONNECTION, "The connection failed. The outcome was not retried. ${RedactedLog.redact(error.message.orEmpty())}", disposition = ResponseDisposition.OUTCOME_UNKNOWN)
    }

    suspend fun verifyMetadata(apiKey: String): MetadataReport {
        if (!authorization.metadataChecksAuthorized) {
            return emptyReport(
                OpenRouterFailure.POLICY,
                ConnectionState.VERIFICATION_REQUIRED,
                "No metadata request was sent. Confirmation in the app is required first.",
            )
        }
        val keyResult = exchange(buildKeyRequest(apiKey))
        if (keyResult.failure != OpenRouterFailure.NONE) {
            val state = when (keyResult.failure) {
                OpenRouterFailure.AUTHENTICATION -> ConnectionState.AUTHENTICATION_FAILED
                OpenRouterFailure.RATE_LIMIT, OpenRouterFailure.TIMEOUT, OpenRouterFailure.CONNECTION -> ConnectionState.VERIFICATION_REQUIRED
                else -> ConnectionState.BLOCKED
            }
            return emptyReport(keyResult.failure, state, keyResult.reason)
        }
        val observation = try {
            parseKey(keyResult.responseBody.orEmpty())
        } catch (invalid: org.json.JSONException) {
            return emptyReport(
                OpenRouterFailure.MALFORMED,
                ConnectionState.BLOCKED,
                "The key response was not the documented JSON object.",
            )
        }
        val modelsResult = exchange(buildModelsRequest(apiKey))
        if (modelsResult.failure != OpenRouterFailure.NONE) {
            return classifyMetadata(observation, emptyList()).copy(
                failure = modelsResult.failure,
                summary = "Key metadata was read. The model catalog request failed: ${modelsResult.reason}",
            )
        }
        val models = try {
            parseModels(modelsResult.responseBody.orEmpty())
        } catch (invalid: org.json.JSONException) {
            return classifyMetadata(observation, emptyList()).copy(
                failure = OpenRouterFailure.MALFORMED,
                summary = "The model catalog was not the documented JSON object.",
            )
        }
        return classifyMetadata(observation, models)
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
            AdapterOutcome(
                parsed.failure,
                parsed.reason,
                parsed.generationId,
                parsed.modelId,
                parsed.text,
                parsed.completed,
                request,
                parsed.disposition,
            )
        } else {
            AdapterOutcome(
                OpenRouterFailure.NONE,
                "Metadata was read. It was not logged with the credential.",
                request = request,
                responseBody = result.body,
            )
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

    private fun isAutomaticRoute(modelId: String): Boolean {
        if (modelId == OpenRouterEndpoints.FREE_ROUTER) return true
        return modelId.endsWith(":online") ||
            modelId.endsWith(":nitro") ||
            modelId.endsWith(":floor") ||
            modelId.endsWith(":exacto")
    }

    private fun emptyReport(failure: OpenRouterFailure, state: ConnectionState, summary: String) = MetadataReport(
        failure = failure,
        state = state,
        credentialValid = false,
        summary = summary,
        usageLimits = "Not observed.",
        remainingQuota = "Unknown",
        eligibleModels = emptyList(),
        restrictions = OpenRouterPluginSafety.UNRESOLVED,
    )

    private fun streamResult(
        generationId: String?,
        modelId: String,
        text: String,
        failure: OpenRouterFailure,
        disposition: ResponseDisposition,
        reason: String,
        completed: Boolean = false,
    ) = StreamParse(generationId, modelId, text, completed, failure, reason, disposition)
}

private fun usageText(key: KeyObservation): String {
    val tier = when (key.freeTierFlag) {
        null -> "Unknown"
        true -> "true"
        false -> "false"
    }
    val dailyLimit = key.dailyLimit?.toString() ?: "Unknown"
    val deprecated = key.deprecatedRateLimitRequests?.toString() ?: "absent"
    return "Documented daily free-model limit: $dailyLimit. Observed is_free_tier: $tier. This flag is not zero-cost eligibility. Deprecated rate_limit.requests: $deprecated. That field is not a remaining quota. The per-minute free-model ceiling is documented separately and was not returned as a remaining count."
}

private fun remainingText(key: KeyObservation): String {
    val credit = key.limitRemaining?.toString() ?: "Unknown"
    val daily = key.dailyRemaining?.toString() ?: "Unknown"
    val used = key.dailyUsed?.toString() ?: "Unknown"
    val usage = key.usage?.toString() ?: "Unknown"
    return "Credit limit remaining: $credit. Free-model requests remaining: $daily. Free-model requests used: $used. Observed usage: $usage. A missing remaining count stays Unknown."
}

private fun JSONObject.optionalString(name: String): String? {
    if (!has(name) || isNull(name)) return null
    val value = optString(name)
    return value.ifBlank { null }
}

private fun JSONObject.optionalBoolean(name: String): Boolean? {
    if (!has(name) || isNull(name)) return null
    return when (val value = get(name)) {
        is Boolean -> value
        else -> null
    }
}

private fun JSONObject.stringList(name: String): List<String> {
    val array = optJSONArray(name) ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val value = array.optString(index)
            if (value.isNotBlank()) add(value)
        }
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
