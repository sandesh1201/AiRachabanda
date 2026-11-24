package com.airachabanda.app

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airachabanda.app.security.AndroidKeystoreGateway
import com.airachabanda.app.security.AndroidKeystoreKeySource
import com.airachabanda.app.security.CredentialAccess
import com.airachabanda.app.security.KeystoreCredentialStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * The AndroidX test orchestrator starts a new instrumentation process for each
 * method. The ciphertext file and the AndroidKeyStore alias must survive that
 * process. These methods use a synthetic secret and delete it at the end.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class KeystoreProcessRestartTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.filesDir, "process-restart-credentials")
    private val marker = File(context.filesDir, "keystore-process-marker.txt")
    private val account = "process-restart"
    private val secret = "synthetic-process-restart-005a".toByteArray(Charsets.UTF_8)

    @Test
    fun a_storeSyntheticCredential() {
        runBlocking {
            store().put("OPENROUTER", account, secret)
            marker.writeText(Process.myPid().toString())
        }
    }

    @Test
    fun b_readCredentialAfterNewProcess() {
        runBlocking {
            val previous = marker.readText().trim().toInt()
            assertTrue("The credential check did not run in a new process.", Process.myPid() != previous)
            val access = store().get("OPENROUTER", account) as CredentialAccess.Available
            assertTrue(access.secret.contentEquals(secret))
            access.secret.fill(0)
            directory.walkTopDown().filter { it.isFile }.forEach { file ->
                assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("synthetic-process-restart-005a"))
            }
        }
    }

    @Test
    fun c_disconnectAndRemoveSyntheticCredential() {
        runBlocking {
            val credentials = store()
            credentials.delete("OPENROUTER", account)
            assertTrue(credentials.get("OPENROUTER", account) is CredentialAccess.Missing)
            marker.delete()
            directory.deleteRecursively()
        }
    }

    private fun store() = KeystoreCredentialStore(AndroidKeystoreGateway(directory, AndroidKeystoreKeySource()))
}
