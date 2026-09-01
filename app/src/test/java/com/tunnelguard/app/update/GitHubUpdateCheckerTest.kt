package com.tunnelguard.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubUpdateCheckerTest {
    private val checker = GitHubUpdateCheckerImpl()

    @Test
    fun selectsOnlyCanonicalStablePackage() {
        val expectedUrl = releaseUrl("v1.2.3", "TunnelGuard-v1.2.3-release.apk")
        val assets = listOf(
            GitHubAsset("TunnelGuard-debug.apk", releaseUrl("v1.2.3", "TunnelGuard-debug.apk")),
            GitHubAsset("TunnelGuard-v1.2.3-release.apk.sha256", releaseUrl("v1.2.3", "TunnelGuard-v1.2.3-release.apk.sha256")),
            GitHubAsset("TunnelGuard-v1.2.3-release.apk", expectedUrl)
        )

        assertEquals(expectedUrl, checker.selectOfficialApkAsset("v1.2.3", assets))
    }

    @Test
    fun rejectsApkFromAnotherPackageOrVersion() {
        val assets = listOf(
            GitHubAsset("TunnelGuard-alpha.apk", releaseUrl("v1.2.3", "TunnelGuard-alpha.apk")),
            GitHubAsset("TunnelGuard-v1.2.2-release.apk", releaseUrl("v1.2.3", "TunnelGuard-v1.2.2-release.apk"))
        )

        assertNull(checker.selectOfficialApkAsset("v1.2.3", assets))
    }

    @Test
    fun rejectsDuplicateCanonicalPackages() {
        val name = "TunnelGuard-v1.2.3-release.apk"
        val assets = listOf(
            GitHubAsset(name, releaseUrl("v1.2.3", name)),
            GitHubAsset(name, releaseUrl("v1.2.3", "duplicate/$name"))
        )

        assertNull(checker.selectOfficialApkAsset("v1.2.3", assets))
    }

    private fun releaseUrl(tag: String, fileName: String) =
        "https://github.com/DisabledAbel/TunnelGuard/releases/download/$tag/$fileName"
}
