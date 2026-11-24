package com.airachabanda.app

import com.airachabanda.app.provider.local.ondevice.DownloadState
import com.airachabanda.app.provider.local.ondevice.LocalTask
import com.airachabanda.app.provider.local.ondevice.OnDeviceInstaller
import com.airachabanda.app.provider.local.ondevice.OnDeviceModelCatalog
import com.airachabanda.app.provider.local.ondevice.OnDeviceModelManager
import com.airachabanda.app.provider.local.ondevice.OnDeviceRuntime
import com.airachabanda.app.provider.local.ondevice.OnDeviceScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class Milestone015ETest {
    @Test
    fun pinnedRuntimeAndModelFilesStayUntransferred() {
        assertEquals("0.20.0", OnDeviceRuntime.PINNED_VERSION)
        assertEquals("CPU", OnDeviceRuntime.BACKEND)
        assertEquals(64, OnDeviceRuntime.AAR_SHA256.length)
        assertFalse(OnDeviceRuntime.PACKAGED)
        val gemma = OnDeviceModelCatalog.GEMMA
        val qwen = OnDeviceModelCatalog.QWEN
        assertTrue(gemma.fileName.endsWith(".litertlm"))
        assertFalse(gemma.fileName.contains("sm8650"))
        assertEquals(584_417_280L, gemma.sizeBytes)
        assertNull(gemma.sha256)
        assertEquals(1_597_922_856L, qwen.sizeBytes)
        assertEquals(64, qwen.sha256!!.length)
        assertEquals("a9bd85643f05cdb1e5cd5af7ba4e74b0e8210ca0", gemma.revision)
        assertEquals("60257f9f1d00ca1f24cc5ce3b829b4139be1ca2b", qwen.revision)
    }

    @Test
    fun installerRecoversAPartialFileAndRejectsABadDigest() {
        val root = File(System.getProperty("java.io.tmpdir"), "airachabanda-015e-${System.nanoTime()}")
        val payload = "fixture-weights-not-a-model".toByteArray()
        val digest = OnDeviceModelManager.sha256(payload)
        val model = OnDeviceModelCatalog.QWEN.copy(
            fileName = "fixture-model.litertlm",
            sizeBytes = payload.size.toLong(),
            sizeLabel = "${payload.size} bytes",
            sha256 = digest,
        )
        val installer = OnDeviceInstaller(root) { _, offset ->
            ByteArrayInputStream(payload.copyOfRange(offset.toInt(), payload.size))
        }
        val space = 80L * 1024 * 1024
        val quiet = installer.install(model, consent = false, availableBytes = space, approved = true)
        assertEquals(DownloadState.NOT_INSTALLED, quiet.state)
        assertEquals(0, installer.networkOpens)
        val stopped = installer.install(model, consent = true, availableBytes = space, approved = true, stopAfterBytes = 8)
        assertEquals(DownloadState.INTERRUPTED, stopped.state)
        assertEquals(8L, stopped.bytesTransferred)
        val done = installer.install(model, consent = true, availableBytes = space, approved = true)
        assertEquals(DownloadState.INSTALLED, done.state)
        assertTrue(File(root, model.fileName).isFile)
        assertFalse(File(root, "${model.fileName}.partial").exists())
        val bad = model.copy(fileName = "bad-model.litertlm", sha256 = "00".repeat(32))
        val rejected = installer.install(bad, consent = true, availableBytes = space, approved = true)
        assertEquals(DownloadState.REJECTED, rejected.state)
        assertFalse(File(root, bad.fileName).exists())
        val before = installer.networkOpens
        val gemma = installer.install(OnDeviceModelCatalog.GEMMA, consent = true, availableBytes = 8L shl 30, approved = true)
        assertEquals(DownloadState.BLOCKED, gemma.state)
        assertTrue(gemma.detail.contains("No trusted SHA-256"))
        assertEquals(before, installer.networkOpens)
        assertEquals(DownloadState.NOT_INSTALLED, installer.delete(model).state)
        root.deleteRecursively()
    }

    @Test
    fun taskLimitsStayInsideTheContextWindow() {
        val scheduler = OnDeviceScheduler()
        val arithmetic = scheduler.limitsFor(OnDeviceModelCatalog.GEMMA.id, LocalTask.ARITHMETIC, 32)
        assertTrue(arithmetic.accepted)
        assertEquals(128, arithmetic.outputTokens)
        assertEquals(60_000L, arithmetic.timeoutMs)
        val shortened = scheduler.limitsFor(OnDeviceModelCatalog.GEMMA.id, LocalTask.COUNCIL, 4000)
        assertTrue(shortened.accepted)
        assertEquals(96, shortened.outputTokens)
        assertTrue(shortened.detail.contains("shortened"))
        assertFalse(scheduler.limitsFor(OnDeviceModelCatalog.GEMMA.id, LocalTask.COUNCIL, 4096).accepted)
    }
}
