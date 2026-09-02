package com.tunnelguard.app

/** Monotonic, deterministic state for one automatic VPN application launch. */
internal class AutoConnectCoordinator(
    private val elapsedRealtime: () -> Long,
    private val timeoutMs: Long = AUTO_CONNECT_TIMEOUT_MS
) {
    data class Attempt(
        val targetPackage: String,
        val vpnPackage: String,
        val startedAtElapsedMs: Long,
        val deadlineElapsedMs: Long
    )

    sealed class Evaluation {
        data object None : Evaluation()
        data class Waiting(val attempt: Attempt) : Evaluation()
        data class Succeeded(val attempt: Attempt) : Evaluation()
        data class TimedOut(val attempt: Attempt) : Evaluation()
        data class Cancelled(val attempt: Attempt) : Evaluation()
    }

    var activeAttempt: Attempt? = null
        private set

    /** Starts an attempt only when the same launch is not already being awaited. */
    @Synchronized
    fun start(targetPackage: String, vpnPackage: String): Boolean {
        if (activeAttempt?.let { it.targetPackage == targetPackage && it.vpnPackage == vpnPackage } == true) {
            return false
        }
        val now = elapsedRealtime()
        activeAttempt = Attempt(targetPackage, vpnPackage, now, now + timeoutMs)
        return true
    }

    /** Reconciles the attempt against freshly evaluated configuration and VPN policy. */
    @Synchronized
    fun evaluate(isRelevant: Boolean, requirementSatisfied: Boolean): Evaluation {
        val attempt = activeAttempt ?: return Evaluation.None
        if (!isRelevant) {
            activeAttempt = null
            return Evaluation.Cancelled(attempt)
        }
        if (requirementSatisfied) {
            activeAttempt = null
            return Evaluation.Succeeded(attempt)
        }
        if (elapsedRealtime() >= attempt.deadlineElapsedMs) {
            activeAttempt = null
            return Evaluation.TimedOut(attempt)
        }
        return Evaluation.Waiting(attempt)
    }

    @Synchronized
    fun cancel(): Attempt? = activeAttempt.also { activeAttempt = null }
}

/** Time allowed for one launched VPN application to satisfy the effective policy. */
internal const val AUTO_CONNECT_TIMEOUT_MS = 10_000L
