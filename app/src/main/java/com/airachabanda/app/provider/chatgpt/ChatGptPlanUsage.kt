package com.airachabanda.app.provider.chatgpt

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

data class ChatGptTokenBundle(
    val accessToken: String,
    val refreshToken: String?,
    val idToken: String?,
    val issuedClientId: String,
    val scopes: Set<String>,
    val expiresIn: Long?,
)

data class ChatGptCompletion(
    val text: String,
    val modelId: String,
    val completed: Boolean,
    val failure: String?,
)

/**
 * Official open-source Sign in with ChatGPT helpers.
 * Authorization uses a loopback callback on 127.0.0.1 as documented by OpenAI.
 */
object ChatGptPlanUsage {
    @Volatile
    var AUTOMATED_INFERENCE_ENABLED: Boolean = false

    const val AUTHORIZE_ENDPOINT = "https://auth.openai.com/api/accounts/authorize"
    const val TOKEN_ENDPOINT = "https://auth.openai.com/api/accounts/oauth/token"
    const val RESPONSES_ENDPOINT = "https://api.openai.com/v1/responses"
    const val RESOURCE = "https://api.openai.com/v1"
    const val DYNAMIC_CLIENT_ID = "dynamic_agent_client"
    const val APPLICATION_NAME = "AI Rachabanda"
    const val SCOPE = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
    const val CALLBACK_PATH = "/auth/callback"
    const val PLAN_SCOPE = "chatgpt.tokens.use.direct"
    const val DEFAULT_MODEL = "gpt-5-mini"
    const val TEST_PROMPT = "Reply with exactly: AI Rachabanda connected."

    private val random = SecureRandom()

    fun disabledReason(): String =
        "Sign in with ChatGPT needs an official authorization return to this app on 127.0.0.1, plan-usage consent, and a successful Responses API test. Logging into chatgpt.com in a browser does not connect AI Rachabanda."

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

    suspend fun exchangeCode(
        code: String,
        codeVerifier: String,
        redirectUri: String,
        clientId: String,
    ): ChatGptTokenBundle = withContext(Dispatchers.IO) {
        val body = listOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "client_id" to clientId,
            "code_verifier" to codeVerifier,
            "resource" to RESOURCE,
        ).joinToString("&") { (k, v) -> "${urlEncode(k)}=${urlEncode(v)}" }
        val connection = (URL(TOKEN_ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
            connectTimeout = 30_000
            readTimeout = 30_000
        }
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val status = connection.responseCode
        val payload = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (status !in 200..299) error("Token exchange failed with HTTP $status.")
        val json = JSONObject(payload)
        val scopes = json.optString("scope").split(' ', '+').filter { it.isNotBlank() }.toSet()
        if (PLAN_SCOPE !in scopes) {
            error("ChatGPT plan usage was not granted. Automatic replies stay off.")
        }
        ChatGptTokenBundle(
            accessToken = json.getString("access_token"),
            refreshToken = json.optString("refresh_token").ifBlank { null },
            idToken = json.optString("id_token").ifBlank { null },
            issuedClientId = clientId,
            scopes = scopes,
            expiresIn = if (json.has("expires_in")) json.getLong("expires_in") else null,
        )
    }

    suspend fun refresh(
        refreshToken: String,
        clientId: String,
    ): ChatGptTokenBundle = withContext(Dispatchers.IO) {
        val body = listOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "client_id" to clientId,
            "resource" to RESOURCE,
        ).joinToString("&") { (k, v) -> "${urlEncode(k)}=${urlEncode(v)}" }
        val connection = (URL(TOKEN_ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
            connectTimeout = 30_000
            readTimeout = 30_000
        }
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val status = connection.responseCode
        val payload = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (status !in 200..299) error("Token refresh failed with HTTP $status.")
        val json = JSONObject(payload)
        val scopes = json.optString("scope").split(' ', '+').filter { it.isNotBlank() }.toSet()
        ChatGptTokenBundle(
            accessToken = json.getString("access_token"),
            refreshToken = json.optString("refresh_token").ifBlank { refreshToken },
            idToken = json.optString("id_token").ifBlank { null },
            issuedClientId = clientId,
            scopes = scopes,
            expiresIn = if (json.has("expires_in")) json.getLong("expires_in") else null,
        )
    }

    suspend fun complete(accessToken: String, prompt: String, modelId: String = DEFAULT_MODEL): ChatGptCompletion =
        withContext(Dispatchers.IO) {
            val input = JSONArray().put(
                JSONObject().put("role", "user").put("content", prompt),
            )
            val body = JSONObject()
                .put("model", modelId)
                .put("input", input)
                .put("store", false)
                .put("stream", true)
            val connection = (URL(RESPONSES_ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "text/event-stream")
                connectTimeout = 30_000
                readTimeout = 120_000
            }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status == 401 || status == 403) {
                return@withContext ChatGptCompletion("", modelId, false, "ChatGPT rejected the access token.")
            }
            if (status == 429) {
                return@withContext ChatGptCompletion("", modelId, false, "ChatGPT plan usage is limited. No further request was sent.")
            }
            if (status !in 200..299) {
                val err = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                return@withContext ChatGptCompletion("", modelId, false, "ChatGPT returned HTTP $status. ${err.take(120)}")
            }
            parseStream(connection.inputStream.bufferedReader(), modelId)
        }

    private fun parseStream(reader: BufferedReader, modelId: String): ChatGptCompletion {
        val text = StringBuilder()
        var completed = false
        var failure: String? = null
        reader.useLines { lines ->
            lines.forEach { line ->
                if (!line.startsWith("data:")) return@forEach
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty() || payload == "[DONE]") return@forEach
                val json = runCatching { JSONObject(payload) }.getOrNull() ?: return@forEach
                when (json.optString("type")) {
                    "response.output_text.delta" -> text.append(json.optString("delta"))
                    "response.completed" -> completed = true
                    "response.failed" -> {
                        failure = json.optJSONObject("response")?.optJSONObject("error")?.optString("code")
                            ?: "response.failed"
                    }
                }
            }
        }
        return ChatGptCompletion(
            text = text.toString().trim(),
            modelId = modelId,
            completed = completed && text.isNotBlank() && failure == null,
            failure = failure ?: if (!completed || text.isBlank()) {
                "The ChatGPT stream ended without a completed reply."
            } else {
                null
            },
        )
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
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}
