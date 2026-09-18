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
        val maxTimeMs: Long = 0,
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
                maxTimeMs = maxOf(current.maxTimeMs, durationMs),
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
    private val BASE_PRIORITY = mapOf("topcinema" to 3f, "wecima" to 2f, "faselhd" to 1f, "shoof" to 1f, "egydead" to 1f)

    /** Higher score runs earlier; broken providers sink to the end. */
    fun getProviderPriorityScore(providerId: String): Float {
        val base = BASE_PRIORITY[providerId] ?: 0f
        val stats = getProviderStats(providerId)
        if (stats.isCircuitBroken) return -1000f + base
        if (stats.successCount + stats.failureCount == 0) return base
        val timePenalty = if (stats.avgTimeMs > 0) stats.avgTimeMs / 1000f else 0f
        return base + (stats.successRate * 100f - timePenalty)
    }

    /**
     * Per-provider time budget from history (StreamPlay adaptive-timeout
     * pattern, widened for WebView sources): a provider gets its slowest
     * success or avg+5s (never below 20s, never above 120s). Broken providers
     * get 20s recovery probes — 5s made recovery impossible since a healthy
     * FaselHD/MyCima run needs 15-70s and always timed out, locking them
     * broken forever.
     *
     * Phase-gated extension (v76): a broken never-successful provider that
     * actually matched something (episode/post URL found, extraction started
     * via [markEpisodeMatched]) may keep running up to [EXTRACTION_BUDGET_MS]
     * total — see loadLinks. Providers that never matched stay at 20s so
     * dead ends (MyCima junk results, Shoof no-anchor) fail fast.
     */
    fun getAdaptiveTimeout(providerId: String, baseTimeoutMs: Long = 90000): Long {
        val stats = getProviderStats(providerId)
        if (stats.successCount == 0) {
            return if (stats.isCircuitBroken) 20000L else baseTimeoutMs
        }
        // Broken but previously successful: probe with room to repeat the
        // slowest success (capped) — a flat 20s kills FaselHD mid-resolve
        // when walled, locking it broken forever. Floor 45s: a walled
        // search (~18s) plus episode extraction (~25s) must fit.
        if (stats.isCircuitBroken) {
            if (stats.maxTimeMs > 0) return minOf(maxOf(45000L, stats.maxTimeMs), 120000L)
            return 20000L
        }
        val avg = stats.avgTimeMs
        if (avg == 0L && stats.maxTimeMs == 0L) return baseTimeoutMs
        return minOf(maxOf(avg + 5000, stats.maxTimeMs, 20000L), 120000L)
    }

    // ==================== Per-run match signal ====================

    /**
     * Total wall-clock room for a provider run that matched something and
     * entered extraction: walled search (~18s) plus episode extraction
     * (~25s) must fit, cf. FaselHD Blacklist S1E13 needing ~46s end to end.
     */
    const val EXTRACTION_BUDGET_MS = 45000L

    /** Providers that reached extraction in the current loadLinks run. */
    private val matchedProviders = ConcurrentHashMap.newKeySet<String>()

    /** Called on entry to a provider's post/episode extraction. */
    fun markEpisodeMatched(providerId: String) {
        matchedProviders.add(providerId)
    }

    fun hasEpisodeMatched(providerId: String): Boolean =
        matchedProviders.contains(providerId)

    /** Called before each provider run so a stale match never extends. */
    fun clearRunState(providerId: String) {
        matchedProviders.remove(providerId)
    }

    // ==================== Persistence ====================

    private const val STATS_PREFIX = "streamly_stats_"

    fun saveProviderStats(prefs: SharedPreferences?) {
        prefs ?: return
        prefs.edit().apply {
            providerStatsMap.forEach { (id, stats) ->
                putString(
                    STATS_PREFIX + id,
                    "${stats.successCount},${stats.failureCount},${stats.totalTimeMs},${stats.consecutiveFailures},${stats.maxTimeMs}"
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
                    maxTimeMs = parts.getOrNull(4)?.toLongOrNull() ?: 0L,
                )
            }.onFailure { Log.e(TAG, "Error loading stats for $id: ${it.message}") }
        }
        Log.d(TAG, "Loaded provider stats from prefs (${providerStatsMap.size} providers)")
    }
}
