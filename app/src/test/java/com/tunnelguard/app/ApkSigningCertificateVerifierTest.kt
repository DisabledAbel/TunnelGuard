package com.tunnelguard.app

import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class ApkSigningCertificateVerifierTest {
    private fun signingInfo(current: Array<Signature>?, history: Array<Signature>? = null, multiple: Boolean = false): SigningInfo =
        mock<SigningInfo>().also {
            whenever(it.hasMultipleSigners()).thenReturn(multiple)
            whenever(it.apkContentsSigners).thenReturn(current)
            whenever(it.signingCertificateHistory).thenReturn(history)
        }

    @Test fun sameSignerAccepted() {
        val signer = Signature(byteArrayOf(1))
        assertEquals(SignerVerificationResult.SUCCESS, ApkSigningCertificateVerifier.verify(signingInfo(arrayOf(signer)), signingInfo(arrayOf(signer))))
    }

    @Test fun differentSignerRejected() {
        assertEquals(SignerVerificationResult.SIGNATURE_MISMATCH, ApkSigningCertificateVerifier.verify(signingInfo(arrayOf(Signature(byteArrayOf(2)))), signingInfo(arrayOf(Signature(byteArrayOf(1))))))
    }

    @Test fun legitimateForwardRotationAccepted() {
        val old = Signature(byteArrayOf(1)); val new = Signature(byteArrayOf(2))
        assertEquals(SignerVerificationResult.SUCCESS, ApkSigningCertificateVerifier.verify(signingInfo(arrayOf(new), arrayOf(old, new)), signingInfo(arrayOf(old))))
    }

    @Test fun reverseRotationRejected() {
        val old = Signature(byteArrayOf(1)); val new = Signature(byteArrayOf(2))
        assertEquals(SignerVerificationResult.SIGNATURE_MISMATCH, ApkSigningCertificateVerifier.verify(signingInfo(arrayOf(old)), signingInfo(arrayOf(new), arrayOf(old, new))))
    }

    @Test fun multiSignerMismatchRejected() {
        val first = Signature(byteArrayOf(1)); val second = Signature(byteArrayOf(2))
        assertEquals(SignerVerificationResult.SIGNATURE_MISMATCH, ApkSigningCertificateVerifier.verify(signingInfo(arrayOf(first, second), multiple = true), signingInfo(arrayOf(first), multiple = true)))
    }

    @Test fun exactMultiSignerSetAccepted() {
        val first = Signature(byteArrayOf(1)); val second = Signature(byteArrayOf(2))
        assertEquals(SignerVerificationResult.SUCCESS, ApkSigningCertificateVerifier.verify(signingInfo(arrayOf(first, second), multiple = true), signingInfo(arrayOf(second, first), multiple = true)))
    }

    @Test fun missingSigningInfoRejected() {
        assertEquals(SignerVerificationResult.SIGNING_INFO_MISSING, ApkSigningCertificateVerifier.verify(null, null))
    }

    @Test fun oneSidedMultiSignerRejected() {
        val first = Signature(byteArrayOf(1)); val second = Signature(byteArrayOf(2))
        assertEquals(
            SignerVerificationResult.SIGNATURE_MISMATCH,
            ApkSigningCertificateVerifier.verify(
                signingInfo(arrayOf(first, second), multiple = true),
                signingInfo(arrayOf(first), multiple = false)
            )
        )
    }

    @Test fun lineageNotEndingInCurrentSignerRejected() {
        val old = Signature(byteArrayOf(1)); val current = Signature(byteArrayOf(2))
        assertEquals(
            SignerVerificationResult.SIGNING_LINEAGE_INVALID,
            ApkSigningCertificateVerifier.verify(
                signingInfo(arrayOf(current), arrayOf(current, old)),
                signingInfo(arrayOf(old))
            )
        )
    }

    @Test fun partiallyMissingSigningInformationRejected() {
        val signer = Signature(byteArrayOf(1))
        assertEquals(
            SignerVerificationResult.SIGNING_INFO_MISSING,
            ApkSigningCertificateVerifier.verify(
                signingInfo(arrayOf(signer)),
                signingInfo(null)
            )
        )
    }
}
