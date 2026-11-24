package com.airachabanda.app.provider.gemini

import android.app.Activity
import android.content.IntentSender
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Official Android authorization. An Android OAuth client is a public client, so this
 * class does not set a server client id and does not contain a client secret.
 */
sealed class GeminiAuthOutcome {
    data class Granted(val accessToken: String) : GeminiAuthOutcome()
    data object Cancelled : GeminiAuthOutcome()
    data class Rejected(val detail: String) : GeminiAuthOutcome()
}

class PlayServicesGeminiAuthorizer(
    private val activity: ComponentActivity,
) {
    private var pending: CompletableDeferred<GeminiAuthOutcome>? = null

    fun onAuthorizationResult(result: ActivityResult) {
        val deferred = pending ?: return
        pending = null
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            deferred.complete(GeminiAuthOutcome.Cancelled)
            return
        }
        val data = result.data ?: return
        deferred.complete(
            try {
                outcomeFrom(Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data))
            } catch (error: ApiException) {
                classify(error.statusCode)
            },
        )
    }

    suspend fun authorize(launch: (IntentSenderRequest) -> Unit): GeminiAuthOutcome {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(GeminiProjectBinding.OAUTH_SCOPE)))
            .build()
        val step = suspendCancellableCoroutine { continuation ->
            Identity.getAuthorizationClient(activity)
                .authorize(request)
                .addOnSuccessListener { continuation.resume(Step.Ready(it)) }
                .addOnFailureListener { error ->
                    val code = (error as? ApiException)?.statusCode ?: CommonStatusCodes.ERROR
                    continuation.resume(Step.Failed(code))
                }
        }
        return when (step) {
            is Step.Failed -> classify(step.code)
            is Step.Ready -> {
                val authorization = step.result
                if (!authorization.hasResolution()) return outcomeFrom(authorization)
                val pendingIntent = authorization.pendingIntent
                    ?: return GeminiAuthOutcome.Rejected("Google did not open an authorization screen. No lookup was sent.")
                val deferred = CompletableDeferred<GeminiAuthOutcome>()
                pending = deferred
                try {
                    launch(IntentSenderRequest.Builder(pendingIntent).build())
                } catch (error: IntentSender.SendIntentException) {
                    pending = null
                    return GeminiAuthOutcome.Rejected("The Google authorization screen did not open. No lookup was sent.")
                }
                deferred.await()
            }
        }
    }

    private fun outcomeFrom(result: AuthorizationResult): GeminiAuthOutcome {
        val token = result.accessToken.orEmpty()
        val granted = result.grantedScopes.orEmpty()
        if (granted.isNotEmpty() && GeminiProjectBinding.OAUTH_SCOPE !in granted) {
            return GeminiAuthOutcome.Rejected("The Google account did not grant cloud-platform.read-only. No lookup was sent.")
        }
        if (token.isBlank()) {
            return GeminiAuthOutcome.Rejected("Google did not return an access token. No lookup was sent.")
        }
        return GeminiAuthOutcome.Granted(token)
    }

    private fun classify(code: Int): GeminiAuthOutcome = when (code) {
        CommonStatusCodes.CANCELED -> GeminiAuthOutcome.Cancelled
        CommonStatusCodes.DEVELOPER_ERROR -> GeminiAuthOutcome.Rejected(
            "Google Play services rejected this install. The Android OAuth client must use this package name and signing certificate. No lookup was sent.",
        )
        else -> GeminiAuthOutcome.Rejected("Google authorization did not start. Status $code. No lookup was sent.")
    }

    private sealed class Step {
        data class Ready(val result: AuthorizationResult) : Step()
        data class Failed(val code: Int) : Step()
    }
}
