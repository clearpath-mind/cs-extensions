package com.streamly

import android.content.SharedPreferences
import com.lagradost.api.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-provider performance tracking for Streamly link sources.
 * Mirrors StreamPlay's cache: success/failure stats with a circuit breaker,
 * priority scoring used to order providers in loadLinks, and persistence.
 */
object StreamlyCache {

    private const val TAG = "StreamlyCache"

    data class ProviderStats(
        val successCount: Int = 0,
        val failureCount: Int = 0,
        val totalTimeMs: Long = 0,
        val consecutiveFailures: Int = 0,
    ) {
        val successRate: Float
            get() = if (successCount + failureCount == 0) 0f
            else successCount.toFloat() / (successCount + failureCount)

        val avgTimeMs: Long
            get() = if (successCount == 0) 0L else totalTimeMs / successCount

        val isCircuitBroken: Boolean
            get() = consecutiveFailures >= 5
    }

    private val providerStatsMap = ConcurrentHashMap<String, ProviderStats>()

    fun getProviderStats(providerId: String): ProviderStats =
        providerStatsMap[providerId] ?: ProviderStats()

    fun recordProviderExecution(providerId: String, success: Boolean, durationMs: Long) {
        val current = providerStatsMap[providerId] ?: ProviderStats()
        val updated = if (success) {
            current.copy(
                successCount = current.successCount + 1,
                totalTimeMs = current.totalTimeMs + durationMs,
                consecutiveFailures = 0,
            )
        } else {
            current.copy(
                failureCount = current.failureCount + 1,
                consecutiveFailures = current.consecutiveFailures + 1,
            )
        }
        providerStatsMap[providerId] = updated

        if (updated.isCircuitBroken && !current.isCircuitBroken) {
            Log.w(TAG, "Provider moved to low priority: $providerId (${updated.consecutiveFailures} consecutive failures)")
        } else if (!updated.isCircuitBroken && current.isCircuitBroken) {
            Log.d(TAG, "Provider recovered: $providerId")
        }
    }

    /** Sensible cold-start order before any stats exist. */
    private val BASE_PRIORITY = mapOf("topcinema" to 3f, "wecima" to 2f, "faselhd" to 1f, "egybest" to 1f)

    /** Higher score runs earlier; broken providers sink to the end. */
    fun getProviderPriorityScore(providerId: String): Float {
        val base = BASE_PRIORITY[providerId] ?: 0f
        val stats = getProviderStats(providerId)
        if (stats.isCircuitBroken) return -1000f + base
        if (stats.successCount + stats.failureCount == 0) return base
        val timePenalty = if (stats.avgTimeMs > 0) stats.avgTimeMs / 1000f else 0f
        return base + (stats.successRate * 100f - timePenalty)
    }

    // ==================== Persistence ====================

    private const val STATS_PREFIX = "streamly_stats_"

    fun saveProviderStats(prefs: SharedPreferences?) {
        prefs ?: return
        prefs.edit().apply {
            providerStatsMap.forEach { (id, stats) ->
                putString(
                    STATS_PREFIX + id,
                    "${stats.successCount},${stats.failureCount},${stats.totalTimeMs},${stats.consecutiveFailures}"
                )
            }
        }.apply()
    }

    fun loadProviderStats(prefs: SharedPreferences?) {
        prefs ?: return
        prefs.all.forEach { (key, value) ->
            if (!key.startsWith(STATS_PREFIX) || value !is String) return@forEach
            val id = key.removePrefix(STATS_PREFIX)
            val parts = value.split(",")
            if (parts.size < 4) return@forEach
            runCatching {
                providerStatsMap[id] = ProviderStats(
                    successCount = parts[0].toInt(),
                    failureCount = parts[1].toInt(),
                    totalTimeMs = parts[2].toLong(),
                    consecutiveFailures = parts[3].toInt(),
                )
            }.onFailure { Log.e(TAG, "Error loading stats for $id: ${it.message}") }
        }
        Log.d(TAG, "Loaded provider stats from prefs (${providerStatsMap.size} providers)")
    }
}
