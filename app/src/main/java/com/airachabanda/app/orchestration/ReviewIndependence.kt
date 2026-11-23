package com.airachabanda.app.orchestration

import com.airachabanda.app.core.model.ModelIdentity

enum class ReviewClassification {
    SELF_CRITIQUE,
    SAME_MODEL_REVIEW,
    CROSS_MODEL_REVIEW,
    INDEPENDENCE_UNVERIFIED,
}

data class ReviewAssessment(
    val classification: ReviewClassification,
    val disclosure: String,
)

object ReviewIndependence {
    fun classify(
        reviewerKey: String,
        reviewerVerifiedModel: String,
        authorKey: String,
        authorVerifiedModel: String,
    ): ReviewClassification {
        if (reviewerKey == authorKey) return ReviewClassification.SELF_CRITIQUE
        val reviewerModel = ModelIdentity.declared(reviewerVerifiedModel)
        val authorModel = ModelIdentity.declared(authorVerifiedModel)
        if (reviewerModel == ModelIdentity.UNKNOWN || authorModel == ModelIdentity.UNKNOWN) {
            return ReviewClassification.INDEPENDENCE_UNVERIFIED
        }
        if (reviewerModel == authorModel) return ReviewClassification.SAME_MODEL_REVIEW
        return ReviewClassification.CROSS_MODEL_REVIEW
    }

    fun summarize(reviewer: EligibleParticipant, comparedWith: List<EligibleParticipant>): ReviewAssessment {
        val peers = comparedWith.ifEmpty { listOf(reviewer) }
        val classifications = peers.map { peer ->
            classify(
                reviewerKey = reviewer.participantKey,
                reviewerVerifiedModel = reviewer.verifiedModelId,
                authorKey = peer.participantKey,
                authorVerifiedModel = peer.verifiedModelId,
            )
        }
        val classification = when {
            ReviewClassification.SELF_CRITIQUE in classifications -> ReviewClassification.SELF_CRITIQUE
            ReviewClassification.INDEPENDENCE_UNVERIFIED in classifications -> ReviewClassification.INDEPENDENCE_UNVERIFIED
            ReviewClassification.SAME_MODEL_REVIEW in classifications -> ReviewClassification.SAME_MODEL_REVIEW
            else -> ReviewClassification.CROSS_MODEL_REVIEW
        }
        val disclosure = when (classification) {
            ReviewClassification.SELF_CRITIQUE ->
                "Review classification: SELF_CRITIQUE. Reduced verification. The same participant is reviewing work from its own role. This is not independent verification."
            ReviewClassification.SAME_MODEL_REVIEW ->
                "Review classification: SAME_MODEL_REVIEW. Different hosts of the same model are not independent reviewers. This is not independent verification."
            ReviewClassification.INDEPENDENCE_UNVERIFIED ->
                "Review classification: INDEPENDENCE_UNVERIFIED. Unknown or user-declared model identity cannot establish independence. This is not independent verification."
            ReviewClassification.CROSS_MODEL_REVIEW ->
                "Review classification: CROSS_MODEL_REVIEW. The verified model identities differ. Cross-model agreement is not proof."
        }
        return ReviewAssessment(classification, disclosure)
    }
}
