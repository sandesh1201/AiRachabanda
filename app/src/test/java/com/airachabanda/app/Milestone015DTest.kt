package com.airachabanda.app

import com.airachabanda.app.core.model.ParticipationMode
import com.airachabanda.app.core.model.ProviderId
import com.airachabanda.app.provider.ProviderAccess
import com.airachabanda.app.provider.ProviderAccessMode
import com.airachabanda.app.provider.ProviderSetupCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Milestone015DTest {
    @Test
    fun offlineAiIsRemovedFromAccessProfiles() {
        assertTrue(ProviderAccess.profile(ProviderId.LOCAL).modes.contains(ProviderAccessMode.UNAVAILABLE))
        assertEquals(ParticipationMode.UNAVAILABLE, ProviderAccess.active(ProviderId.LOCAL))
        assertTrue(ProviderAccess.profile(ProviderId.LOCAL).note.contains("Offline AI was removed"))
        assertTrue(ProviderSetupCatalog.spec(ProviderId.LOCAL).methods.isEmpty())
        assertTrue(ProviderSetupCatalog.spec(ProviderId.LOCAL).automatedBlocker.contains("unavailable", ignoreCase = true))
    }
}
