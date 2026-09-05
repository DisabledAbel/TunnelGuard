package com.tunnelguard.app

import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import androidx.annotation.RequiresApi
import java.security.MessageDigest

internal enum class SignerVerificationResult {
    SUCCESS,
    SIGNING_INFO_MISSING,
    SIGNATURE_MISMATCH,
    SIGNING_LINEAGE_INVALID
}

/** Security-sensitive helpers for comparing public APK signing certificates. */
internal object ApkSigningCertificateVerifier {
    /**
     * Verifies that [downloaded] is a valid update from [installed].
     *
     * A rotated, single-signer update is accepted only when the *currently active*
     * installed certificate occurs in the downloaded APK's lineage. This direction
     * matters: merely sharing an old certificate would also accept a downgrade to an
     * older signer. APKs using multiple concurrent signers must retain the exact set.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    fun verify(downloaded: SigningInfo?, installed: SigningInfo?): SignerVerificationResult {
        if (downloaded == null || installed == null) return SignerVerificationResult.SIGNING_INFO_MISSING

        val downloadedMultiple = downloaded.hasMultipleSigners()
        val installedMultiple = installed.hasMultipleSigners()
        val downloadedCurrent = downloaded.apkContentsSigners?.toCertificateSet().orEmpty()
        val installedCurrent = installed.apkContentsSigners?.toCertificateSet().orEmpty()
        if (downloadedCurrent.isEmpty() || installedCurrent.isEmpty()) {
            return SignerVerificationResult.SIGNING_INFO_MISSING
        }

        if (downloadedMultiple || installedMultiple) {
            return if (downloadedMultiple && installedMultiple && downloadedCurrent == installedCurrent) {
                SignerVerificationResult.SUCCESS
            } else {
                SignerVerificationResult.SIGNATURE_MISMATCH
            }
        }

        if (downloadedCurrent.size != 1 || installedCurrent.size != 1) {
            return SignerVerificationResult.SIGNING_LINEAGE_INVALID
        }
        val downloadedHistory = downloaded.signingCertificateHistory?.toCertificateList()
            ?: downloadedCurrent.toList()
        if (downloadedHistory.isEmpty() || downloadedHistory.last() !in downloadedCurrent) {
            return SignerVerificationResult.SIGNING_LINEAGE_INVALID
        }

        return if (installedCurrent.single() in downloadedHistory) {
            SignerVerificationResult.SUCCESS
        } else {
            SignerVerificationResult.SIGNATURE_MISMATCH
        }
    }

    /** Returns a non-secret SHA-256 fingerprint of a public signing certificate. */
    fun sha256Fingerprint(signature: Signature): String =
        MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray())
            .joinToString(":") { "%02X".format(it) }

    private fun Array<Signature>.toCertificateSet(): Set<List<Byte>> =
        mapTo(linkedSetOf()) { it.toByteArray().toList() }

    private fun Array<Signature>.toCertificateList(): List<List<Byte>> =
        map { it.toByteArray().toList() }
}
