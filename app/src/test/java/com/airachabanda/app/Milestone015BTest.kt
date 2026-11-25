package com.airachabanda.app

import com.airachabanda.app.core.model.AccountChoice
import com.airachabanda.app.core.model.BillingEligibility
import com.airachabanda.app.core.model.IntegrationMethod
import com.airachabanda.app.core.model.ParticipationMode
import com.airachabanda.app.core.model.ProviderConfig
import com.airachabanda.app.core.model.ProviderId
import com.airachabanda.app.provider.FreeModelSelection
import com.airachabanda.app.provider.ProviderSetupCatalog
import com.airachabanda.app.provider.cloudflare.CloudflareAccountDiscovery
import com.airachabanda.app.ui.ConnectionPresentation
import com.airachabanda.app.ui.ProviderPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Milestone015BTest {
    @Test
    fun simpleStatesStayBehindTheExistingGates() {
        val chat = config(ProviderId.CLAUDE, BillingEligibility.UNKNOWN, AccountChoice.NOT_CONFIGURED)
        assertEquals(ConnectionPresentation.UNAVAILABLE, ConnectionPresentation.state(chat, enabled = true))
        val declared = chat.copy(accountChoice = AccountChoice.FREE_ACCOUNT, participationMode = ParticipationMode.UNAVAILABLE)
        assertEquals("Unavailable", ProviderPresentation.status(declared, credentialStored = false))
        assertEquals(ConnectionPresentation.UNAVAILABLE, ConnectionPresentation.state(chat, enabled = false))

        val stored = config(ProviderId.GROQ, BillingEligibility.UNKNOWN, AccountChoice.NOT_CONFIGURED)
        assertEquals(ConnectionPresentation.RECONNECT_REQUIRED, ConnectionPresentation.state(stored, credentialStored = true))
        assertEquals(ConnectionPresentation.NOT_CONNECTED, ConnectionPresentation.state(stored, credentialStored = false))

        val ready = config(ProviderId.GROQ, BillingEligibility.VERIFIED_FREE, AccountChoice.FREE_ACCOUNT, automated = true)
        assertEquals(
            ConnectionPresentation.CONNECTED,
            ConnectionPresentation.state(ready, enabled = true, credentialStored = true, connectionTested = true),
        )
        assertEquals(ConnectionPresentation.RECONNECT_REQUIRED, ConnectionPresentation.state(ready, enabled = true, credentialStored = true))
        assertEquals(
            ConnectionPresentation.RECONNECT_REQUIRED,
            ConnectionPresentation.state(ready.copy(automatedInferenceEnabled = false), credentialStored = true),
        )

        val blocked = config(ProviderId.OPENROUTER, BillingEligibility.BLOCKED, AccountChoice.FREE_ACCOUNT)
        assertEquals(ConnectionPresentation.FREE_LIMIT_REACHED, ConnectionPresentation.state(blocked, credentialStored = true))
        val exhausted = config(ProviderId.GEMINI, BillingEligibility.VERIFIED_FREE, AccountChoice.FREE_ACCOUNT, automated = true)
        assertEquals(
            ConnectionPresentation.FREE_LIMIT_REACHED,
            ConnectionPresentation.state(exhausted, quotaExhausted = true, credentialStored = true),
        )
        val local = config(ProviderId.LOCAL, BillingEligibility.UNKNOWN, AccountChoice.NOT_CONFIGURED)
        assertEquals(ConnectionPresentation.UNAVAILABLE, ConnectionPresentation.state(local))
        assertFalse(ConnectionPresentation.explanation(ConnectionPresentation.CONNECTED, ProviderId.GEMINI).contains("Ready for automated"))
    }

    @Test
    fun connectActionsStayAKeyOrOfficialSignIn() {
        val gemini = ProviderSetupCatalog.spec(ProviderId.GEMINI)
        assertTrue(gemini.links.any { it.label.contains("API key", ignoreCase = true) && it.url.startsWith("https://") })
        assertTrue(gemini.links.any { it.url.startsWith("https://gemini.google.com") })
        assertEquals(listOf("api-key"), gemini.fields.map { it.key })
        val cloudflare = ProviderSetupCatalog.spec(ProviderId.CLOUDFLARE)
        assertTrue(cloudflare.fields.any { it.key == "api-token" })
        val chat = ProviderSetupCatalog.spec(ProviderId.CHATGPT)
        assertTrue(chat.fields.isEmpty())
        assertTrue(chat.links.any { it.label.contains("sign-in", ignoreCase = true) || it.label.contains("ChatGPT", ignoreCase = true) })
    }

    @Test
    fun discoveryAndModelSelectionRejectUnknownPrices() {
        val one = CloudflareAccountDiscovery.read("""{"success":true,"result":[{"id":"0123456789abcdef0123456789abcdef","name":"Fixture"}]}""")
        assertEquals("0123456789abcdef0123456789abcdef", one.accountId)
        val many = CloudflareAccountDiscovery.read(
            """{"success":true,"result":[{"id":"0123456789abcdef0123456789abcdef"},{"id":"abcdef0123456789abcdef0123456789"}]}""",
        )
        assertNull(many.accountId)
        assertTrue(many.reason.contains("more than one"))
        assertEquals("gemini-3.5-flash-lite", FreeModelSelection.choose(ProviderId.GEMINI, emptyList()))
        assertNull(FreeModelSelection.choose(ProviderId.GEMINI, emptyList(), "gemini-3.8-flash"))
        assertNull(FreeModelSelection.choose(ProviderId.OPENROUTER, listOf("unknown/free")))
        assertFalse(FreeModelSelection.eligible(ProviderId.CLOUDFLARE, "@cf/moonshotai/kimi-k2.6"))
        assertEquals(ConnectionPresentation.CONNECTING, ConnectionPresentation.state(config(ProviderId.GROQ, BillingEligibility.UNKNOWN, AccountChoice.NOT_CONFIGURED), connecting = true))
    }

    private fun config(
        id: ProviderId,
        billing: BillingEligibility,
        account: AccountChoice,
        automated: Boolean = false,
    ) = ProviderConfig(
        providerId = id,
        accountChoice = account,
        declaredPlanId = null,
        billingEligibility = billing,
        participationMode = if (automated) ParticipationMode.AUTOMATED else ParticipationMode.UNAVAILABLE,
        integrationMethod = IntegrationMethod.NONE,
        automatedInferenceEnabled = automated,
        updatedAtEpochMs = 0,
    )
}
