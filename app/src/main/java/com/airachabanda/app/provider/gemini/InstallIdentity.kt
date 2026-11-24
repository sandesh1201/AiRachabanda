package com.airachabanda.app.provider.gemini

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

object CertificateFingerprint {
    fun sha1(encodedCertificate: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(encodedCertificate)
        return digest.joinToString(":") { "%02X".format(it) }
    }
}

object InstallIdentity {
    fun describe(context: Context): String {
        val fingerprint = signingSha1(context)
        return "Package ${context.packageName}. SHA-1 $fingerprint."
    }

    fun signingSha1(context: Context): String {
        val encoded = certificate(context) ?: return "unavailable"
        return CertificateFingerprint.sha1(encoded)
    }

    @Suppress("DEPRECATION")
    private fun certificate(context: Context): ByteArray? {
        val manager = context.packageManager
        val packageName = context.packageName
        return if (Build.VERSION.SDK_INT >= 28) {
            val info = manager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            val info = manager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            info.signatures?.firstOrNull()?.toByteArray()
        }
    }
}
