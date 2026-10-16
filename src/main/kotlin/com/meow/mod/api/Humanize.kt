package com.meow.mod.api

import kotlin.random.Random

// Every delay setting in the mod is a base value, but nobody waits exactly the same number
// of milliseconds twice. Each wait is stretched or shortened by a random 10-20%, which is
// enough to break up the rhythm without changing how fast a module actually works.
//
// Roll it once per wait, not once per tick: a delay that is compared against the clock
// every tick would otherwise be won by its shortest roll, which erodes the wait towards
// 80% of the configured value instead of varying around it.
fun humanDelay(millis: Long): Long {
	val percent = Random.nextDouble(MIN_VARIATION, MAX_VARIATION) * if (Random.nextBoolean()) 1.0 else -1.0
	// Rounded rather than truncated: cutting the fraction off would stop short delays
	// (a few milliseconds) from ever being stretched at all.
	return Math.round(millis * (1.0 + percent)).coerceAtLeast(1L)
}

fun humanDelay(millis: Int): Int = humanDelay(millis.toLong()).toInt()

private const val MIN_VARIATION = 0.10
private const val MAX_VARIATION = 0.20
