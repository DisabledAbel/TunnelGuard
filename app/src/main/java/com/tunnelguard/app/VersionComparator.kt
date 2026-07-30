package com.tunnelguard.app

object VersionComparator {

    /**
     * Validates that the version has exactly two or three numeric components.
     * Returns "1.0.1" as fallback for malformed components, non-numeric values,
     * or versions with any other number of parts. Otherwise, returns the cleaned version.
     */
    fun validateAndNormalizeVersion(version: String): String {
        val clean = version.trim().removePrefix("v").removePrefix("V")
        val parts = clean.split(".")
        if (parts.size != 2 && parts.size != 3) {
            return "1.0.1"
        }
        for (part in parts) {
            if (part.isEmpty() || !part.all { it.isDigit() }) {
                return "1.0.1"
            }
        }
        return clean
    }

    /**
     * Compares two semantic version strings (e.g. "1.0", "1.0.5", "2.1.3").
     * Returns true if [latest] is a newer version than [current].
     *
     * Leading 'v' characters are stripped from both version names.
     * Each version string is split by "." and compared component by component.
     * Missing components in shorter arrays default to 0 (so "1.0" is equivalent to "1.0.0").
     */
    fun isNewerVersion(current: String, latest: String): Boolean {
        val currClean = current.trim().removePrefix("v")
        val lateClean = latest.trim().removePrefix("v")

        val currParts = currClean.split(".")
        val lateParts = lateClean.split(".")

        val maxParts = maxOf(currParts.size, lateParts.size)

        for (i in 0 until maxParts) {
            val currVal = if (i < currParts.size) getLeadingNumber(currParts[i]) else 0
            val lateVal = if (i < lateParts.size) getLeadingNumber(lateParts[i]) else 0

            if (lateVal > currVal) {
                return true
            } else if (currVal > lateVal) {
                return false
            }
        }

        return false
    }

    private fun getLeadingNumber(part: String): Int {
        val digits = part.takeWhile { it.isDigit() }
        return digits.toIntOrNull() ?: 0
    }
}
