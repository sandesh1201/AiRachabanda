package com.airachabanda.app.provider.groq

import com.airachabanda.app.provider.connect.RuntimeEligibility
import com.airachabanda.app.provider.openrouter.HttpExchange
import com.airachabanda.app.provider.openrouter.HttpTransport
import com.airachabanda.app.security.RedactedLog

class GroqConnectCheck(
    private val transport: HttpTransport,
) {
    suspend fun run(apiKey: String, consent: Boolean): RuntimeEligibility {
        if (!consent) return RuntimeEligibility(detail = "Groq was not contacted. Connect was not confirmed.")
        if (apiKey.isBlank()) return RuntimeEligibility(detail = "No Groq API key is available. No request was sent.")
        RedactedLog.d("Groq", "Prepared GET https://api.groq.com/openai/v1/models")
        val models = transport.exchange(
            HttpExchange(
                method = "GET",
                url = "https://api.groq.com/openai/v1/models",
                headers = mapOf("Authorization" to "Bearer $apiKey", "Accept" to "application/json"),
                body = null,
            ),
        )
        if (models.status == 401 || models.status == 403) {
            return RuntimeEligibility(credentialStored = true, detail = "Groq rejected the key. The key was not logged. The plan was not read.")
        }
        if (models.status !in 200..299) {
            return RuntimeEligibility(credentialStored = true, detail = "The Groq model list returned HTTP ${models.status}. No completion was sent.")
        }
        return RuntimeEligibility(
            credentialStored = true,
            authenticationVerified = true,
            detail = "The key was accepted and the model list was read. Groq does not return the Free or Developer plan on this response, so automated use stays off.",
        )
    }
}
