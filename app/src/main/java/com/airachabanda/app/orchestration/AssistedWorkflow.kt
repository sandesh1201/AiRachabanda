package com.airachabanda.app.orchestration

import com.airachabanda.app.core.model.ProviderId
import com.airachabanda.app.provider.ProviderAccess

object ImportRules {
    data class Result(val accepted: Boolean, val reason: String)

    fun check(providerId: ProviderId, body: String, roundNumber: Int): Result {
        if (ProviderAccess.active(providerId) != com.airachabanda.app.core.model.ParticipationMode.ASSISTED) {
            return Result(false, "This provider has no free assisted chat path.")
        }
        if (body.isBlank()) {
            return Result(false, "Paste a response before importing it.")
        }
        if (roundNumber < 1) {
            return Result(false, "The discussion round must be 1 or greater.")
        }
        return Result(true, "The text will be stored as a user-supplied response attributed to the selected provider.")
    }
}

object AssistedPrompt {
    fun prepare(providerName: String, question: String, modeLabel: String): String = buildString {
        appendLine("AI Rachabanda assisted research prompt")
        appendLine("Provider: $providerName")
        appendLine("Research mode: $modeLabel")
        appendLine()
        appendLine("Please answer the question below. State uncertainty and distinguish evidence from inference.")
        appendLine()
        append(question.trim())
    }

    fun frame(providerName: String, role: String, task: String, body: String): String = buildString {
        appendLine("AI Rachabanda assisted prompt")
        appendLine("Provider: $providerName")
        appendLine("Role: $role")
        appendLine("Task: $task")
        appendLine("This is assisted participation. The provider is not connected automatically.")
        appendLine()
        append(body.trim())
    }
}
