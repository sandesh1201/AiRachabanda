package com.airachabanda.app.provider.gemini

import com.airachabanda.app.provider.openrouter.HttpExchange
import com.airachabanda.app.provider.openrouter.HttpTransport
import com.airachabanda.app.provider.openrouter.ResponseDisposition
import com.airachabanda.app.security.RedactedLog
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Sends the owner-confirmed Gemini metadata reads and, only after those pass, one generation.
 * The council path stays on GeminiAuthorization.PRODUCTION and does not use this type.
 */
class GeminiOwnerSession(
    private val transport: HttpTransport,
) {
    data class Record(
        val keyFingerprint: String,
        val lookupParent: String,
        val projectId: String,
        val billingProjectId: String,
        val modelId: String,
    )

    data class MetadataReport(
        val record: Record?,
        val summary: String,
    )

    data class InferenceReport(
        val sent: Boolean,
        val text: String,
        val modelId: String,
        val disposition: ResponseDisposition,
        val latencyMs: Long,
        val promptTokens: Int?,
        val outputTokens: Int?,
        val budgetRemaining: Int,
        val grade: String,
        val billingState: String,
        val summary: String,
    )

    suspend fun verifyMetadata(apiKey: String, accessToken: String, metadataConsent: Boolean): MetadataReport {
        if (!metadataConsent) return MetadataReport(null, "Metadata consent was not given. No Google request was sent.")
        if (apiKey.isBlank()) return MetadataReport(null, "No Gemini API key is stored. No lookup was sent.")
        val blocked = GeminiLiveGate.metadata(
            GeminiLiveGate.Input(
                metadataConsent = true,
                inferenceConsent = false,
                oauthState = tokenState(accessToken),
                binding = GeminiProjectBinding.assess(null, null, null),
                modelId = "",
                freeTierKnown = false,
                sessionBudgetRemaining = 1,
                quotaExhausted = false,
            ),
        )
        if (!blocked.permitted) return MetadataReport(null, sanitize(blocked.reason))
        val lookup = exchange(lookupExchange(apiKey, accessToken))
            ?: return MetadataReport(null, "The key lookup connection failed. It was not retried.")
        if (lookup.status == 401) return MetadataReport(null, "The Google authorization has expired. No billing read was sent.")
        if (lookup.status == 403) return MetadataReport(null, "The Google authorization is missing apikeys.keys.lookup. No billing read was sent.")
        if (lookup.status !in 200..299) return MetadataReport(null, "The key lookup returned HTTP ${lookup.status}. The key was not logged.")
        val parsedLookup = parseLookup(lookup.body)
            ?: return MetadataReport(null, "The key lookup did not name a project. A typed project ID was not used.")
        val suffix = parsedLookup.parent.removePrefix("projects/")
        val billingCall = exchange(authorizedGet(GeminiProjectBinding.billingUrl(suffix), accessToken))
            ?: return MetadataReport(null, "The billing connection failed. It was not retried.")
        if (billingCall.status == 401) return MetadataReport(null, "The Google authorization expired before billing was read.")
        if (billingCall.status == 403) return MetadataReport(null, "The Google authorization is missing resourcemanager.projects.get. Billing was not accepted.")
        if (billingCall.status !in 200..299) return MetadataReport(null, "The billing read returned HTTP ${billingCall.status}.")
        val billing = parseBilling(billingCall.body)
            ?: return MetadataReport(null, "The billing response did not say whether billing is enabled.")
        var identity: GeminiProjectBinding.ProjectIdentity? = null
        if (!GeminiProjectBinding.sameProject(parsedLookup.parent, billing, null)) {
            val identityCall = exchange(authorizedGet("${GeminiProjectBinding.RESOURCE_MANAGER_URL}/$suffix", accessToken))
                ?: return MetadataReport(null, "The project identity connection failed. Billing was not accepted.")
            if (identityCall.status !in 200..299) {
                return MetadataReport(null, "The billing project did not match the key lookup, and project identity returned HTTP ${identityCall.status}.")
            }
            identity = parseIdentity(identityCall.body)
        }
        val binding = GeminiProjectBinding.assess(parsedLookup, billing, typedProjectId = null, identity = identity)
        if (!binding.credentialBoundToProject) return MetadataReport(null, sanitize(binding.reason))
        if (!binding.billingUnlinked) return MetadataReport(null, sanitize(binding.reason))
        val modelsCall = exchange(modelsExchange(apiKey))
            ?: return MetadataReport(null, "The model list connection failed. No generation request was sent.")
        if (modelsCall.status !in 200..299) return MetadataReport(null, "The model list returned HTTP ${modelsCall.status}. No generation request was sent.")
        val selected = GeminiFreeCatalog.select(GeminiAdapter(transport).parseModels(modelsCall.body))
            ?: return MetadataReport(null, "No documented free-tier text model with generateContent was listed. No generation request was sent.")
        val record = Record(
            keyFingerprint = fingerprint(apiKey),
            lookupParent = parsedLookup.parent,
            projectId = suffix,
            billingProjectId = billing.projectId,
            modelId = selected,
        )
        return MetadataReport(
            record,
            "The API key belongs to $suffix and billingEnabled is false. The selected free-tier model is $selected. ${GeminiFreeCatalog.PROTECTION}",
        )
    }

    suspend fun infer(
        apiKey: String,
        accessToken: String,
        record: Record?,
        inferenceConsent: Boolean,
        budgetRemaining: Int,
    ): InferenceReport {
        val empty = InferenceReport(
            sent = false,
            text = "",
            modelId = record?.modelId.orEmpty(),
            disposition = ResponseDisposition.FAILED,
            latencyMs = 0,
            promptTokens = null,
            outputTokens = null,
            budgetRemaining = budgetRemaining,
            grade = "No provider text was returned.",
            billingState = "Not revalidated.",
            summary = "",
        )
        if (record == null) return empty.copy(summary = "Billing eligibility is not established. No generation request was sent.")
        if (fingerprint(apiKey) != record.keyFingerprint) {
            return empty.copy(summary = "The stored API key changed. The previous billing result was discarded. No generation request was sent.")
        }
        val binding = GeminiProjectBinding.assess(
            GeminiProjectBinding.KeyLookup(record.lookupParent, "${record.lookupParent}/locations/global/keys/verified"),
            GeminiProjectBinding.ProjectBillingRead(record.projectId, false, ""),
            typedProjectId = null,
        )
        val decision = GeminiLiveGate.inference(
            GeminiLiveGate.Input(
                metadataConsent = true,
                inferenceConsent = inferenceConsent,
                oauthState = tokenState(accessToken),
                binding = binding,
                modelId = record.modelId,
                freeTierKnown = GeminiFreeCatalog.eligible(record.modelId),
                sessionBudgetRemaining = budgetRemaining,
                quotaExhausted = false,
            ),
        )
        if (!decision.permitted) return empty.copy(summary = sanitize(decision.reason))
        val reread = exchange(authorizedGet(GeminiProjectBinding.billingUrl(record.projectId), accessToken))
            ?: return empty.copy(summary = "Billing could not be revalidated. No generation request was sent.")
        if (reread.status == 401) return empty.copy(summary = "The Google authorization expired. No generation request was sent.")
        if (reread.status == 403) return empty.copy(summary = "Billing could not be revalidated because permission was denied. No generation request was sent.")
        if (reread.status !in 200..299) return empty.copy(summary = "Billing revalidation returned HTTP ${reread.status}. No generation request was sent.")
        val billing = parseBilling(reread.body)
        if (billing == null || billing.billingEnabled == null) {
            return empty.copy(summary = "Billing revalidation did not say whether billing is enabled. No generation request was sent.")
        }
        if (billing.billingEnabled) {
            return empty.copy(summary = "Billing is enabled for the key's project. No generation request was sent.", billingState = "billingEnabled true")
        }
        val sameProject = GeminiProjectBinding.sameProject(record.lookupParent, billing) ||
            billing.projectId == record.projectId ||
            billing.projectId == record.billingProjectId
        if (!sameProject) {
            return empty.copy(summary = "Billing revalidation named a different project. No generation request was sent.")
        }
        val request = HttpExchange(
            method = "POST",
            url = "https://generativelanguage.googleapis.com/v1beta/models/${record.modelId}:streamGenerateContent?alt=sse",
            headers = mapOf(
                "x-goog-api-key" to apiKey,
                "Content-Type" to "application/json",
                "Accept" to "text/event-stream",
            ),
            body = GeminiLiveGate.requestBody().toString(),
        )
        val started = System.nanoTime()
        val result = exchange(request) ?: return empty.copy(
            sent = true,
            budgetRemaining = budgetRemaining - 1,
            summary = "The generation connection failed. It was not retried.",
            billingState = "billingEnabled false",
        )
        val latencyMs = (System.nanoTime() - started) / 1_000_000
        val remaining = budgetRemaining - 1
        if (result.status == 401 || result.status == 403) {
            return empty.copy(sent = true, latencyMs = latencyMs, budgetRemaining = remaining, billingState = "billingEnabled false", summary = "Gemini rejected the credential. The key was not logged. It was not retried.")
        }
        if (result.status == 429) {
            return empty.copy(sent = true, latencyMs = latencyMs, budgetRemaining = remaining, disposition = ResponseDisposition.FAILED, billingState = "billingEnabled false", summary = "Gemini rate limited the request. No automatic retry and no paid fallback were sent.")
        }
        if (result.status !in 200..299) {
            return empty.copy(sent = true, latencyMs = latencyMs, budgetRemaining = remaining, billingState = "billingEnabled false", summary = "Gemini returned HTTP ${result.status}. It was not retried.")
        }
        val parsed = GeminiAdapter(transport).parseStream(record.modelId, result.body)
        val usage = parseUsage(result.body)
        val grade = if (parsed.text.isBlank()) "No provider text was returned." else GeminiLiveGate.grade(parsed.text)
        val usageText = if (usage.first == null && usage.second == null) {
            "Token usage was not reported."
        } else {
            "Reported prompt tokens ${usage.first ?: "unknown"}, output tokens ${usage.second ?: "unknown"}."
        }
        return InferenceReport(
            sent = true,
            text = parsed.text,
            modelId = record.modelId,
            disposition = parsed.disposition,
            latencyMs = latencyMs,
            promptTokens = usage.first,
            outputTokens = usage.second,
            budgetRemaining = remaining,
            grade = grade,
            billingState = "billingEnabled false",
            summary = sanitize(
                "Model ${record.modelId}. Status ${parsed.disposition}. Latency ${latencyMs} ms. $usageText Budget remaining $remaining. $grade Billing revalidation: billingEnabled false. ${GeminiFreeCatalog.BILLING_LIMIT}",
            ),
        )
    }

    private fun tokenState(accessToken: String): GeminiLiveGate.OauthState =
        if (accessToken.isBlank()) GeminiLiveGate.OauthState.MISSING else GeminiLiveGate.OauthState.READY

    private suspend fun exchange(request: HttpExchange) = try {
        transport.exchange(request)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        null
    }

    private fun lookupExchange(apiKey: String, accessToken: String): HttpExchange {
        val encoded = URLEncoder.encode(apiKey, Charsets.UTF_8.name())
        return authorizedGet("${GeminiProjectBinding.LOOKUP_URL}?keyString=$encoded", accessToken)
    }

    private fun modelsExchange(apiKey: String) = HttpExchange(
        method = "GET",
        url = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=100",
        headers = mapOf("x-goog-api-key" to apiKey, "Accept" to "application/json"),
        body = null,
    )

    private fun authorizedGet(url: String, accessToken: String) = HttpExchange(
        method = "GET",
        url = url,
        headers = mapOf("Authorization" to "Bearer $accessToken", "Accept" to "application/json"),
        body = null,
    )

    private fun sanitize(message: String): String = RedactedLog.redact(message)

    companion object {
        fun fingerprint(apiKey: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(apiKey.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        fun parseLookup(body: String): GeminiProjectBinding.KeyLookup? {
            val root = json(body) ?: return null
            val parent = root.optString("parent")
            val name = root.optString("name")
            if (parent.isBlank() || name.isBlank()) return null
            return GeminiProjectBinding.KeyLookup(parent, name)
        }

        fun parseBilling(body: String): GeminiProjectBinding.ProjectBillingRead? {
            val root = json(body) ?: return null
            if (!root.has("billingEnabled") || root.isNull("billingEnabled")) {
                return GeminiProjectBinding.ProjectBillingRead(
                    projectId = root.optString("projectId"),
                    billingEnabled = null,
                    billingAccountName = root.optString("billingAccountName"),
                    resourceName = root.optString("name"),
                )
            }
            return GeminiProjectBinding.ProjectBillingRead(
                projectId = root.optString("projectId"),
                billingEnabled = root.optBoolean("billingEnabled"),
                billingAccountName = root.optString("billingAccountName"),
                resourceName = root.optString("name"),
            )
        }

        fun parseIdentity(body: String): GeminiProjectBinding.ProjectIdentity? {
            val root = json(body) ?: return null
            val number = root.optString("projectNumber")
            val id = root.optString("projectId")
            if (number.isBlank() || id.isBlank()) return null
            return GeminiProjectBinding.ProjectIdentity(number, id)
        }

        fun parseUsage(body: String): Pair<Int?, Int?> {
            var prompt: Int? = null
            var output: Int? = null
            body.lineSequence().forEach { raw ->
                val line = raw.trim().removePrefix("data:").trim()
                if (line.isBlank() || line.startsWith(":")) return@forEach
                val json = json(line) ?: return@forEach
                val usage = json.optJSONObject("usageMetadata") ?: return@forEach
                if (usage.has("promptTokenCount")) prompt = usage.optInt("promptTokenCount")
                if (usage.has("candidatesTokenCount")) output = usage.optInt("candidatesTokenCount")
            }
            return prompt to output
        }

        private fun json(body: String): JSONObject? = try {
            JSONObject(body)
        } catch (invalid: org.json.JSONException) {
            null
        }
    }
}
