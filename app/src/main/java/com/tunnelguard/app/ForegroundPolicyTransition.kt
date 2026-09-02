package com.tunnelguard.app

import java.util.concurrent.atomic.AtomicBoolean

/** A foreground observation whose identity and effective policy are evaluated together. */
data class ForegroundPolicyObservation(
    val packageName: String?,
    val policy: ForegroundVpnPolicy
)

/**
 * Deduplicates foreground observations. Policy is deliberately part of the key because settings
 * can change the effective country while the foreground package remains unchanged.
 */
class ForegroundPolicyTransitionDetector {
    private var previous: ForegroundPolicyObservation? = null

    @Synchronized
    fun observe(observation: ForegroundPolicyObservation): Boolean {
        if (observation == previous) return false
        previous = observation
        return true
    }
}

/**
 * Coalesces requests and runs routing evaluations one at a time. A request received during an
 * evaluation causes exactly one further pass, so the final observation cannot be lost.
 */
class SerializedRoutingEvaluator(
    private val dispatch: ((() -> Unit) -> Unit),
    private val evaluate: () -> Unit
) {
    private val running = AtomicBoolean(false)
    private val pending = AtomicBoolean(false)

    fun request() {
        pending.set(true)
        startDrainIfNeeded()
    }

    private fun startDrainIfNeeded() {
        if (!running.compareAndSet(false, true)) return
        dispatch {
            while (true) {
                while (pending.getAndSet(false)) evaluate()
                running.set(false)
                if (!pending.get() || !running.compareAndSet(false, true)) return@dispatch
            }
        }
    }
}
