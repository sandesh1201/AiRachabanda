package com.airachabanda.app.provider.openrouter

import org.json.JSONObject
import java.io.File

data class OpenRouterSnapshot(
    val state: ConnectionState,
    val authentication: String,
    val metadata: String,
    val zeroCost: String,
    val lastVerification: String,
    val usageLimits: String,
    val remainingQuota: String,
    val models: String,
    val restrictions: String,
)

class OpenRouterStatusStore(private val file: File) {
    fun read(): OpenRouterSnapshot? {
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText())
            OpenRouterSnapshot(
                state = ConnectionState.valueOf(json.getString("state")),
                authentication = json.getString("authentication"),
                metadata = json.getString("metadata"),
                zeroCost = json.getString("zeroCost"),
                lastVerification = json.getString("lastVerification"),
                usageLimits = json.getString("usageLimits"),
                remainingQuota = json.getString("remainingQuota"),
                models = json.getString("models"),
                restrictions = json.getString("restrictions"),
            )
        } catch (invalid: Exception) {
            null
        }
    }

    fun write(snapshot: OpenRouterSnapshot) {
        val json = JSONObject()
            .put("state", snapshot.state.name)
            .put("authentication", snapshot.authentication)
            .put("metadata", snapshot.metadata)
            .put("zeroCost", snapshot.zeroCost)
            .put("lastVerification", snapshot.lastVerification)
            .put("usageLimits", snapshot.usageLimits)
            .put("remainingQuota", snapshot.remainingQuota)
            .put("models", snapshot.models)
            .put("restrictions", snapshot.restrictions)
        file.parentFile?.mkdirs()
        file.writeText(json.toString())
    }

    fun clear() {
        file.delete()
    }
}

fun openRouterSnapshot(stored: Boolean, saved: OpenRouterSnapshot?): OpenRouterSnapshot {
    if (!stored) {
        return OpenRouterSnapshot(
            state = ConnectionState.NOT_CONFIGURED,
            authentication = "Not checked.",
            metadata = "Not checked. Saving a key does not send a request.",
            zeroCost = "Unverified. Inference is off.",
            lastVerification = "Never.",
            usageLimits = "Not observed.",
            remainingQuota = "Unknown",
            models = "None discovered. This screen does not use a hardcoded catalog.",
            restrictions = OpenRouterPluginSafety.UNRESOLVED,
        )
    }
    if (saved == null) {
        return OpenRouterSnapshot(
            state = ConnectionState.VERIFICATION_REQUIRED,
            authentication = "Not checked.",
            metadata = "Verification required. No metadata request has been sent.",
            zeroCost = "Unverified. A stored key is not zero-cost eligibility. Inference is off.",
            lastVerification = "Never.",
            usageLimits = "Not observed.",
            remainingQuota = "Unknown",
            models = "None discovered. This screen does not use a hardcoded catalog.",
            restrictions = OpenRouterPluginSafety.UNRESOLVED,
        )
    }
    return saved
}

fun snapshotFromReport(report: MetadataReport, verifiedAt: String): OpenRouterSnapshot {
    val names = report.eligibleModels.take(8).joinToString("; ") { model ->
        val title = model.name.ifBlank { model.id }
        "$title (${model.id})"
    }
    val extra = (report.eligibleModels.size - 8).coerceAtLeast(0)
    val models = when {
        report.eligibleModels.isEmpty() -> "None with verified zero pricing."
        extra == 0 -> names
        else -> "$names; and $extra more."
    }
    return OpenRouterSnapshot(
        state = report.state,
        authentication = if (report.credentialValid) "Authenticated. The key is not shown." else "Not authenticated. ${report.summary}",
        metadata = report.summary,
        zeroCost = "${report.state.name}. Metadata verification does not unlock inference.",
        lastVerification = verifiedAt,
        usageLimits = report.usageLimits,
        remainingQuota = report.remainingQuota,
        models = models,
        restrictions = report.restrictions,
    )
}
