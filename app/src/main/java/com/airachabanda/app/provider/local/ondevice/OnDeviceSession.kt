package com.airachabanda.app.provider.local.ondevice

import com.airachabanda.app.core.model.BillingEligibility
import com.airachabanda.app.core.model.ModelIdentity
import com.airachabanda.app.core.model.ParticipantRole
import com.airachabanda.app.core.model.ProviderId
import com.airachabanda.app.orchestration.EligibleParticipant
import com.airachabanda.app.orchestration.ReviewIndependence

enum class ThermalLevel { NORMAL, SEVERE }

data class ScheduleDecision(val loaded: Boolean, val residentModelId: String?, val detail: String)

data class GenerationBounds(val accepted: Boolean, val contextTokens: Int, val outputTokens: Int, val timeoutMs: Long, val detail: String)

data class GenerationPermit(val generationId: Long, val modelId: String, val cancelled: Boolean)

data class LocalGenerationResult(
    val modelId: String,
    val text: String,
    val fixture: Boolean,
    val cancelled: Boolean,
    val failure: String?,
)

class OnDeviceScheduler {
    private var resident: String? = null
    private var activeGeneration: Long = 0
    private val cancelled = mutableSetOf<Long>()

    fun schedule(modelId: String, availableRamBytes: Long, thermal: ThermalLevel): ScheduleDecision {
        val model = OnDeviceModelCatalog.find(modelId)
            ?: return ScheduleDecision(false, resident, "That model is not in the recommended catalog.")
        if (thermal == ThermalLevel.SEVERE) {
            return ScheduleDecision(false, resident, "The device is thermally limited. Generation was not started.")
        }
        val needed = model.publishedPeakRamBytes + OnDeviceRuntime.RAM_HEADROOM_BYTES
        if (availableRamBytes < needed) {
            return ScheduleDecision(false, resident, "Available memory is below the published peak for ${model.displayName}. The model was not loaded.")
        }
        val current = resident
        if (current != null && current != model.id) {
            return ScheduleDecision(false, current, "One model stays resident. Unload it before loading another.")
        }
        resident = model.id
        return ScheduleDecision(true, model.id, "${model.displayName} is the only resident model.")
    }

    fun unload(modelId: String): ScheduleDecision {
        if (resident == modelId) resident = null
        return ScheduleDecision(false, resident, "The model was unloaded.")
    }

    fun bounds(modelId: String, requestedContext: Int, requestedOutput: Int): GenerationBounds {
        val model = OnDeviceModelCatalog.find(modelId)
            ?: return GenerationBounds(false, 0, 0, 0, "That model is not in the recommended catalog.")
        if (requestedContext <= 0 || requestedContext > model.contextTokens) {
            return GenerationBounds(false, model.contextTokens, 0, 0, "The context cap for ${model.displayName} is ${model.contextTokens} tokens.")
        }
        if (requestedOutput <= 0 || requestedOutput > OnDeviceRuntime.MAX_OUTPUT_TOKENS) {
            return GenerationBounds(false, requestedContext, OnDeviceRuntime.MAX_OUTPUT_TOKENS, 0, "Output is limited to ${OnDeviceRuntime.MAX_OUTPUT_TOKENS} tokens.")
        }
        return GenerationBounds(true, requestedContext, requestedOutput, OnDeviceRuntime.GENERATION_TIMEOUT_MS, "Bounds accepted.")
    }

    fun begin(modelId: String): GenerationPermit {
        activeGeneration += 1
        return GenerationPermit(activeGeneration, modelId, cancelled = false)
    }

    fun cancel(generationId: Long) {
        cancelled += generationId
    }

    fun accept(permit: GenerationPermit): Boolean =
        permit.generationId == activeGeneration && permit.generationId !in cancelled
}

interface OnDeviceEngine {
    val genuineInference: Boolean
    fun generate(prompt: String, permit: GenerationPermit, accept: Boolean): LocalGenerationResult
}

object UnavailableOnDeviceEngine : OnDeviceEngine {
    override val genuineInference: Boolean = false

    override fun generate(prompt: String, permit: GenerationPermit, accept: Boolean): LocalGenerationResult {
        val failure = when {
            prompt.isBlank() -> "The prompt was empty. No model file was read."
            !accept -> "Generation was cancelled before a model ran."
            else -> "LiteRT-LM is not installed. No model file was read. This is not a model response."
        }
        return LocalGenerationResult(permit.modelId, "", fixture = false, cancelled = !accept, failure = failure)
    }
}

object FixtureOnDeviceEngine : OnDeviceEngine {
    override val genuineInference: Boolean = false

    override fun generate(prompt: String, permit: GenerationPermit, accept: Boolean): LocalGenerationResult {
        if (prompt.isBlank() || !accept) {
            return LocalGenerationResult(permit.modelId, "", fixture = true, cancelled = true, failure = "The fixture generation was cancelled.")
        }
        return LocalGenerationResult(
            modelId = permit.modelId,
            text = "FIXTURE_LOCAL_INFERENCE. This text was not produced by a model.",
            fixture = true,
            cancelled = false,
            failure = null,
        )
    }
}

object OnDeviceParticipation {
    val roles: List<ParticipantRole> = ParticipantRole.entries

    fun seats(modelIds: List<String>): List<EligibleParticipant> =
        modelIds.mapNotNull { id ->
            val model = OnDeviceModelCatalog.find(id) ?: return@mapNotNull null
            EligibleParticipant(
                providerId = ProviderId.LOCAL,
                assisted = false,
                billing = BillingEligibility.UNKNOWN,
                userDeclaredModelId = model.id,
                verifiedModelId = model.id,
                participantKey = "LOCAL:${model.id}",
                capabilityNote = model.displayName,
            )
        }.distinctBy { it.participantKey }

    fun review(reviewerModelId: String, authorModelId: String) = ReviewIndependence.classify(
        reviewerKey = "LOCAL:$reviewerModelId",
        reviewerVerifiedModel = ModelIdentity.declared(reviewerModelId),
        authorKey = "LOCAL:$authorModelId",
        authorVerifiedModel = ModelIdentity.declared(authorModelId),
    )

    fun remaining(participants: List<String>, failed: String): List<String> = participants.filter { it != failed }

    fun rejectToolUse(request: String): String =
        "Local generation cannot run device code or open files. The request was refused: ${request.take(80)}"
}

object OnDeviceDispatch {
    fun reason(modelId: String): String {
        val model = OnDeviceModelCatalog.find(modelId)
        return if (model == null) {
            "No on-device model was selected."
        } else {
            "LiteRT-LM is prepared for ${model.displayName}. No runtime or model file is installed, so no tokens were generated."
        }
    }
}
