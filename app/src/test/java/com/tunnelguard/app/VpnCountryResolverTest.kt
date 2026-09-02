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

class VpnCountryResolverTest {

    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var config: TunnelGuardConfig
    private val prefsStore = mutableMapOf<String, Any>()

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
    fun testFallsBackToDefaultRouteWhenVpnNetworkRejectsBoundRequest() {
        val mockNet = mock(Network::class.java)
        val attemptedNetworks = mutableListOf<Network?>()
        val resolver = VpnCountryResolver(config, fetcher = { network, url ->
            if (url == "https://api.country.is") {
                attemptedNetworks.add(network)
                if (network == null) """{"country":"NL"}""" else null
            } else {
                null
            }
        })

        assertEquals("NL", resolver.resolveCountry(mockNet))
        assertEquals(listOf(mockNet, null), attemptedNetworks)
        assertEquals("NL", config.getActiveVpnCountryCode())
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
        config.setActiveVpnCountryCode("US")
        val resolver = VpnCountryResolver(config, fetcher = { _, _ -> null })

        val result = resolver.resolveCountry(mockNet)
        assertNull(result)
        assertEquals("", config.getActiveVpnCountryCode())
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
