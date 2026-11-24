package com.airachabanda.app

import com.airachabanda.app.core.model.ProviderId
import com.airachabanda.app.provider.ProviderSetupCatalog
import com.airachabanda.app.provider.SetupMethod
import com.airachabanda.app.security.RedactedLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Milestone015ATest {
    @Test
    fun everyProviderHasASupportedMethodAndOfficialLinksStayHttps() {
        ProviderId.entries.forEach { id ->
            val spec = ProviderSetupCatalog.spec(id)
            assertTrue(spec.methods.isNotEmpty())
            assertFalse(spec.methods.map { it.label }.any { it.contains("Paid API", ignoreCase = true) })
            spec.links.forEach { link ->
                assertTrue(link.url.startsWith("https://"))
            }
        }
        val chat = ProviderSetupCatalog.spec(ProviderId.CHATGPT)
        assertEquals(listOf(SetupMethod.FREE_ASSISTED_CHAT, SetupMethod.SUBSCRIPTION_ASSISTED), chat.methods)
        assertTrue(chat.fields.isEmpty())
        val gemini = ProviderSetupCatalog.spec(ProviderId.GEMINI)
        assertTrue(SetupMethod.FREE_AUTOMATED_API in gemini.methods)
        assertTrue(gemini.requirements.contains("billingEnabled"))
        assertFalse(gemini.requirements.contains("E0:6F"))
        assertTrue(ProviderSetupCatalog.spec(ProviderId.GROQ).automatedBlocker.contains("Free plan"))
    }

    @Test
    fun incompleteApiSetupStaysBlockedAndAssistedChatDoesNotAuthorizeAnApi() {
        val groq = ProviderSetupCatalog.spec(ProviderId.GROQ)
        val blocked = ProviderSetupCatalog.progress(groq, SetupMethod.FREE_AUTOMATED_API, credentialStored = true, billingVerified = false, enabled = true)
        assertTrue(blocked.first { it.name == "Verify zero-cost eligibility" }.state.contains("stays off"))
        assertTrue(blocked.first { it.name == "Council participation" }.state.contains("stays off"))
        val claude = ProviderSetupCatalog.spec(ProviderId.CLAUDE)
        val assisted = ProviderSetupCatalog.progress(claude, SetupMethod.SUBSCRIPTION_ASSISTED, false, false, true)
        assertTrue(assisted.first { it.name == "Credentials or authorization" }.state.contains("cannot confirm"))
        assertTrue(assisted.first { it.name == "Council participation" }.state.contains("Enabled"))
        assertFalse(RedactedLog.redact("api_key=sk-testsecretvalue").contains("sk-test"))
    }
}
