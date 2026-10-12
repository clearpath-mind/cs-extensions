package com.streamly

import android.app.ActivityManager
import android.content.Context
import com.lagradost.api.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Device-adaptive concurrency for Streamly link sources.
 * Mirrors StreamPlay's approach: detect the device profile once, then bound
 * parallel provider work with a semaphore.
 */
object StreamlyConcurrency {

    private const val TAG = "StreamlyConcurrency"

    enum class DeviceProfile {
        LOW_END,    // < 2GB RAM or < 4 cores
        MID_RANGE,  // < 4GB RAM or < 6 cores
        HIGH_END;   // everything else

        val recommendedConcurrency: Int
            get() = when (this) {
                LOW_END -> 8
                MID_RANGE -> 20
                HIGH_END -> 40
            }
    }

    @Volatile
    private var detectedProfile: DeviceProfile? = null

    fun detectDeviceProfile(context: Context?): DeviceProfile {
        detectedProfile?.let { return it }
        val profile = if (context == null) {
            DeviceProfile.MID_RANGE
        } else {
            runCatching {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val memInfo = ActivityManager.MemoryInfo()
                am?.getMemoryInfo(memInfo)
                val totalRamMB = memInfo.totalMem / (1024 * 1024)
                val cores = Runtime.getRuntime().availableProcessors()
                when {
                    totalRamMB < 2048 || cores < 4 -> DeviceProfile.LOW_END
                    totalRamMB < 4096 || cores < 6 -> DeviceProfile.MID_RANGE
                    else -> DeviceProfile.HIGH_END
                }
            }.getOrDefault(DeviceProfile.MID_RANGE)
        }
        detectedProfile = profile
        Log.d(TAG, "Detected device: $profile (concurrency: ${profile.recommendedConcurrency})")
        return profile
    }

    /** Run [tasks] with at most [concurrency] in flight; individual failures are swallowed. */
    suspend fun runLimitedAsync(
        context: Context?,
        vararg tasks: suspend () -> Unit,
    ) {
        if (tasks.isEmpty()) return
        // Honor a user-saved concurrency (set on first run from the device
        // profile); fall back to live detection when unset or out of range.
        val saved = runCatching {
            context?.getSharedPreferences("streamly_prefs", Context.MODE_PRIVATE)
                ?.getInt("provider_concurrency", -1) ?: -1
        }.getOrDefault(-1)
        val concurrency = if (saved in 8..50) saved
        else detectDeviceProfile(context).recommendedConcurrency
        val semaphore = Semaphore(concurrency)
        coroutineScope {
            tasks.map { task ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        try {
                            task()
                        } catch (e: Exception) {
                            Log.e(TAG, "Task failed: ${e.message}")
                        }
                    }
                }
            }.awaitAll()
        }
    }
}
