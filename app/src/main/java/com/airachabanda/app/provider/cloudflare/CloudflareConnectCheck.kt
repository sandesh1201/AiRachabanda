package com.airachabanda.app.provider.cloudflare

import com.airachabanda.app.provider.connect.RuntimeEligibility
import com.airachabanda.app.provider.openrouter.HttpExchange
import com.airachabanda.app.provider.openrouter.HttpTransport
import com.airachabanda.app.security.RedactedLog

class CloudflareConnectCheck(
    private val transport: HttpTransport,
) {
    suspend fun run(token: String, consent: Boolean): RuntimeEligibility {
        if (!consent) {
            return RuntimeEligibility(detail = "Cloudflare was not contacted. Connect was not confirmed.")
        }
        if (token.isBlank()) {
            return RuntimeEligibility(detail = "No Cloudflare token is available. No request was sent.")
        }
        val accounts = transport.exchange(get("https://api.cloudflare.com/client/v4/accounts", token))
        if (accounts.status == 401 || accounts.status == 403) {
            return RuntimeEligibility(credentialStored = true, detail = "Cloudflare rejected the token. The token was not logged.")
        }
        if (accounts.status !in 200..299) {
            return RuntimeEligibility(credentialStored = true, detail = "Account discovery returned HTTP ${accounts.status}. No model was run.")
        }
        val discovered = CloudflareAccountDiscovery.read(accounts.body)
        val accountId = discovered.accountId
            ?: return RuntimeEligibility(credentialStored = true, authenticationVerified = true, detail = discovered.reason)
        val subscriptions = transport.exchange(
            get("https://api.cloudflare.com/client/v4/accounts/$accountId/subscriptions", token),
        )
        if (subscriptions.status !in 200..299) {
            return RuntimeEligibility(
                credentialStored = true,
                authenticationVerified = true,
                detail = "One account was listed. The subscriptions read returned HTTP ${subscriptions.status}. Workers Free was not verified.",
            )
        }
        val plan = CloudflareSubscriptionRead.read(subscriptions.body)
        return RuntimeEligibility(
            credentialStored = true,
            authenticationVerified = true,
            billingVerified = plan.positiveWorkersFree,
            detail = plan.reason,
        )
    }

    private fun get(url: String, token: String): HttpExchange {
        RedactedLog.d("Cloudflare", "Prepared GET $url")
        return HttpExchange(
            method = "GET",
            url = url,
            headers = mapOf("Authorization" to "Bearer $token", "Accept" to "application/json"),
            body = null,
        )
    }
}

data class CloudflareSubscriptionReadResult(
    val positiveWorkersFree: Boolean,
    val paidWorkersListed: Boolean,
    val reason: String,
)

object CloudflareSubscriptionRead {
    fun read(body: String): CloudflareSubscriptionReadResult {
        val paid = body.contains("WORKERS_PAID", ignoreCase = true)
        if (paid) {
            return CloudflareSubscriptionReadResult(
                positiveWorkersFree = false,
                paidWorkersListed = true,
                reason = "WORKERS_PAID is listed. Automated Workers AI stays off.",
            )
        }
        return CloudflareSubscriptionReadResult(
            positiveWorkersFree = false,
            paidWorkersListed = false,
            reason = "The subscriptions response does not name Workers Free. Omitting WORKERS_PAID is not that proof. Automated use stays off.",
        )
    }
}
