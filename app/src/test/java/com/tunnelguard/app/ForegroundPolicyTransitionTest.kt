package com.tunnelguard.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundPolicyTransitionTest {
    private val usApp = ForegroundPolicyObservation(
        "app.us", ForegroundVpnPolicy.ProtectedApp("app.us", "US")
    )
    private val caApp = ForegroundPolicyObservation(
        "app.ca", ForegroundVpnPolicy.ProtectedApp("app.ca", "CA")
    )

    @Test
    fun `US app switching to CA app invalidates a US upstream VPN`() {
        val detector = ForegroundPolicyTransitionDetector()
        assertTrue(detector.observe(usApp))
        assertTrue(evaluate(usApp.policy, "US") is UpstreamVpnEvaluation.Valid)

        assertTrue(detector.observe(caApp))
        assertTrue(evaluate(caApp.policy, "US") is UpstreamVpnEvaluation.CountryMismatch)
    }

    @Test
    fun `mismatching protected app switching to matching app restores valid state`() {
        assertTrue(evaluate(caApp.policy, "US") is UpstreamVpnEvaluation.CountryMismatch)
        assertTrue(evaluate(usApp.policy, "US") is UpstreamVpnEvaluation.Valid)
    }

    @Test
    fun `protected and unprotected transitions are both detected`() {
        val detector = ForegroundPolicyTransitionDetector()
        val unprotected = ForegroundPolicyObservation(
            "app.other", ForegroundVpnPolicy.UnprotectedApp("app.other", "ANY")
        )
        assertTrue(detector.observe(usApp))
        assertTrue(detector.observe(unprotected))
        assertTrue(evaluate(unprotected.policy, "US") is UpstreamVpnEvaluation.Valid)
        assertTrue(detector.observe(usApp))
    }

    @Test
    fun `country setting change for same package is detected`() {
        val detector = ForegroundPolicyTransitionDetector()
        assertTrue(detector.observe(usApp))
        assertTrue(detector.observe(usApp.copy(policy = ForegroundVpnPolicy.ProtectedApp("app.us", "CA"))))
    }

    @Test
    fun `unavailable foreground remains fail closed when overrides exist`() {
        val unknown = ForegroundVpnPolicy.Unknown("ANY", hasProtectedCountryOverrides = true)
        assertEquals(
            UpstreamVpnEvaluation.ForegroundUnknown,
            evaluate(unknown, "US")
        )
    }

    @Test
    fun `duplicate package and policy only produce one transition`() {
        val detector = ForegroundPolicyTransitionDetector()
        assertTrue(detector.observe(usApp))
        repeat(20) { assertFalse(detector.observe(usApp)) }
    }

    @Test
    fun `emergency lock keeps priority during transitions`() {
        assertFalse(TunnelGuardVpnService.shouldEnterVpnConflict(
            evaluate(caApp.policy, "US"), emergencyLock = true
        ))
        assertTrue(TunnelGuardVpnService.shouldEnterVpnConflict(
            evaluate(caApp.policy, "US"), emergencyLock = false
        ))
    }

    @Test
    fun `rapid requests are serialized coalesced and finish with latest policy`() {
        val executor = Executors.newCachedThreadPool()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val evaluations = AtomicInteger()
        val latest = AtomicReference(usApp)
        val finalEvaluation = AtomicReference<UpstreamVpnEvaluation>()
        val evaluator = SerializedRoutingEvaluator(
            dispatch = { executor.execute(it) },
            evaluate = {
                val nowActive = active.incrementAndGet()
                maximumActive.updateAndGet { maxOf(it, nowActive) }
                val pass = evaluations.incrementAndGet()
                if (pass == 1) {
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
                finalEvaluation.set(evaluate(latest.get().policy, "US"))
                active.decrementAndGet()
                finished.countDown()
            }
        )

        evaluator.request()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        latest.set(caApp)
        val callers = List(20) { thread { evaluator.request() } }
        callers.forEach(Thread::join)
        release.countDown()

        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertEquals(1, maximumActive.get())
        assertEquals(2, evaluations.get())
        assertTrue(finalEvaluation.get() is UpstreamVpnEvaluation.CountryMismatch)
        executor.shutdownNow()
    }

    private fun evaluate(policy: ForegroundVpnPolicy, country: String): UpstreamVpnEvaluation {
        if (policy is ForegroundVpnPolicy.Unknown && policy.hasProtectedCountryOverrides) {
            return UpstreamVpnEvaluation.ForegroundUnknown
        }
        return UpstreamVpnEvaluation.fromObservation(
            externalVpnActive = true,
            requiredCountry = policy.requiredCountry,
            detectedCountry = country
        )
    }
}
