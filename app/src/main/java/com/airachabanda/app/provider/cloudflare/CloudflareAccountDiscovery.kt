package com.airachabanda.app.provider.cloudflare

data class CloudflareAccountDiscoveryResult(
    val accountId: String?,
    val reason: String,
)

object CloudflareAccountDiscovery {
    private val ACCOUNT = Regex(""""id"\s*:\s*"([a-fA-F0-9]{32})"""")

    fun read(body: String): CloudflareAccountDiscoveryResult {
        if (!body.contains(""""success":true""") && !body.contains(""""success": true""")) {
            return CloudflareAccountDiscoveryResult(null, "Cloudflare did not accept the token for account discovery. The provider stays skipped.")
        }
        val ids = ACCOUNT.findAll(body).map { it.groupValues[1] }.distinct().toList()
        return when (ids.size) {
            1 -> CloudflareAccountDiscoveryResult(ids.first(), "One account was listed. Workers Free is still not verified.")
            0 -> CloudflareAccountDiscoveryResult(null, "The token did not list an account. Cloudflare stays skipped.")
            else -> CloudflareAccountDiscoveryResult(null, "The token lists more than one account. Use a token limited to one account, or skip Cloudflare.")
        }
    }
}
