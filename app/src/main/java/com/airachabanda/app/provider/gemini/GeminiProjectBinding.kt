package com.airachabanda.app.provider.gemini

/**
 * Binds a Gemini API key to the project that owns it, then reads that project's billing.
 * The Gemini API key is not the caller. Nothing in this type contacts the network.
 */
object GeminiProjectBinding {
    const val LOOKUP_URL = "https://apikeys.googleapis.com/v2/keys:lookupKey"
    const val OAUTH_SCOPE = "https://www.googleapis.com/auth/cloud-platform.read-only"
    const val LOOKUP_PERMISSION = "apikeys.keys.lookup"
    const val BILLING_PERMISSION = "resourcemanager.projects.get"

    data class KeyLookup(val parent: String, val resourceName: String)

    data class ProjectBillingRead(
        val projectId: String,
        val billingEnabled: Boolean?,
        val billingAccountName: String,
    )

    data class Binding(
        val projectId: String?,
        val credentialBoundToProject: Boolean,
        val billingUnlinked: Boolean,
        val sendPermitted: Boolean,
        val reason: String,
    )

    fun billingUrl(projectId: String): String =
        "https://cloudbilling.googleapis.com/v1/projects/$projectId/billingInfo"

    fun assess(lookup: KeyLookup?, billing: ProjectBillingRead?, typedProjectId: String?): Binding {
        val projectId = lookup?.parent?.let(::projectIdFromParent)
        val named = lookup != null && lookup.resourceName.isNotBlank() && projectId != null
        val typedConflict = !typedProjectId.isNullOrBlank() && typedProjectId != projectId
        val billingMatches = named && billing != null && billing.projectId == projectId
        val unlinked = billingMatches && billing?.billingEnabled == false
        val reason = when {
            lookup == null -> "No key lookup was supplied. A typed project ID is not the key's project."
            lookup.resourceName.isBlank() -> "The key lookup returned no resource name. A purged key is not bound."
            projectId == null -> "The key lookup parent was not a project resource."
            typedConflict -> "The typed project ID does not match the project that owns the API key."
            billing == null -> "The key belongs to $projectId. Billing for that project was not read."
            billing.projectId != projectId -> "Billing was read for a different project than the key lookup."
            billing.billingEnabled == null -> "The billing read did not say whether billing is enabled."
            billing.billingEnabled -> "The project that owns the API key has billing enabled."
            else -> "The API key belongs to $projectId and billingEnabled is false. Live metadata was not sent."
        }
        return Binding(
            projectId = if (named && !typedConflict) projectId else null,
            credentialBoundToProject = billingMatches && !typedConflict,
            billingUnlinked = unlinked && !typedConflict,
            sendPermitted = false,
            reason = reason,
        )
    }

    private fun projectIdFromParent(parent: String): String? {
        val parts = parent.split("/")
        if (parts.size != 2 || parts[0] != "projects" || parts[1].isBlank()) return null
        return parts[1]
    }
}
