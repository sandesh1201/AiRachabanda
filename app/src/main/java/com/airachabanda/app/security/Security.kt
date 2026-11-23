package com.airachabanda.app.security

import android.util.Log

object RedactedLog {
    private val secretPatterns = listOf(
        Regex("""(?i)bearer\s+[A-Za-z0-9._\-]+"""),
        Regex("""(?i)(api[_-]?key|access[_-]?token|refresh[_-]?token|code_verifier|authorization)\s*[:=]\s*\S+"""),
        Regex("""sk-[A-Za-z0-9_\-]{8,}"""),
    )

    fun redact(message: String): String {
        var cleaned = message
        secretPatterns.forEach { pattern ->
            cleaned = pattern.replace(cleaned, "[redacted]")
        }
        return cleaned
    }

    fun d(tag: String, message: String) {
        Log.d(tag, redact(message))
    }
}

interface CredentialStore {
    suspend fun put(providerId: String, accountKey: String, secret: ByteArray)
    suspend fun get(providerId: String, accountKey: String): ByteArray?
    suspend fun delete(providerId: String, accountKey: String)
    suspend fun deleteAll()
}

/**
 * Keystore-backed design. Milestone 003 does not call [put] from production flows.
 * Secrets, if a later milestone stores them, stay in app-private storage under a
 * key that never leaves the Android Keystore.
 */
class KeystoreCredentialStore(
    private val gateway: KeystoreGateway,
) : CredentialStore {
    override suspend fun put(providerId: String, accountKey: String, secret: ByteArray) {
        gateway.write(alias(providerId, accountKey), secret)
    }

    override suspend fun get(providerId: String, accountKey: String): ByteArray? =
        gateway.read(alias(providerId, accountKey))

    override suspend fun delete(providerId: String, accountKey: String) {
        gateway.delete(alias(providerId, accountKey))
    }

    override suspend fun deleteAll() {
        gateway.deleteAll()
    }

    private fun alias(providerId: String, accountKey: String): String =
        "airachabanda.$providerId.$accountKey"
}

interface KeystoreGateway {
    fun write(alias: String, secret: ByteArray)
    fun read(alias: String): ByteArray?
    fun delete(alias: String)
    fun deleteAll()
}

class AndroidKeystoreGateway : KeystoreGateway {
    override fun write(alias: String, secret: ByteArray) {
        error("Credential storage is not used in this version.")
    }

    override fun read(alias: String): ByteArray? = null

    override fun delete(alias: String) = Unit

    override fun deleteAll() = Unit
}
