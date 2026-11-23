package com.airachabanda.app.provider.chatgpt

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

data class ChatGptAuthorizationRequest(
    val authorizationUrl: String,
    val redirectUri: String,
    val state: String,
    val nonce: String,
    val codeVerifier: String,
    val codeChallenge: String,
    val hostId: String,
    val issuedClientId: String?,
)

/**
 * Builds the documented open-source Sign in with ChatGPT authorization request.
 * It does not open a browser, exchange a code, or call the Responses API.
 */
object ChatGptPlanUsage {
    const val AUTOMATED_INFERENCE_ENABLED = false
    const val AUTHORIZE_ENDPOINT = "https://auth.openai.com/api/accounts/authorize"
    const val TOKEN_ENDPOINT = "https://auth.openai.com/api/accounts/oauth/token"
    const val RESPONSES_ENDPOINT = "https://api.openai.com/v1/responses"
    const val RESOURCE = "https://api.openai.com/v1"
    const val DYNAMIC_CLIENT_ID = "dynamic_agent_client"
    const val APPLICATION_NAME = "AI Rachabanda"
    const val SCOPE = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
    const val CALLBACK_PATH = "/auth/callback"

    private val random = SecureRandom()

    fun disabledReason(): String =
        "Automated ChatGPT inference is disabled until the application can guarantee that purchased credits are not consumed."

    fun prepareAuthorization(
        loopbackPort: Int,
        hostId: String = newHostId(),
        issuedClientId: String? = null,
    ): ChatGptAuthorizationRequest {
        require(loopbackPort in 1..65535)
        require(hostId.startsWith("urn:uuid:") || hostId.startsWith("urn:ietf:params:oauth:jwk-thumbprint:"))
        val verifier = randomUrlToken(32)
        val challenge = s256Challenge(verifier)
        val state = randomUrlToken(24)
        val nonce = randomUrlToken(24)
        val redirect = "http://127.0.0.1:$loopbackPort$CALLBACK_PATH"
        val clientId = issuedClientId ?: DYNAMIC_CLIENT_ID
        val params = linkedMapOf(
            "client_id" to clientId,
            "response_type" to "code",
            "redirect_uri" to redirect,
            "scope" to SCOPE,
            "resource" to RESOURCE,
            "state" to state,
            "nonce" to nonce,
            "code_challenge_method" to "S256",
            "code_challenge" to challenge,
            "ext_agent_host_id" to hostId,
        )
        if (issuedClientId == null) {
            params["agent_name_hint"] = APPLICATION_NAME
        }
        val query = params.entries.joinToString("&") { (key, value) ->
            "${urlEncode(key)}=${urlEncode(value)}"
        }
        return ChatGptAuthorizationRequest(
            authorizationUrl = "$AUTHORIZE_ENDPOINT?$query",
            redirectUri = redirect,
            state = state,
            nonce = nonce,
            codeVerifier = verifier,
            codeChallenge = challenge,
            hostId = hostId,
            issuedClientId = issuedClientId,
        )
    }

    fun newHostId(): String = "urn:uuid:${UUID.randomUUID()}"

    fun assertLiveExchangeDisabled(): Nothing {
        error(disabledReason())
    }

    private fun s256Challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun randomUrlToken(size: Int): String {
        val bytes = ByteArray(size)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}
