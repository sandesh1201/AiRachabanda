package com.airachabanda.app.provider.cloudflare

import com.airachabanda.app.core.model.ModelIdentity
import com.airachabanda.app.provider.billing.CloudflareBillingPolicy
import com.airachabanda.app.provider.openrouter.HttpExchange
import com.airachabanda.app.provider.openrouter.HttpTransport
import com.airachabanda.app.provider.openrouter.OpenRouterFailure
import com.airachabanda.app.provider.openrouter.ResponseDisposition
import com.airachabanda.app.security.RedactedLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CancellationException

class CloudflareAuthorization(
    val metadataAuthorized: Boolean = false,
    val inferenceAuthorized: Boolean = false,
) {
    companion object {
        val PRODUCTION = CloudflareAuthorization()
    }
}

data class CloudflareModel(
    val id: String,
    val name: String,
    val task: String,
    val paidRequired: Boolean,
)

data class CloudflareOutcome(
    val failure: OpenRouterFailure,
    val disposition: ResponseDisposition,
    val reason: String,
    val modelId: String = ModelIdentity.UNKNOWN,
    val text: String = "",
    val generationId: String? = null,
    val completed: Boolean = false,
    val request: HttpExchange? = null,
)

class CloudflareAdapter(
    private val transport: HttpTransport,
    private val authorization: CloudflareAuthorization = CloudflareAuthorization.PRODUCTION,
) {
    fun verifyMetadata(accountId: String, apiToken: String): CloudflareOutcome {
        if (!authorization.metadataAuthorized || apiToken.isBlank()) {
            return CloudflareOutcome(
                OpenRouterFailure.POLICY,
                ResponseDisposition.FAILED,
                "No Cloudflare metadata request was sent. Live metadata is not authorized.",
            )
        }
        if (!ACCOUNT_ID.matches(accountId)) {
            return CloudflareOutcome(OpenRouterFailure.POLICY, ResponseDisposition.FAILED, "The account ID is not a 32-character Cloudflare account ID.")
        }
        return CloudflareOutcome(OpenRouterFailure.POLICY, ResponseDisposition.FAILED, "Metadata consent is not enabled for a live call in this build.")
    }

    suspend fun complete(
        accountId: String,
        apiToken: String,
        modelId: String,
        prompt: String,
        sessionBudgetRemaining: Int,
        quotaExhausted: Boolean = false,
        subscriptionsRead: Boolean = false,
        paidWorkersPlanListed: Boolean = false,
        textGeneration: Boolean = true,
    ): CloudflareOutcome {
        val decision = CloudflareBillingPolicy.assess(
            subscriptionsRead = subscriptionsRead,
            paidWorkersPlanListed = paidWorkersPlanListed,
            modelId = modelId,
            textGeneration = textGeneration,
            quotaExhausted = quotaExhausted,
            sessionBudgetRemaining = sessionBudgetRemaining,
            gatewayRequested = false,
        )
        if (!decision.accepted) {
            return CloudflareOutcome(OpenRouterFailure.POLICY, ResponseDisposition.FAILED, decision.reason)
        }
        if (!ACCOUNT_ID.matches(accountId) || !MODEL_ID.matches(modelId)) {
            return CloudflareOutcome(OpenRouterFailure.POLICY, ResponseDisposition.FAILED, "The account ID or model ID was rejected before a request was built.")
        }
        val body = JSONObject().put("prompt", prompt).put("stream", true).put("max_tokens", 128)
        val request = HttpExchange(
            method = "POST",
            url = "https://api.cloudflare.com/client/v4/accounts/$accountId/ai/run/$modelId",
            headers = mapOf(
                "Authorization" to "Bearer $apiToken",
                "Content-Type" to "application/json",
                "Accept" to "text/event-stream",
            ),
            body = body.toString(),
        )
        if (!authorization.inferenceAuthorized) {
            RedactedLog.d("Cloudflare", "Prepared POST Workers AI run. The token was not logged.")
            return CloudflareOutcome(
                OpenRouterFailure.POLICY,
                ResponseDisposition.FAILED,
                decision.reason,
                request = request,
            )
        }
        val result = try {
            transport.exchange(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: java.net.SocketTimeoutException) {
            return CloudflareOutcome(OpenRouterFailure.TIMEOUT, ResponseDisposition.OUTCOME_UNKNOWN, "The Cloudflare request timed out. It was not retried.")
        } catch (error: Exception) {
            return CloudflareOutcome(OpenRouterFailure.CONNECTION, ResponseDisposition.OUTCOME_UNKNOWN, "The Cloudflare connection failed. It was not retried.")
        }
        if (result.status == 401 || result.status == 403) {
            return CloudflareOutcome(OpenRouterFailure.AUTHENTICATION, ResponseDisposition.FAILED, "Cloudflare rejected the credential. The token was not logged.")
        }
        if (result.status == 429) {
            return CloudflareOutcome(OpenRouterFailure.RATE_LIMIT, ResponseDisposition.FAILED, "Cloudflare rate limited or exhausted the request. No automatic retry was sent.")
        }
        if (result.status !in 200..299) {
            return CloudflareOutcome(OpenRouterFailure.MALFORMED, ResponseDisposition.FAILED, "Cloudflare returned HTTP ${result.status}.")
        }
        return parseRun(modelId, result.body)
    }

    fun parseModels(body: String): List<CloudflareModel> {
        val root = JSONObject(body)
        val result = root.optJSONArray("result") ?: JSONArray()
        return buildList {
            for (index in 0 until result.length()) {
                val row = result.optJSONObject(index) ?: continue
                val id = row.optString("name").ifBlank { row.optString("id") }
                if (id.isBlank()) continue
                val task = row.optJSONObject("task")?.optString("name").orEmpty().ifBlank { row.optString("task") }
                add(CloudflareModel(id = id, name = row.optString("description"), task = task, paidRequired = id in CloudflareBillingPolicy.PAID_REQUIRED_MODELS))
            }
        }
    }

    fun parseSubscriptions(body: String): Boolean {
        val result = JSONObject(body).optJSONArray("result") ?: return false
        for (index in 0 until result.length()) {
            val plan = result.optJSONObject(index)?.optJSONObject("rate_plan")?.optString("id").orEmpty()
            if (plan == "WORKERS_PAID" || plan.startsWith("PARTNERS_WORKERS")) return true
        }
        return false
    }

    fun parseRun(modelId: String, body: String): CloudflareOutcome {
        if (body.contains("data:")) {
            val text = StringBuilder()
            var sawDone = false
            body.lineSequence().forEach { raw ->
                val line = raw.trim()
                if (!line.startsWith("data:")) return@forEach
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") {
                    sawDone = true
                    return@forEach
                }
                val json = try {
                    JSONObject(payload)
                } catch (invalid: org.json.JSONException) {
                    return CloudflareOutcome(OpenRouterFailure.MALFORMED, ResponseDisposition.FAILED, "A Cloudflare stream chunk was not valid JSON.", modelId, text.toString())
                }
                text.append(json.optString("response"))
            }
            if (!sawDone) {
                return CloudflareOutcome(OpenRouterFailure.INCOMPLETE, ResponseDisposition.INTERRUPTED, "The Cloudflare stream ended early. The partial text is not a final answer.", modelId, text.toString(), generationId = "cloudflare:$modelId", completed = false)
            }
            return CloudflareOutcome(OpenRouterFailure.NONE, ResponseDisposition.COMPLETED, "The Cloudflare stream finished.", modelId, text.toString(), generationId = "cloudflare:$modelId", completed = true)
        }
        val root = try {
            JSONObject(body)
        } catch (invalid: org.json.JSONException) {
            return CloudflareOutcome(OpenRouterFailure.MALFORMED, ResponseDisposition.FAILED, "The Cloudflare response was not JSON.")
        }
        if (!root.optBoolean("success", false)) {
            return CloudflareOutcome(OpenRouterFailure.MALFORMED, ResponseDisposition.FAILED, "Cloudflare did not mark the response successful.")
        }
        val response = root.optJSONObject("result")?.optString("response").orEmpty()
        if (response.isBlank()) {
            return CloudflareOutcome(OpenRouterFailure.INCOMPLETE, ResponseDisposition.OUTCOME_UNKNOWN, "Cloudflare returned no response text. The outcome is unknown and was not retried.", modelId)
        }
        return CloudflareOutcome(OpenRouterFailure.NONE, ResponseDisposition.COMPLETED, "Cloudflare returned a completed text response.", modelId, response, generationId = "cloudflare:$modelId", completed = true)
    }

    private companion object {
        val ACCOUNT_ID = Regex("^[a-fA-F0-9]{32}$")
        val MODEL_ID = Regex("^@cf/[A-Za-z0-9][A-Za-z0-9._/-]{0,120}$")
    }
}
