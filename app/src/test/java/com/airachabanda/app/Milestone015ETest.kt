package com.airachabanda.app

import com.airachabanda.app.core.model.ProviderId
import com.airachabanda.app.provider.ProviderAccess
import com.airachabanda.app.provider.ProviderAccessMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Milestone015ETest {
    @Test
    fun litertLmRuntimeIsNoLongerPackagedForLocalInference() {
        assertTrue(ProviderAccess.profile(ProviderId.LOCAL).modes.contains(ProviderAccessMode.UNAVAILABLE))
        assertFalse(ProviderAccess.profile(ProviderId.LOCAL).modes.contains(ProviderAccessMode.LOCAL_INFERENCE))
        assertTrue(ProviderAccess.profile(ProviderId.LOCAL).note.contains("Offline AI was removed"))
    }
}
