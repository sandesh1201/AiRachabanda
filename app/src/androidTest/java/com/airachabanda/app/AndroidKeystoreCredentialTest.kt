package com.airachabanda.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airachabanda.app.security.AndroidKeystoreGateway
import com.airachabanda.app.security.AndroidKeystoreKeySource
import com.airachabanda.app.security.CredentialAccess
import com.airachabanda.app.security.CredentialAccounts
import com.airachabanda.app.security.KeystoreCredentialStore
import com.airachabanda.app.security.RedactedLog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreCredentialTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.filesDir, "instrumentation-credentials")
    private val secret = "synthetic-keystore-secret-004b".toByteArray(Charsets.UTF_8)
    private val other = "synthetic-other-account-004b".toByteArray(Charsets.UTF_8)

    @After
    fun clean() {
        AndroidKeystoreGateway(directory).deleteAll()
        directory.deleteRecursively()
    }

    @Test
    fun keystoreRoundTripReplacementIsolationAndRestart() {
        runBlocking {
        val first = store()
        first.put("OPENROUTER", CredentialAccounts.DEFAULT, secret)
        val created = first.get("OPENROUTER", CredentialAccounts.DEFAULT) as CredentialAccess.Available
        assertTrue(created.secret.contentEquals(secret))
        created.secret.fill(0)

        val replaced = "synthetic-keystore-secret-replaced".toByteArray(Charsets.UTF_8)
        first.put("OPENROUTER", CredentialAccounts.DEFAULT, replaced)
        val afterReplace = first.get("OPENROUTER", CredentialAccounts.DEFAULT) as CredentialAccess.Available
        assertTrue(afterReplace.secret.contentEquals(replaced))
        afterReplace.secret.fill(0)

        first.put("GROQ", "other-account", other)
        first.delete("OPENROUTER", CredentialAccounts.DEFAULT)
        assertTrue(first.get("OPENROUTER", CredentialAccounts.DEFAULT) is CredentialAccess.Missing)
        val isolated = first.get("GROQ", "other-account") as CredentialAccess.Available
        assertTrue(isolated.secret.contentEquals(other))
        isolated.secret.fill(0)

        val restarted = KeystoreCredentialStore(AndroidKeystoreGateway(directory, AndroidKeystoreKeySource()))
        val survived = restarted.get("GROQ", "other-account") as CredentialAccess.Available
        assertTrue(survived.secret.contentEquals(other))
        survived.secret.fill(0)
        directory.walkTopDown().filter { it.isFile }.forEach { file ->
            val bytes = file.readBytes()
            assertFalse(String(bytes, Charsets.ISO_8859_1).contains("synthetic-other-account-004b"))
            assertFalse(String(bytes, Charsets.ISO_8859_1).contains("synthetic-keystore-secret"))
        }
        }
    }

    @Test
    fun corruptedCiphertextAndMissingKeyAreRejected() {
        runBlocking {
        val gateway = AndroidKeystoreGateway(directory, AndroidKeystoreKeySource())
        val alias = "airachabanda.OPENROUTER.default"
        gateway.write(alias, secret)
        val file = directory.listFiles()?.single { it.isFile && !it.name.endsWith(".tmp") }
        check(file != null)
        val original = file.readBytes()
        original[original.lastIndex] = original[original.lastIndex].inc()
        file.writeBytes(original)
        assertTrue(gateway.read(alias) is CredentialAccess.Corrupted)

        file.writeBytes(original.also { it[it.lastIndex] = it[it.lastIndex].dec() })
        AndroidKeystoreKeySource().delete(alias)
        assertEquals(CredentialAccess.KeyInvalidated, gateway.read(alias))
        val logged = RedactedLog.redact("api_key=synthetic-keystore-secret-004b")
        assertFalse(logged.contains("synthetic-keystore-secret-004b"))
        Log.d("KeystoreTest", logged)
        }
    }

    @Test
    fun accountDisconnectionDeletesOnlyThatCredential() {
        runBlocking {
        val credentials = store()
        credentials.put("OPENROUTER", CredentialAccounts.DEFAULT, secret)
        credentials.put("GROQ", CredentialAccounts.DEFAULT, other)
        credentials.delete("OPENROUTER", CredentialAccounts.DEFAULT)
        assertTrue(credentials.get("OPENROUTER", CredentialAccounts.DEFAULT) is CredentialAccess.Missing)
        assertTrue(credentials.get("GROQ", CredentialAccounts.DEFAULT) is CredentialAccess.Available)
        }
    }

    private fun store() = KeystoreCredentialStore(AndroidKeystoreGateway(directory, AndroidKeystoreKeySource()))
}
