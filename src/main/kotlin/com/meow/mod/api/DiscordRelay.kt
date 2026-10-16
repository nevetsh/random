package com.meow.mod.api

/**
 * The shape of a relay line sent with /dc or /irc.
 *
 * Both directions go through one Discord channel under the bot's own account, so a line typed
 * with those commands has to be told apart from this player's public chat being relayed. The
 * rules live here, away from the client, so they can be read and checked without the game.
 */
object DiscordRelay {
	/**
	 * Marks a line that came from /dc or /irc. A zero width space: invisible in Discord, and it
	 * sits inside the message rather than at either edge, where whitespace gets trimmed.
	 */
	const val DIRECT_MARK = ":\u200b "

	/** What to post for a direct line: the same text every client will render. */
	fun direct(name: String, body: String): String = "$name$DIRECT_MARK$body"

	/** How a direct line reads in chat, identically on the sender's client and everyone else's. */
	fun directLine(name: String, body: String): String = "&8[&dMeow&8] &b$name&7: &f$body"

	/**
	 * The sender and body of a direct line, or null when [content] is some other kind of
	 * message (a real Discord user's text, or public chat being relayed).
	 */
	fun parse(content: String): Pair<String, String>? {
		val mark = content.indexOf(DIRECT_MARK)
		if (mark <= 0) return null
		return content.substring(0, mark) to content.substring(mark + DIRECT_MARK.length)
	}
}
