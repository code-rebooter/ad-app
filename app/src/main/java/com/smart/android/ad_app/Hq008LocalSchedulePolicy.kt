package com.smart.android.ad_app

import android.content.Context
import kotlin.random.Random

object Hq008LocalSchedulePolicy {
    // Give CMP/local persisted state more time to settle before the first floating poll.
    private const val INITIAL_DELAY_MS = 30_000L
    private const val BASE_POLLING_SECONDS = 1_200L
    private const val MAX_POLLING_JITTER_SECONDS = 300L
    private const val PREFS_NAME = "hq008_local_schedule_policy"
    private const val KEY_LAST_FLOATING_POLL_AT_MS = "last_floating_poll_at_ms"
    private const val KEY_SERVER_POLLING_SECONDS_PREFIX = "server_polling_seconds_"
    private const val MIN_SERVER_POLLING_SECONDS = 10L
    private const val MAX_SERVER_POLLING_SECONDS = 24 * 60 * 60L

    private val randomizedPollingSeconds by lazy {
        BASE_POLLING_SECONDS + Random.nextLong(MAX_POLLING_JITTER_SECONDS + 1)
    }

    @Volatile
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun initialDelayMs(channelId: String = BuildConfig.CHANNEL): Long {
        val context = appContext ?: return INITIAL_DELAY_MS
        val lastPollAtMs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(lastFloatingPollKey(channelId), 0L)
            .takeIf { it > 0L }
        return resolveInitialDelayMs(
            lastPollAtMs = lastPollAtMs,
            nowMs = System.currentTimeMillis(),
            pollingSeconds = resolveEffectivePollingSeconds(channelId) ?: randomizedPollingSeconds
        )
    }

    fun pollingSeconds(channelId: String = BuildConfig.CHANNEL): Long {
        return resolveEffectivePollingSeconds(channelId) ?: randomizedPollingSeconds
    }

    fun updateServerPollingSeconds(channelId: String, nextRequestSeconds: Long) {
        val normalized = normalizeServerPollingSeconds(nextRequestSeconds) ?: return
        val context = appContext ?: return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(serverPollingKey(channelId), normalized)
            .apply()
    }

    fun clearServerPollingSeconds(channelId: String) {
        val context = appContext ?: return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(serverPollingKey(channelId))
            .apply()
    }

    fun markFloatingPollTriggered(channelId: String = BuildConfig.CHANNEL, timestampMs: Long = System.currentTimeMillis()) {
        val context = appContext ?: return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(lastFloatingPollKey(channelId), timestampMs)
            .apply()
    }

    internal fun resolveEffectivePollingSeconds(channelId: String): Long? {
        val context = appContext ?: return null
        val storedServerSeconds = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(serverPollingKey(channelId), 0L)
            .takeIf { it > 0L }
        return storedServerSeconds ?: randomizedPollingSeconds
    }

    internal fun normalizeServerPollingSeconds(nextRequestSeconds: Long): Long? {
        return nextRequestSeconds.takeIf { it in MIN_SERVER_POLLING_SECONDS..MAX_SERVER_POLLING_SECONDS }
    }

    internal fun resolveInitialDelayMs(
        lastPollAtMs: Long?,
        nowMs: Long,
        pollingSeconds: Long
    ): Long {
        if (lastPollAtMs == null || lastPollAtMs <= 0L) {
            return INITIAL_DELAY_MS
        }
        val elapsedMs = nowMs - lastPollAtMs
        if (elapsedMs < 0L) {
            return INITIAL_DELAY_MS
        }
        val remainingMs = pollingSeconds.coerceAtLeast(1L) * 1_000L - elapsedMs
        return if (remainingMs <= 0L) {
            INITIAL_DELAY_MS
        } else {
            remainingMs.coerceAtLeast(INITIAL_DELAY_MS)
        }
    }

    private fun lastFloatingPollKey(channelId: String): String {
        return "${KEY_LAST_FLOATING_POLL_AT_MS}_$channelId"
    }

    private fun serverPollingKey(channelId: String): String {
        return "${KEY_SERVER_POLLING_SECONDS_PREFIX}$channelId"
    }
}
