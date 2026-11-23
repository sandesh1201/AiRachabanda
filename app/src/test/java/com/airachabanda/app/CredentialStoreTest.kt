package com.airachabanda.app

import com.airachabanda.app.security.AndroidKeystoreGateway
import com.airachabanda.app.security.CredentialAccess
import com.airachabanda.app.security.KeystoreCredentialStore
import com.airachabanda.app.security.SecretKeySource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class CredentialStoreTest {
    @Test
    fun encryptsReplacesDeletesAndKeepsProvidersApart() = runBlocking {
        val directory = tempDirectory()
        val keys = MemoryKeySource()
        val store = store(directory, keys)
        val first = "synthetic-groq-key".toByteArray()
        val replacement = "synthetic-groq-key-rotated".toByteArray()
        val other = "synthetic-gemini-key".toByteArray()

        store.put("GROQ", "default", first)
        val stored = onlyFile(directory)
        assertFalse(String(stored.readBytes(), Charsets.ISO_8859_1).contains("synthetic-groq-key"))
        val before = stored.readBytes()
        assertEquals(first.toList(), (store.get("GROQ", "default") as CredentialAccess.Available).secret.toList())

        store.put("GROQ", "default", replacement)
        val after = onlyFile(directory).readBytes()
        assertFalse(before.contentEquals(after))
        assertEquals(replacement.toList(), (store.get("GROQ", "default") as CredentialAccess.Available).secret.toList())

        store.put("GEMINI", "default", other)
        store.delete("GROQ", "default")
        assertTrue(store.get("GROQ", "default") is CredentialAccess.Missing)
        assertEquals(other.toList(), (store.get("GEMINI", "default") as CredentialAccess.Available).secret.toList())

        val restarted = store(directory, keys)
        assertEquals(other.toList(), (restarted.get("GEMINI", "default") as CredentialAccess.Available).secret.toList())
        restarted.deleteAll()
        assertTrue(restarted.get("GEMINI", "default") is CredentialAccess.Missing)
    }

    @Test
    fun tamperedCiphertextAndMissingKeyFailClosed() = runBlocking {
        val directory = tempDirectory()
        val keys = MemoryKeySource()
        val store = store(directory, keys)
        store.put("GROQ", "default", "synthetic-secret".toByteArray())
        val file = onlyFile(directory)
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes[bytes.lastIndex] + 1).toByte()
        file.writeBytes(bytes)
        assertTrue(store.get("GROQ", "default") is CredentialAccess.Corrupted)

        val freshDirectory = tempDirectory()
        val freshKeys = MemoryKeySource()
        val freshStore = store(freshDirectory, freshKeys)
        freshStore.put("GROQ", "default", "synthetic-secret".toByteArray())
        freshKeys.drop("airachabanda.GROQ.default")
        assertTrue(freshStore.get("GROQ", "default") is CredentialAccess.KeyInvalidated)
    }

    @Test
    fun concurrentReplacementsStayReadable() = runBlocking {
        val directory = tempDirectory()
        val store = store(directory, MemoryKeySource())
        (1..8).map { index ->
            async(Dispatchers.Default) {
                store.put("GROQ", "default", "synthetic-$index".toByteArray())
            }
        }.awaitAll()
        val access = store.get("GROQ", "default")
        assertTrue(access is CredentialAccess.Available)
        val text = String((access as CredentialAccess.Available).secret)
        assertTrue(text.startsWith("synthetic-"))
    }

    private fun store(directory: File, keys: SecretKeySource) =
        KeystoreCredentialStore(AndroidKeystoreGateway(directory, keys))

    private fun tempDirectory(): File =
        File(System.getProperty("java.io.tmpdir"), "airachabanda-cred-${System.nanoTime()}").apply { mkdirs() }

    private fun onlyFile(directory: File): File =
        directory.listFiles().orEmpty().single { it.isFile && !it.name.endsWith(".tmp") }
}

class MemoryKeySource : SecretKeySource {
    private val keys = ConcurrentHashMap<String, SecretKey>()

    override fun get(alias: String): SecretKey? = keys[alias]

    override fun getOrCreate(alias: String): SecretKey = keys.getOrPut(alias) {
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }

    override fun delete(alias: String) {
        keys.remove(alias)
    }

    override fun deleteAll() {
        keys.clear()
    }

    fun drop(alias: String) {
        keys.remove(alias)
    }
}
