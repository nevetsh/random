package com.meow.mod.api

// Waiting for the game to answer a click, instead of waiting for a timer.
//
// A click is answered by the menu changing. The menu a click was made in is almost never the
// menu the next step wants, so acting on it before it has been replaced is how an automation
// ends up clicking the wrong thing on a slow tick - which is a matter of the server being
// slow, not of the delay being too short. Modules that click through menus record the menu
// each click was made in here and then ask whether that click has been answered.
//
// Nothing sends anything from here: this only remembers, so a module keeps its own policy
// about what to do while it waits and what to do when the wait runs out.
class ClickWait(private val retryMs: Long, private val maxRetries: Int) {
	private var signature: String? = null
	private var at = 0L
	private var tries = 0

	// Records the menu a click was just made in. Pressing the same button in the same menu
	// again is a repeat of a click that went unanswered, so the attempts carry over and the
	// retries stay bounded.
	fun clicked(current: String) {
		if (current != signature) tries = 0
		signature = current
		at = System.currentTimeMillis()
	}

	// True while the menu is still exactly the one the click was made in, which is the same
	// as saying that click has not been answered yet.
	fun unanswered(current: String): Boolean = signature != null && current == signature

	// True once per retry, after a click that has changed nothing for retryMs, and at most
	// maxRetries times: a click that never reached the server is most likely lost, and
	// pressing the same button again is the only way forward.
	fun retryDue(current: String): Boolean {
		if (!unanswered(current)) return false
		if (System.currentTimeMillis() - at < retryMs) return false
		if (tries >= maxRetries) return false
		tries++
		at = System.currentTimeMillis()
		return true
	}

	// How long ago the last click was (or was retried), for a caller that gives up on its own
	// schedule.
	fun since(): Long = if (at == 0L) Long.MAX_VALUE else System.currentTimeMillis() - at

	fun reset() {
		signature = null
		at = 0L
		tries = 0
	}
}
