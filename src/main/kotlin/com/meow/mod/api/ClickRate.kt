package com.meow.mod.api

import kotlin.random.Random

// The gap between two clicks at a given rate, carrying the same small random wobble Auto
// Click has always used so a run never falls into a fixed, audible rhythm. Never below a
// millisecond.
fun cpsDelay(cps: Double): Long =
	(1000.0 / cps + (Random.nextDouble() - 0.5) * 60.0).toLong().coerceAtLeast(1L)
