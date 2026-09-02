package com.tunnelguard.app

import android.content.Context
import android.content.SharedPreferences
import android.net.Network
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.kotlin.whenever
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

class VpnCountryResolverTest {

    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var config: TunnelGuardConfig
    private val prefsStore = ConcurrentHashMap<String, Any>()

    @Before
    fun setUp() {
        mockContext = mock(Context::class.java)
        mockPrefs = mock(SharedPreferences::class.java)
        mockEditor = mock(SharedPreferences.Editor::class.java)

        prefsStore.clear()
        whenever(mockContext.getSharedPreferences(anyString(), anyInt())).thenReturn(mockPrefs)
        whenever(mockPrefs.edit()).thenReturn(mockEditor)

        whenever(mockEditor.putString(anyString(), org.mockito.kotlin.anyOrNull())).thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            val value = invocation.getArgument<String?>(1)
            if (value != null) prefsStore[key] = value else prefsStore.remove(key)
            mockEditor
        }

        whenever(mockPrefs.getString(anyString(), org.mockito.kotlin.anyOrNull())).thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            val default = invocation.getArgument<String?>(1)
            (prefsStore[key] as? String) ?: default
        }

        config = TunnelGuardConfig(mockContext)
    }

    @Test
    fun testSuccessfulCountryResolutionFromApiCountryIs() {
        val mockNet = mock(Network::class.java)
        val resolver = VpnCountryResolver(config, fetcher = { _, url ->
            if (url == "https://api.country.is") {
                """{"ip":"1.2.3.4","country":"DE"}"""
            } else null
        })

        val result = resolver.resolveCountry(mockNet)
        assertEquals("DE", result)
        assertEquals("DE", config.getActiveVpnCountryCode())
    }

    @Test
    fun testFallbackToSecondEndpointOnFirstFailure() {
        val mockNet = mock(Network::class.java)
        val resolver = VpnCountryResolver(config, fetcher = { _, url ->
            when (url) {
                "https://api.country.is" -> null // Fails
                "https://ipapi.co/json" -> """{"ip":"1.2.3.4","country_code":"GB"}"""
                else -> null
            }
        })

        val result = resolver.resolveCountry(mockNet)
        assertEquals("GB", result)
        assertEquals("GB", config.getActiveVpnCountryCode())
    }

    @Test
    fun testVpnNetworkLookupNeverFallsBackToUnboundRoute() {
        val mockNet = mock(Network::class.java)
        val attemptedNetworks = mutableListOf<Network?>()
        val resolver = VpnCountryResolver(config, fetcher = { network, url ->
            if (url == "https://api.country.is") {
                attemptedNetworks.add(network)
            }
            null
        })

        assertNull(resolver.resolveCountry(mockNet))
        assertEquals(listOf(mockNet), attemptedNetworks)
    }

    @Test
    fun testSupportsCountryCodeReturnedByAdditionalProvider() {
        val mockNet = mock(Network::class.java)
        val resolver = VpnCountryResolver(config, fetcher = { _, url ->
            if (url == "https://api.iplocation.net/?cmd=get-ip-country") {
                """{"country_name":"Australia","country_code2":"AU"}"""
            } else null
        })

        assertEquals("AU", resolver.resolveCountry(mockNet))
        assertEquals("AU", config.getActiveVpnCountryCode())
    }

    @Test
    fun testFallbackToThirdEndpoint() {
        val mockNet = mock(Network::class.java)
        val resolver = VpnCountryResolver(config, fetcher = { _, url ->
            when (url) {
                "https://api.country.is" -> "invalid json"
                "https://ipapi.co/json" -> "{}"
                "https://ipinfo.io/json" -> """{"country":"CA"}"""
                else -> null
            }
        })

        val result = resolver.resolveCountry(mockNet)
        assertEquals("CA", result)
        assertEquals("CA", config.getActiveVpnCountryCode())
    }

    @Test
    fun testAllEndpointsFailingReturnsNull() {
        val mockNet = mock(Network::class.java)
        val resolver = VpnCountryResolver(config, fetcher = { _, _ -> null })

        val result = resolver.resolveCountry(mockNet)
        assertNull(result)
        assertEquals("", config.getActiveVpnCountryCode())
    }

    @Test
    fun testExpiredCacheFailureClearsItsPersistedCountry() {
        val mockNet = mock(Network::class.java)
        var now = 1_000L
        var response: String? = """{"country":"US"}"""
        val resolver = VpnCountryResolver(config, fetcher = { _, _ -> response }, clock = { now })

        assertEquals("US", resolver.resolveCountry(mockNet))
        now += 300_001L
        response = null

        assertNull(resolver.resolveCountry(mockNet))
        assertEquals("", config.getActiveVpnCountryCode())
    }

    @Test
    fun testFailedLookupCannotClearAnotherNetworksCountry() {
        val failingNet = mock(Network::class.java)
        val successfulNet = mock(Network::class.java)
        whenever(failingNet.toString()).thenReturn("failing-network")
        whenever(successfulNet.toString()).thenReturn("successful-network")
        val failureStarted = CountDownLatch(1)
        val releaseFailure = CountDownLatch(1)
        val resolver = VpnCountryResolver(config, fetcher = { network, url ->
            when {
                network === failingNet && url == "https://api.country.is" -> {
                    failureStarted.countDown()
                    releaseFailure.await(2, TimeUnit.SECONDS)
                    null
                }
                network === successfulNet && url == "https://api.country.is" -> """{"country":"CA"}"""
                else -> null
            }
        })
        val executor = Executors.newSingleThreadExecutor()
        val failedLookup = executor.submit<String?> { resolver.resolveCountry(failingNet) }
        assertTrue(failureStarted.await(2, TimeUnit.SECONDS))

        assertEquals("CA", resolver.resolveCountry(successfulNet))
        releaseFailure.countDown()
        assertNull(failedLookup.get(2, TimeUnit.SECONDS))
        executor.shutdownNow()

        assertEquals("CA", config.getActiveVpnCountryCode())
    }

    @Test
    fun testNetworkCachingBehavior() {
        val mockNet = mock(Network::class.java)
        var callCount = 0

        val resolver = VpnCountryResolver(config, fetcher = { _, _ ->
            callCount++
            """{"country":"JP"}"""
        })

        val first = resolver.resolveCountry(mockNet)
        assertEquals("JP", first)
        assertEquals(1, callCount)

        // Second call should return cached result without calling fetcher again
        val second = resolver.resolveCountry(mockNet)
        assertEquals("JP", second)
        assertEquals(1, callCount)

        // Clear cache for network and re-query
        resolver.clearCacheForNetwork(mockNet)
        val third = resolver.resolveCountry(mockNet)
        assertEquals("JP", third)
        assertEquals(2, callCount)
    }

    @Test
    fun testClearCacheGlobally() {
        val mockNet = mock(Network::class.java)
        var callCount = 0

        val resolver = VpnCountryResolver(config, fetcher = { _, _ ->
            callCount++
            """{"country":"FR"}"""
        })

        resolver.resolveCountry(mockNet)
        assertEquals(1, callCount)

        resolver.clearCache()
        resolver.resolveCountry(mockNet)
        assertEquals(2, callCount)
    }
}
