package com.airachabanda.app.provider.chatgpt

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Starts a 127.0.0.1 loopback listener first so the authorization URL can include the real port.
 */
class ChatGptConnectSession(
    private val hostId: String = ChatGptPlanUsage.newHostId(),
    private val issuedClientId: String? = null,
) : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val request: ChatGptAuthorizationRequest = ChatGptPlanUsage.prepareAuthorization(
        loopbackPort = server.localPort,
        hostId = hostId,
        issuedClientId = issuedClientId,
    )

    suspend fun awaitAuthorization(timeoutMs: Long = 180_000L): Map<String, String> = withContext(Dispatchers.IO) {
        withTimeout(timeoutMs) {
            server.soTimeout = timeoutMs.toInt().coerceAtMost(Int.MAX_VALUE)
            val client = server.accept()
            try {
                readCallback(client)
            } finally {
                runCatching { client.close() }
            }
        }
    }

    override fun close() {
        runCatching { server.close() }
    }

    private fun readCallback(client: Socket): Map<String, String> {
        client.soTimeout = 15_000
        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
        val requestLine = reader.readLine().orEmpty()
        while (true) {
            val header = reader.readLine() ?: break
            if (header.isBlank()) break
        }
        val path = requestLine.split(' ').getOrNull(1).orEmpty()
        val query = path.substringAfter('?', "")
        val params = query.split('&').mapNotNull { part ->
            if (part.isBlank()) return@mapNotNull null
            val key = URLDecoder.decode(part.substringBefore('='), Charsets.UTF_8.name())
            val value = URLDecoder.decode(part.substringAfter('=', ""), Charsets.UTF_8.name())
            key to value
        }.toMap()
        val body = "AI Rachabanda received the authorization return. You can close this tab and return to the app."
        val response = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n")
            append("Connection: close\r\n\r\n")
            append(body)
        }
        client.getOutputStream().use { it.write(response.toByteArray(Charsets.UTF_8)) }
        return params
    }
}
