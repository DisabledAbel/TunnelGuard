package com.tunnelguard.app.update

import android.app.Activity
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import com.tunnelguard.app.TunnelGuardConfig
import com.tunnelguard.app.UpdateManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.mockito.kotlin.whenever
import java.io.File

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
    fun testValidateApkFile_Success() {
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

        // Mock getPackageArchiveInfo to return archivePackageInfo
        whenever(mockPackageManager.getPackageArchiveInfo(eq("/fake/path/apk.apk"), eq(PackageManager.GET_SIGNATURES)))
            .thenReturn(archivePackageInfo)

        // Mock getPackageInfo for installed app
        whenever(mockPackageManager.getPackageInfo(eq("com.tunnelguard.app"), eq(PackageManager.GET_SIGNATURES)))
            .thenReturn(currentPackageInfo)

        val errorBuilder = StringBuilder()
        val result = updateManager.validateApkFile(mockFile, errorBuilder)

        assertTrue(result)
        assertTrue(errorBuilder.isEmpty())
    }

    @Test
    fun testValidateApkFile_PackageNameMismatch() {
        val sig1 = mock(Signature::class.java)
        whenever(sig1.toByteArray()).thenReturn(byteArrayOf(1, 2, 3))

        val archivePackageInfo = PackageInfo().apply {
            packageName = "com.different.app"
            signatures = arrayOf(sig1)
        }

        whenever(mockPackageManager.getPackageArchiveInfo(eq("/fake/path/apk.apk"), eq(PackageManager.GET_SIGNATURES)))
            .thenReturn(archivePackageInfo)

        val errorBuilder = StringBuilder()
        val result = updateManager.validateApkFile(mockFile, errorBuilder)

        assertFalse(result)
        assertTrue(errorBuilder.toString().contains("Package name mismatch"))
    }

    @Test
    fun testValidateApkFile_SignatureMismatch() {
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
}
