package com.airachabanda.app

import com.airachabanda.app.security.AndroidKeystoreKeySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KeystoreSpecTest {
    @Test
    fun aesKeyDoesNotRequireBiometricUnlock() {
        val spec = AndroidKeystoreKeySource.specification("airachabanda.GROQ.default")
        assertEquals(256, spec.keySize)
        assertFalse(spec.isUserAuthenticationRequired)
        assertFalse(spec.isUnlockedDeviceRequired)
    }
}