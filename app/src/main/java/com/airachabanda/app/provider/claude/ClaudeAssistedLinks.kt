package com.airachabanda.app.provider.claude

object ClaudeAssistedLinks {
    const val OFFICIAL_SITE = "https://claude.ai"
    const val LINKED_BILLING_ARTICLE =
        "https://support.claude.com/en/articles/18467148-use-your-claude-platform-account-in-a-third-party-app"
    const val LEGAL_PAGE = "https://code.claude.com/docs/en/legal-and-compliance"

    const val AUTOMATED_INFERENCE_ENABLED = false

    const val STATUS_SUMMARY =
        "Claude participation is assisted. Opening claude.ai does not verify a login. " +
            "Imported text is user-supplied and attributed to Claude. It is not an authenticated API response."

    const val LINKED_BILLING_FINDING =
        "Linked billing is an official Anthropic path for a Claude Console organization that already pays Anthropic. " +
            "Subscription plans cannot be linked. Requests bill a prepaid balance or an invoice. " +
            "That is pay-as-you-go or invoice billing, so it is not enabled under Zero-Cost Mode."
}
