package com.smart.android.ad_app

import kotlin.random.Random

object Hq008LocalSchedulePolicy {
    private const val INITIAL_DELAY_MS = 5_000L
    private const val BASE_POLLING_SECONDS = 900L
    private const val MAX_POLLING_JITTER_SECONDS = 300L

    private val randomizedPollingSeconds by lazy {
        BASE_POLLING_SECONDS + Random.nextLong(MAX_POLLING_JITTER_SECONDS + 1)
    }

    fun initialDelayMs(): Long = INITIAL_DELAY_MS

    fun pollingSeconds(): Long = randomizedPollingSeconds
}
