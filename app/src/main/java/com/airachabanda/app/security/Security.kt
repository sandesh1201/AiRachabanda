package com.airachabanda.app.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException

object RedactedLog {
    private val secretPatterns = listOf(
        Regex("""(?i)bearer\s+[A-Za-z0-9._\-]+"""),
        Regex("""(?i)(api[_-]?key|access[_-]?token|refresh[_-]?token|code_verifier|authorization)\s*[:=]\s*\S+"""),
        Regex("""(?i)keyString=[^&\s]+"""),
        Regex("""AIza[0-9A-Za-z_\-]{20,}"""),
        Regex("""ya29\.[0-9A-Za-z_\-]+"""),
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

object CredentialAccounts {
    const val DEFAULT = "default"
}

sealed class CredentialAccess {
    data class Available(val secret: ByteArray) : CredentialAccess()
    data object Missing : CredentialAccess()
    data object KeyInvalidated : CredentialAccess()
    data object Corrupted : CredentialAccess()
}

class CredentialKeyException(message: String) : Exception(message)

interface CredentialStore {
    suspend fun put(providerId: String, accountKey: String, secret: ByteArray)
    suspend fun get(providerId: String, accountKey: String): CredentialAccess
    suspend fun delete(providerId: String, accountKey: String)
    suspend fun deleteAll()
}

/**
 * Encrypts each provider account with its own Android Keystore AES-256-GCM key.
 * Ciphertext lives in the app's private files directory. Room and DataStore never
 * receive the credential. Biometric unlock is not required, so a later background
 * research task can still open a stored credential after the user has opted in.
 */
class KeystoreCredentialStore(
    private val gateway: KeystoreGateway,
) : CredentialStore {
    override suspend fun put(providerId: String, accountKey: String, secret: ByteArray) {
        gateway.write(alias(providerId, accountKey), secret)
    }

    override suspend fun get(providerId: String, accountKey: String): CredentialAccess =
        gateway.read(alias(providerId, accountKey))

    override suspend fun delete(providerId: String, accountKey: String) {
        gateway.delete(alias(providerId, accountKey))
    }

    override suspend fun deleteAll() {
        gateway.deleteAll()
    }

    private fun alias(providerId: String, accountKey: String): String {
        require(providerId.matches(SAFE_PART)) { "Provider id is not a valid credential alias." }
        require(accountKey.matches(SAFE_PART)) { "Account key is not a valid credential alias." }
        return "airachabanda.$providerId.$accountKey"
    }

    private companion object {
        val SAFE_PART = Regex("[A-Za-z0-9._-]{1,64}")
    }
}

interface KeystoreGateway {
    fun write(alias: String, secret: ByteArray)
    fun read(alias: String): CredentialAccess
    fun delete(alias: String)
    fun deleteAll()
}

interface SecretKeySource {
    fun get(alias: String): SecretKey?
    fun getOrCreate(alias: String): SecretKey
    fun delete(alias: String)
    fun deleteAll()
}

class AndroidKeystoreKeySource : SecretKeySource {
    override fun get(alias: String): SecretKey? {
        val store = load()
        if (!store.containsAlias(alias)) return null
        return try {
            store.getKey(alias, null) as? SecretKey
        } catch (invalid: UnrecoverableKeyException) {
            store.deleteEntry(alias)
            null
        } catch (invalid: KeyPermanentlyInvalidatedException) {
            store.deleteEntry(alias)
            null
        }
    }

    override fun getOrCreate(alias: String): SecretKey {
        get(alias)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(specification(alias))
        return generator.generateKey()
    }

    override fun delete(alias: String) {
        val store = load()
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }

    override fun deleteAll() {
        val store = load()
        store.aliases().toList()
            .filter { it.startsWith(ALIAS_PREFIX) }
            .forEach { store.deleteEntry(it) }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS_PREFIX = "airachabanda."

        fun specification(alias: String): KeyGenParameterSpec {
            val builder = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                builder.setUnlockedDeviceRequired(false)
            }
            return builder.build()
        }
    }

    private fun load(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
}

class AndroidKeystoreGateway(
    private val directory: File,
    private val keys: SecretKeySource = AndroidKeystoreKeySource(),
) : KeystoreGateway {
    private val lock = Any()

    init {
        directory.mkdirs()
    }

    override fun write(alias: String, secret: ByteArray) {
        synchronized(lock) {
            val key = try {
                keys.getOrCreate(alias)
            } catch (invalid: GeneralSecurityException) {
                throw CredentialKeyException("The encryption key is unavailable. The credential was not stored.")
            }
            val payload = try {
                encrypt(key, secret)
            } catch (invalid: KeyPermanentlyInvalidatedException) {
                keys.delete(alias)
                throw CredentialKeyException("The encryption key is unavailable. The credential was not stored.")
            } catch (invalid: GeneralSecurityException) {
                throw CredentialKeyException("The credential could not be encrypted. It was not stored.")
            }
            writeAtomically(fileFor(alias), payload)
        }
    }

    override fun read(alias: String): CredentialAccess {
        synchronized(lock) {
            val file = fileFor(alias)
            if (!file.exists()) return CredentialAccess.Missing
            val key = try {
                keys.get(alias)
            } catch (invalid: GeneralSecurityException) {
                keys.delete(alias)
                null
            }
            if (key == null) return CredentialAccess.KeyInvalidated
            val payload = try {
                file.readBytes()
            } catch (io: java.io.IOException) {
                return CredentialAccess.Corrupted
            }
            return try {
                CredentialAccess.Available(decrypt(key, payload))
            } catch (invalid: KeyPermanentlyInvalidatedException) {
                keys.delete(alias)
                CredentialAccess.KeyInvalidated
            } catch (invalid: AEADBadTagException) {
                CredentialAccess.Corrupted
            } catch (invalid: GeneralSecurityException) {
                CredentialAccess.Corrupted
            } catch (invalid: IllegalArgumentException) {
                CredentialAccess.Corrupted
            }
        }
    }

    override fun delete(alias: String) {
        synchronized(lock) {
            fileFor(alias).delete()
            keys.delete(alias)
        }
    }

    override fun deleteAll() {
        synchronized(lock) {
            directory.listFiles()?.forEach { it.delete() }
            keys.deleteAll()
        }
    }

    private fun encrypt(key: SecretKey, secret: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        check(iv.size == GCM_IV_BYTES) { "Encryption did not produce a GCM initialization vector." }
        val ciphertext = cipher.doFinal(secret)
        val payload = ByteArray(MAGIC.size + iv.size + ciphertext.size)
        MAGIC.copyInto(payload)
        iv.copyInto(payload, MAGIC.size)
        ciphertext.copyInto(payload, MAGIC.size + iv.size)
        return payload
    }

    private fun decrypt(key: SecretKey, payload: ByteArray): ByteArray {
        if (payload.size < MAGIC.size + GCM_IV_BYTES + GCM_TAG_BYTES) {
            throw IllegalArgumentException("Credential payload is too short.")
        }
        if (!payload.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw IllegalArgumentException("Credential payload is not a recognized envelope.")
        }
        val iv = payload.copyOfRange(MAGIC.size, MAGIC.size + GCM_IV_BYTES)
        val ciphertext = payload.copyOfRange(MAGIC.size + GCM_IV_BYTES, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun writeAtomically(destination: File, payload: ByteArray) {
        val temporary = File(directory, destination.name + ".tmp")
        FileOutputStream(temporary).use { stream ->
            stream.write(payload)
            stream.fd.sync()
        }
        if (destination.exists() && !destination.delete()) {
            temporary.delete()
            throw CredentialKeyException("The previous credential could not be replaced.")
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            throw CredentialKeyException("The credential could not be stored.")
        }
    }

    private fun fileFor(alias: String): File {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(alias.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
        return File(directory, name)
    }

    private companion object {
        val MAGIC = byteArrayOf('A'.code.toByte(), 'R'.code.toByte(), 'C'.code.toByte(), 1)
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = 16
    }
}
