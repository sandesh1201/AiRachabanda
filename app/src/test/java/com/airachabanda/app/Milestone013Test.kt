package com.airachabanda.app

import com.airachabanda.app.core.model.ResearchMode
import com.airachabanda.app.documents.DocumentEngine
import com.airachabanda.app.documents.DocumentRequest
import com.airachabanda.app.documents.OutputFormat
import com.airachabanda.app.documents.PdfText
import com.airachabanda.app.documents.TemporaryFiles
import com.airachabanda.app.orchestration.DestinationPolicy
import com.airachabanda.app.orchestration.DiscussionBudgets
import com.airachabanda.app.orchestration.DiscussionPlanner
import com.airachabanda.app.orchestration.ExecutionGate
import com.airachabanda.app.orchestration.ExecutionIntent
import com.airachabanda.app.orchestration.PinnedHttps
import com.airachabanda.app.orchestration.ResearchConnectors
import com.airachabanda.app.orchestration.SourceRanking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress

class Milestone013Test {
    @Test
    fun modesUseSeparateBudgetsAndDoNotRaiseThem() {
        val quick = DiscussionBudgets.resolve(ResearchMode.QUICK)
        val standard = DiscussionBudgets.resolve(ResearchMode.STANDARD)
        val deep = DiscussionBudgets.resolve(ResearchMode.DEEP_RESEARCH)
        assertEquals(1, quick.rounds)
        assertEquals(1, quick.aiRequests)
        assertEquals(4, standard.aiRequests)
        assertTrue(standard.aiRequests != standard.rounds)
        assertEquals(6, deep.researchSources)
        assertTrue(deep.verificationRequired)
        val raised = DiscussionBudgets.resolve(
            ResearchMode.CUSTOM,
            DiscussionBudgets(99, 99, 99, 99, 99, 99, 99, true),
        )
        assertEquals(DiscussionBudgets.MAX_ROUNDS, raised.rounds)
        assertEquals(DiscussionBudgets.MAX_AI_REQUESTS, raised.aiRequests)
        assertEquals(DiscussionBudgets.MAX_SOURCES, raised.researchSources)
        val plan = DiscussionPlanner.plan(
            DiscussionPlanner.Signals(
                objective = "TEST FIXTURE compare two notes.",
                mode = ResearchMode.CUSTOM,
                explicitCapabilities = emptySet(),
                participantCount = 2,
                requestBudget = raised.aiRequests,
                deadlineEpochMs = null,
                nowEpochMs = 1L,
                consequences = false,
                researchSources = 2,
                researchRequests = 1,
                concurrencyCap = 1,
                depthCap = 1,
            ),
        )
        assertEquals(1, plan.concurrencyLimit)
        assertEquals(1, plan.delegationDepthLimit)
        assertTrue(plan.note.contains("Research sources 2"))
        assertTrue(plan.note.contains("Research requests 1"))
    }

    @Test
    fun mixedPrivateDnsAndRedirectTargetsStayClosed() {
        val publicAddress = InetAddress.getByAddress(byteArrayOf(93, 184.toByte(), 216.toByte(), 34))
        val privateAddress = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 8))
        val loopback = InetAddress.getByName("127.0.0.1")
        assertFalse(DestinationPolicy.addressesArePublic(listOf(publicAddress, privateAddress)))
        assertFalse(DestinationPolicy.addressesArePublic(listOf(loopback)))
        assertTrue(DestinationPolicy.addressesArePublic(listOf(publicAddress)))
        val pinned = PinnedHttps.target("https://en.wikipedia.org/w/api.php?action=query", publicAddress)
        assertEquals("93.184.216.34", pinned.host)
        assertEquals("en.wikipedia.org", java.net.URI("https://en.wikipedia.org/w/api.php").host)
        assertFalse(pinned.host.equals("en.wikipedia.org", ignoreCase = true))
        assertFalse(ExecutionGate.allow(ExecutionIntent.EXECUTE_CODE))
        assertFalse(ExecutionGate.allow(ExecutionIntent.READ_CREDENTIAL))
        assertFalse(ExecutionGate.allow(ExecutionIntent.OPEN_NETWORK))
        assertFalse(ExecutionGate.allow(ExecutionIntent.RAISE_BUDGET))
    }

    @Test
    fun rankingPrefersTitleOverlapAndDoesNotTreatItAsSupport() {
        val weak = source("Other topic", "A page about something else.", "2020-01-01")
        val strong = source("Gravity", "Gravity is a force.", "2019-01-01")
        val chosen = SourceRanking.choose("gravity", listOf(weak, strong))
        assertEquals("Gravity", chosen?.title)
        assertTrue((chosen?.excerpt ?: "").contains("force"))
    }

    @Test
    fun pdfReportsCharactersTheFontCannotDraw() {
        assertTrue(PdfText.omitted("TEST FIXTURE café").isEmpty())
        val telugu = PdfText.omitted("TEST FIXTURE హలో")
        assertTrue(telugu.contains("were not drawn"))
        assertTrue(telugu.contains("TELUGU"))
        assertFalse(telugu.contains("question mark was substituted"))
        val generated = DocumentEngine.generate(
            DocumentRequest(
                title = "TEST FIXTURE",
                templateId = "business-report",
                body = "TEST FIXTURE హలో",
                formatName = "PDF",
                records = com.airachabanda.app.documents.StoredRecords(),
            ),
            OutputFormat.PDF,
        )
        assertEquals("PARTIAL", generated.status)
        assertTrue(generated.report.contains("were not drawn"))
        assertFalse(generated.bytes.toString(Charsets.ISO_8859_1).contains("3F3F"))
    }

    @Test
    fun abandonedCacheFilesAreRemovedAndUserFilesAreLeft() {
        val root = File(System.getProperty("java.io.tmpdir"), "airachabanda-013-cache")
        val kept = File(root, "kept")
        root.deleteRecursively()
        root.mkdirs()
        val old = File(root, "old.tmp")
        val fresh = File(root, "fresh.tmp")
        old.writeText("temporary")
        fresh.writeText("temporary")
        old.setLastModified(1_000L)
        fresh.setLastModified(10_000L)
        kept.mkdirs()
        val removed = TemporaryFiles.sweep(root, nowEpochMs = 10_000L, maxAgeMs = 1_000L)
        assertEquals(1, removed)
        assertFalse(old.exists())
        assertTrue(fresh.exists())
        assertTrue(kept.exists())
        root.deleteRecursively()
    }

    private fun source(title: String, excerpt: String, published: String) = ResearchConnectors.ParsedSource(
        sourceId = title,
        canonical = "https://example.com/$title",
        original = "https://example.com",
        title = title,
        publisher = "Example",
        author = "",
        published = published,
        excerpt = excerpt,
    )
}
