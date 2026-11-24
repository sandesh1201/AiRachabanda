package com.airachabanda.app

import com.airachabanda.app.provider.cloudflare.CloudflareConnectCheck
import com.airachabanda.app.provider.cloudflare.CloudflareSubscriptionRead
import com.airachabanda.app.provider.connect.RuntimeEligibility
import com.airachabanda.app.provider.groq.GroqConnectCheck
import com.airachabanda.app.provider.openrouter.HttpExchange
import com.airachabanda.app.provider.openrouter.HttpResult
import com.airachabanda.app.provider.openrouter.HttpTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Milestone015CTest {
    @Test
    fun aStoredKeyIsNotReady() {
        val stored = RuntimeEligibility(credentialStored = true, detail = "saved")
        assertFalse(stored.ready)
        assertTrue(stored.lines().any { it == "Ready for automated Council participation: no" })
    }

    @Test
    fun cloudflareDiscoveryDoesNotTreatAMissingPaidPlanAsFree() = runBlocking {
        val transport = ScriptedTransport(
            mapOf(
                "https://api.cloudflare.com/client/v4/accounts" to HttpResult(200, emptyMap(), """{"success":true,"result":[{"id":"0123456789abcdef0123456789abcdef"}]}"""),
                "https://api.cloudflare.com/client/v4/accounts/0123456789abcdef0123456789abcdef/subscriptions" to HttpResult(200, emptyMap(), """{"success":true,"result":[]}"""),
            ),
        )
        val quiet = CloudflareConnectCheck(transport).run("fixture-token", consent = false)
        assertFalse(transport.called)
        assertFalse(quiet.ready)
        val checked = CloudflareConnectCheck(transport).run("fixture-token", consent = true)
        assertTrue(checked.authenticationVerified)
        assertFalse(checked.billingVerified)
        assertFalse(checked.eligibleModelDiscovered)
        assertFalse(checked.ready)
        assertTrue(checked.detail.contains("not that proof"))
        val paid = CloudflareSubscriptionRead.read("""{"result":[{"rate_plan":{"id":"WORKERS_PAID"}}]}""")
        assertTrue(paid.paidWorkersListed)
        assertFalse(paid.positiveWorkersFree)
    }

    @Test
    fun groqModelListDoesNotProveTheFreePlan() = runBlocking {
        val transport = ScriptedTransport(
            mapOf("https://api.groq.com/openai/v1/models" to HttpResult(200, emptyMap(), """{"data":[{"id":"fixture-model"}]}""")),
        )
        val checked = GroqConnectCheck(transport).run("fixture-key", consent = true)
        assertTrue(checked.authenticationVerified)
        assertFalse(checked.billingVerified)
        assertFalse(checked.ready)
        assertTrue(checked.detail.contains("Free or Developer"))
        val rejected = GroqConnectCheck(
            ScriptedTransport(mapOf("https://api.groq.com/openai/v1/models" to HttpResult(401, emptyMap(), "{}"))),
        ).run("fixture-key", consent = true)
        assertFalse(rejected.authenticationVerified)
        assertFalse(rejected.ready)
    }

    private class ScriptedTransport(private val responses: Map<String, HttpResult>) : HttpTransport {
        var called = false
        override suspend fun exchange(request: HttpExchange): HttpResult {
            called = true
            return responses[request.url] ?: HttpResult(404, emptyMap(), "{}")
        }
    }
}
