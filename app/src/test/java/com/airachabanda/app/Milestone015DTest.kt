package com.airachabanda.app

import com.airachabanda.app.core.model.ParticipantRole
import com.airachabanda.app.core.model.ProviderId
import com.airachabanda.app.orchestration.ReviewClassification
import com.airachabanda.app.provider.ProviderAccess
import com.airachabanda.app.provider.local.ondevice.DownloadState
import com.airachabanda.app.provider.local.ondevice.FixtureOnDeviceEngine
import com.airachabanda.app.provider.local.ondevice.OnDeviceDispatch
import com.airachabanda.app.provider.local.ondevice.OnDeviceModelCatalog
import com.airachabanda.app.provider.local.ondevice.OnDeviceModelManager
import com.airachabanda.app.provider.local.ondevice.OnDeviceParticipation
import com.airachabanda.app.provider.local.ondevice.OnDeviceRuntime
import com.airachabanda.app.provider.local.ondevice.OnDeviceScheduler
import com.airachabanda.app.provider.local.ondevice.ThermalLevel
import com.airachabanda.app.provider.local.ondevice.UnavailableOnDeviceEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Milestone015DTest {
    private val gemma = OnDeviceModelCatalog.GEMMA
    private val qwen = OnDeviceModelCatalog.QWEN
    private val plenty = 8L * 1024 * 1024 * 1024

    @Test
    fun twoFamiliesAreRegisteredAndTheRuntimeIsNotPackaged() {
        assertEquals("Gemma", gemma.family)
        assertEquals("Qwen", qwen.family)
        assertTrue(OnDeviceModelCatalog.compatible(gemma))
        assertTrue(OnDeviceModelCatalog.compatible(qwen))
        assertEquals(OnDeviceRuntime.Candidate.LITERT_LM, OnDeviceRuntime.selected.candidate)
        assertEquals(1, OnDeviceRuntime.assessments.count { it.selected })
        assertFalse(OnDeviceRuntime.PACKAGED)
        assertFalse(OnDeviceRuntime.OWNER_DOWNLOAD_APPROVED)
        assertFalse(OnDeviceRuntime.NETWORK_PERMITTED)
        assertTrue(ProviderAccess.profile(ProviderId.LOCAL).modes.contains(com.airachabanda.app.provider.ProviderAccessMode.LOCAL_INFERENCE))
    }

    @Test
    fun downloadConsentStorageAndIntegrityStayClosed() {
        val manager = OnDeviceModelManager()
        val quiet = manager.download(gemma.id, consent = false, availableBytes = plenty)
        assertEquals(DownloadState.NOT_INSTALLED, quiet.state)
        assertEquals(0, manager.networkRequests)
        val short = manager.download(qwen.id, consent = true, availableBytes = 1024)
        assertEquals(DownloadState.BLOCKED, short.state)
        assertTrue(short.detail.contains("1598 MB"))
        val blocked = manager.download(gemma.id, consent = true, availableBytes = plenty)
        assertEquals(DownloadState.BLOCKED, blocked.state)
        assertEquals(0L, blocked.bytesTransferred)
        assertTrue(blocked.detail.contains("Nothing was downloaded"))
        val cancelled = manager.cancel(gemma.id)
        assertEquals(DownloadState.CANCELLED, cancelled.state)
        assertEquals(DownloadState.NOT_INSTALLED, manager.delete(gemma.id).state)
        assertFalse(manager.enable(gemma.id).enabled)
        val bytes = "fixture-model".toByteArray()
        val digest = OnDeviceModelManager.sha256(bytes)
        assertFalse(manager.verifyIntegrity(bytes, "").matches)
        assertFalse(manager.verifyIntegrity(bytes, "00").matches)
        val matched = manager.verifyIntegrity(bytes, digest)
        assertTrue(matched.matches)
        assertTrue(matched.detail.contains("did not install"))
    }

    @Test
    fun oneModelIsResidentAndBoundsCanBeCancelled() {
        val scheduler = OnDeviceScheduler()
        val ram = OnDeviceRuntime.TARGET_RAM_BYTES
        val first = scheduler.schedule(gemma.id, ram, ThermalLevel.NORMAL)
        assertTrue(first.loaded)
        val second = scheduler.schedule(qwen.id, ram, ThermalLevel.NORMAL)
        assertFalse(second.loaded)
        assertEquals(gemma.id, second.residentModelId)
        assertTrue(second.detail.contains("Unload"))
        scheduler.unload(gemma.id)
        assertTrue(scheduler.schedule(qwen.id, ram, ThermalLevel.NORMAL).loaded)
        val tight = gemma.publishedPeakRamBytes
        assertFalse(scheduler.schedule(gemma.id, tight, ThermalLevel.NORMAL).loaded)
        assertFalse(scheduler.schedule(gemma.id, ram, ThermalLevel.SEVERE).loaded)
        assertFalse(scheduler.bounds(gemma.id, gemma.contextTokens + 1, 32).accepted)
        assertFalse(scheduler.bounds(gemma.id, 128, OnDeviceRuntime.MAX_OUTPUT_TOKENS + 1).accepted)
        assertTrue(scheduler.bounds(gemma.id, 128, 32).accepted)
        val firstPermit = scheduler.begin(gemma.id)
        val newer = scheduler.begin(gemma.id)
        scheduler.cancel(newer.generationId)
        assertFalse(scheduler.accept(firstPermit))
        assertFalse(scheduler.accept(newer))
    }

    @Test
    fun fixtureTextIsLabeledAndCouncilKeepsOtherParticipants() {
        val permit = OnDeviceScheduler().begin(gemma.id)
        val fixture = FixtureOnDeviceEngine.generate("hello", permit, accept = true)
        assertFalse(FixtureOnDeviceEngine.genuineInference)
        assertTrue(fixture.fixture)
        assertTrue(fixture.text.startsWith("FIXTURE_LOCAL_INFERENCE"))
        val unavailable = UnavailableOnDeviceEngine.generate("hello", permit, accept = true)
        assertEquals("", unavailable.text)
        assertTrue(unavailable.failure!!.contains("not a model response"))
        val cancelled = FixtureOnDeviceEngine.generate("hello", permit, accept = false)
        assertEquals("", cancelled.text)
        assertTrue(cancelled.cancelled)
        val seats = OnDeviceParticipation.seats(listOf(gemma.id, gemma.id, qwen.id))
        assertEquals(2, seats.size)
        assertEquals(ProviderId.LOCAL, seats.first().providerId)
        assertEquals(ReviewClassification.SELF_CRITIQUE, OnDeviceParticipation.review(gemma.id, gemma.id))
        assertEquals(ReviewClassification.CROSS_MODEL_REVIEW, OnDeviceParticipation.review(qwen.id, gemma.id))
        assertTrue(ParticipantRole.DEVILS_ADVOCATE in OnDeviceParticipation.roles)
        assertTrue(ParticipantRole.FINAL_SYNTHESIZER in OnDeviceParticipation.roles)
        assertEquals(listOf("claude"), OnDeviceParticipation.remaining(listOf("local-gemma", "claude"), "local-gemma"))
        assertTrue(OnDeviceParticipation.rejectToolUse("run a shell command").contains("cannot run device code"))
        assertTrue(OnDeviceDispatch.reason(gemma.id).contains("no tokens were generated"))
    }
}
