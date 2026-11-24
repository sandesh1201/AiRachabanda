package com.airachabanda.app

import com.airachabanda.app.core.model.ParticipationMode
import com.airachabanda.app.core.model.ProviderId
import com.airachabanda.app.orchestration.ImportRules
import com.airachabanda.app.provider.ProviderAccess
import com.airachabanda.app.provider.ProviderAccessMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderAccessTest {
    @Test
    fun freeChatIsUsedWhenApiProtectionIsMissing() {
        val gemini = ProviderAccess.choose(
            modes = setOf(ProviderAccessMode.FREE_AUTOMATED_API, ProviderAccessMode.FREE_ASSISTED_CHAT),
            apiProtectionEstablished = false,
            paidFallbackRequested = false,
        )
        assertEquals(ParticipationMode.ASSISTED, gemini)
        assertEquals(ParticipationMode.ASSISTED, ProviderAccess.active(ProviderId.GEMINI))
        assertEquals("https://gemini.google.com", ProviderAccess.profile(ProviderId.GEMINI).chatUrl)
        assertEquals(ParticipationMode.UNAVAILABLE, ProviderAccess.active(ProviderId.CLOUDFLARE))
        assertEquals(ParticipationMode.UNAVAILABLE, ProviderAccess.active(ProviderId.GROQ))
    }

    @Test
    fun protectedFreeApiIsPreferredAndPaidFallbackIsRejected() {
        val modes = setOf(ProviderAccessMode.FREE_AUTOMATED_API, ProviderAccessMode.FREE_ASSISTED_CHAT)
        assertEquals(ParticipationMode.AUTOMATED, ProviderAccess.choose(modes, apiProtectionEstablished = true, paidFallbackRequested = false))
        assertEquals(ParticipationMode.UNAVAILABLE, ProviderAccess.choose(modes, apiProtectionEstablished = true, paidFallbackRequested = true))
        assertEquals(ParticipationMode.UNAVAILABLE, ProviderAccess.choose(setOf(ProviderAccessMode.FREE_AUTOMATED_API), false, false))
    }

    @Test
    fun assistedImportAcceptsFreeChatAndRejectsApiOnlyProviders() {
        assertTrue(ImportRules.check(ProviderId.DEEPSEEK, "A pasted response", 1).accepted)
        assertTrue(ImportRules.check(ProviderId.MISTRAL, "A pasted response", 1).accepted)
        assertFalse(ImportRules.check(ProviderId.GROQ, "A pasted response", 1).accepted)
        assertFalse(ImportRules.check(ProviderId.CLOUDFLARE, "A pasted response", 1).accepted)
        assertTrue(ProviderAccess.assistedProviders().contains(ProviderId.CLAUDE))
        assertTrue(ProviderAccess.assistedProviders().contains(ProviderId.META_AI))
        assertEquals(ParticipationMode.UNAVAILABLE, ProviderAccess.active(ProviderId.LOCAL))
        assertFalse(ProviderAccess.assistedProviders().contains(ProviderId.LOCAL))
        assertTrue(ProviderAccess.profile(ProviderId.LOCAL).modes.contains(ProviderAccessMode.LOCAL_INFERENCE))
    }
}
