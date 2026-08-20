package com.tunnelguard.app.update

import android.app.Activity
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import com.tunnelguard.app.TunnelGuardConfig
import com.tunnelguard.app.UpdateManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
class UpdateManagerValidationTest {

    private lateinit var mockActivity: Activity
    private lateinit var mockPackageManager: PackageManager
    private lateinit var mockConfig: TunnelGuardConfig
    private lateinit var updateManager: UpdateManager
    private lateinit var mockFile: File

    @Before
    fun setUp() {
        mockActivity = mock(Activity::class.java)
        mockPackageManager = mock(PackageManager::class.java)
        mockConfig = mock(TunnelGuardConfig::class.java)
        mockFile = mock(File::class.java)

        whenever(mockActivity.packageManager).thenReturn(mockPackageManager)
        whenever(mockActivity.packageName).thenReturn("com.tunnelguard.app")
        whenever(mockFile.exists()).thenReturn(true)
        whenever(mockFile.length()).thenReturn(100L)
        whenever(mockFile.absolutePath).thenReturn("/fake/path/apk.apk")

        updateManager = UpdateManager(mockActivity, mockConfig)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.Q])
    fun testValidateApkFile_Android28Plus_Success_WithRotation() {
        val sigOld = mock(Signature::class.java)
        whenever(sigOld.toByteArray()).thenReturn(byteArrayOf(1, 2, 3))
        val sigNew = mock(Signature::class.java)
        whenever(sigNew.toByteArray()).thenReturn(byteArrayOf(4, 5, 6))

        val mockArchiveSigningInfo = mock(SigningInfo::class.java)
        whenever(mockArchiveSigningInfo.hasMultipleSigners()).thenReturn(false)
        // Rotated key history contains both old and new keys
        whenever(mockArchiveSigningInfo.getSigningCertificateHistory()).thenReturn(arrayOf(sigOld, sigNew))

        val mockCurrentSigningInfo = mock(SigningInfo::class.java)
        whenever(mockCurrentSigningInfo.hasMultipleSigners()).thenReturn(false)
        whenever(mockCurrentSigningInfo.getSigningCertificateHistory()).thenReturn(arrayOf(sigOld))

        val archivePackageInfo = PackageInfo().apply {
            packageName = "com.tunnelguard.app"
            signingInfo = mockArchiveSigningInfo
        }

        val currentPackageInfo = PackageInfo().apply {
            packageName = "com.tunnelguard.app"
            signingInfo = mockCurrentSigningInfo
        }

        whenever(mockPackageManager.getPackageArchiveInfo(eq("/fake/path/apk.apk"), eq(PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES)))
            .thenReturn(archivePackageInfo)

        whenever(mockPackageManager.getPackageInfo(eq("com.tunnelguard.app"), eq(PackageManager.GET_SIGNING_CERTIFICATES)))
            .thenReturn(currentPackageInfo)

        val errorBuilder = StringBuilder()
        val result = updateManager.validateApkFile(mockFile, errorBuilder)

        assertTrue(result)
        assertTrue(errorBuilder.isEmpty())
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.Q])
    fun testValidateApkFile_Android28Plus_Success_NonRotatedSingleSigner() {
        val sigCommon = mock(Signature::class.java)
        whenever(sigCommon.toByteArray()).thenReturn(byteArrayOf(1, 2, 3))

        val mockArchiveSigningInfo = mock(SigningInfo::class.java)
        whenever(mockArchiveSigningInfo.hasMultipleSigners()).thenReturn(false)
        // Standard non-rotated single-signer returns null for history
        whenever(mockArchiveSigningInfo.getSigningCertificateHistory()).thenReturn(null)
        whenever(mockArchiveSigningInfo.getApkContentsSigners()).thenReturn(arrayOf(sigCommon))

        val mockCurrentSigningInfo = mock(SigningInfo::class.java)
        whenever(mockCurrentSigningInfo.hasMultipleSigners()).thenReturn(false)
        whenever(mockCurrentSigningInfo.getSigningCertificateHistory()).thenReturn(null)
        whenever(mockCurrentSigningInfo.getApkContentsSigners()).thenReturn(arrayOf(sigCommon))

        val archivePackageInfo = PackageInfo().apply {
            packageName = "com.tunnelguard.app"
            signingInfo = mockArchiveSigningInfo
        }

        val currentPackageInfo = PackageInfo().apply {
            packageName = "com.tunnelguard.app"
            signingInfo = mockCurrentSigningInfo
        }

        whenever(mockPackageManager.getPackageArchiveInfo(eq("/fake/path/apk.apk"), eq(PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES)))
            .thenReturn(archivePackageInfo)

        whenever(mockPackageManager.getPackageInfo(eq("com.tunnelguard.app"), eq(PackageManager.GET_SIGNING_CERTIFICATES)))
            .thenReturn(currentPackageInfo)

        val errorBuilder = StringBuilder()
        val result = updateManager.validateApkFile(mockFile, errorBuilder)

        assertTrue(result)
        assertTrue(errorBuilder.isEmpty())
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.Q])
    fun testValidateApkFile_Android28Plus_SignatureMismatch() {
        val sig1 = mock(Signature::class.java)
        whenever(sig1.toByteArray()).thenReturn(byteArrayOf(1, 2, 3))
        val sig2 = mock(Signature::class.java)
        whenever(sig2.toByteArray()).thenReturn(byteArrayOf(4, 5, 6))

        val mockArchiveSigningInfo = mock(SigningInfo::class.java)
        whenever(mockArchiveSigningInfo.hasMultipleSigners()).thenReturn(false)
        whenever(mockArchiveSigningInfo.getSigningCertificateHistory()).thenReturn(arrayOf(sig1))

        val mockCurrentSigningInfo = mock(SigningInfo::class.java)
        whenever(mockCurrentSigningInfo.hasMultipleSigners()).thenReturn(false)
        whenever(mockCurrentSigningInfo.getSigningCertificateHistory()).thenReturn(arrayOf(sig2))

        val archivePackageInfo = PackageInfo().apply {
            packageName = "com.tunnelguard.app"
            signingInfo = mockArchiveSigningInfo
        }

        val currentPackageInfo = PackageInfo().apply {
            packageName = "com.tunnelguard.app"
            signingInfo = mockCurrentSigningInfo
        }

        whenever(mockPackageManager.getPackageArchiveInfo(eq("/fake/path/apk.apk"), eq(PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES)))
            .thenReturn(archivePackageInfo)

        whenever(mockPackageManager.getPackageInfo(eq("com.tunnelguard.app"), eq(PackageManager.GET_SIGNING_CERTIFICATES)))
            .thenReturn(currentPackageInfo)

        val errorBuilder = StringBuilder()
        val result = updateManager.validateApkFile(mockFile, errorBuilder)

        assertFalse(result)
        assertTrue(errorBuilder.toString().contains("Signature mismatch"))
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.Q])
    fun testValidateApkFile_Android28Plus_PackageNameMismatch() {
        val archivePackageInfo = PackageInfo().apply {
            packageName = "com.different.app"
        }

        whenever(mockPackageManager.getPackageArchiveInfo(eq("/fake/path/apk.apk"), eq(PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES)))
            .thenReturn(archivePackageInfo)

        val errorBuilder = StringBuilder()
        val result = updateManager.validateApkFile(mockFile, errorBuilder)

        assertFalse(result)
        assertTrue(errorBuilder.toString().contains("Package name mismatch"))
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.M])
    fun testValidateApkFile_Legacy_Success() {
        val sig1 = mock(Signature::class.java)
        whenever(sig1.toByteArray()).thenReturn(byteArrayOf(1, 2, 3))

        val archivePackageInfo = PackageInfo().apply {
            packageName = "com.tunnelguard.app"
            signatures = arrayOf(sig1)
        }

        val currentPackageInfo = PackageInfo().apply {
            packageName = "com.tunnelguard.app"
            signatures = arrayOf(sig1)
        }

        whenever(mockPackageManager.getPackageArchiveInfo(eq("/fake/path/apk.apk"), eq(PackageManager.GET_SIGNATURES)))
            .thenReturn(archivePackageInfo)

        whenever(mockPackageManager.getPackageInfo(eq("com.tunnelguard.app"), eq(PackageManager.GET_SIGNATURES)))
            .thenReturn(currentPackageInfo)

        val errorBuilder = StringBuilder()
        val result = updateManager.validateApkFile(mockFile, errorBuilder)

        assertTrue(result)
        assertTrue(errorBuilder.isEmpty())
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.M])
    fun testValidateApkFile_Legacy_SignatureMismatch() {
        val sig1 = mock(Signature::class.java)
        val sig2 = mock(Signature::class.java)
        whenever(sig1.toByteArray()).thenReturn(byteArrayOf(1, 2, 3))
        whenever(sig2.toByteArray()).thenReturn(byteArrayOf(4, 5, 6))

        val archivePackageInfo = PackageInfo().apply {
            packageName = "com.tunnelguard.app"
            signatures = arrayOf(sig1)
        }

        val currentPackageInfo = PackageInfo().apply {
            packageName = "com.tunnelguard.app"
            signatures = arrayOf(sig2)
        }

        whenever(mockPackageManager.getPackageArchiveInfo(eq("/fake/path/apk.apk"), eq(PackageManager.GET_SIGNATURES)))
            .thenReturn(archivePackageInfo)

        whenever(mockPackageManager.getPackageInfo(eq("com.tunnelguard.app"), eq(PackageManager.GET_SIGNATURES)))
            .thenReturn(currentPackageInfo)

        val errorBuilder = StringBuilder()
        val result = updateManager.validateApkFile(mockFile, errorBuilder)

        assertFalse(result)
        assertTrue(errorBuilder.toString().contains("Signature mismatch"))
    }

    @Test
    fun testUninstallCurrentVersion_launchesUninstallIntent() {
        val result = updateManager.uninstallCurrentVersion()

        val intentCaptor = org.mockito.kotlin.argumentCaptor<android.content.Intent>()
        verify(mockActivity).startActivity(intentCaptor.capture())

        val capturedIntent = intentCaptor.firstValue
        assertEquals(android.content.Intent.ACTION_UNINSTALL_PACKAGE, capturedIntent.action)
        assertEquals("package:com.tunnelguard.app", capturedIntent.dataString)
        assertTrue(capturedIntent.getBooleanExtra(android.content.Intent.EXTRA_RETURN_RESULT, false))
        assertTrue(result)
    }

    @Test
    fun testInstallerManager_uninstallCurrentVersion_launchesUninstallIntent() {
        val installerManager = InstallerManager(mockActivity, mockConfig)
        val result = installerManager.uninstallCurrentVersion()

        val intentCaptor = org.mockito.kotlin.argumentCaptor<android.content.Intent>()
        verify(mockActivity).startActivity(intentCaptor.capture())

        val capturedIntent = intentCaptor.firstValue
        assertEquals(android.content.Intent.ACTION_UNINSTALL_PACKAGE, capturedIntent.action)
        assertEquals("package:com.tunnelguard.app", capturedIntent.dataString)
        assertTrue(capturedIntent.getBooleanExtra(android.content.Intent.EXTRA_RETURN_RESULT, false))
        assertTrue(result)
    }
}
