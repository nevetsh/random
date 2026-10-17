package com.meow.mod.features

import com.meow.mod.Access
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.util.FormattedCharSequence

// Hypixel's date line - "10/16/26 m10A", "10/16/26 m72DM 102,66" - ends in the id of the
// server the player is on, and it shows up on the scoreboard sidebar (and in the tab list).
// This drops that token from the line wherever the game draws it: every string drawn passes
// through the font, which is where this hooks in (see FontMixin), so the sidebar is covered
// without chasing each screen that renders the line.
//
// The token is 4-7 letters or digits directly after the date, and it must stand alone, so
// the coordinates that follow can never be swallowed by a shorter date match. Only the text
// on screen changes - nothing about it, or anything else, is sent anywhere.
object HideServerId : Feature(
	"hideServerId",
	"Hides the server id after the date.",
	subcategory = "General",
	displayName = "Hide Server ID",
	enabledByDefault = false,
	heading = "Misc"
) {
	// The date in day/month/year (or month/day/year) form, then the id: "m72DM", "m4DP" and
	// friends are 4-7 letters or digits, and the look-ahead keeps the token from running into
	// the coordinates after it.
	private val SERVER_ID = Regex("(\\d{1,2}/\\d{1,2}/\\d{2,4})\\s+[a-zA-Z0-9]{4,7}(?=\\s|$)")

	@JvmStatic
	fun isActive(): Boolean = configSwitch.get() && Access.authorized

	@JvmStatic
	fun replaceComponentIfNeeded(component: Component?): Component? {
		if (component == null || !isActive()) return null
		if (!SERVER_ID.containsMatchIn(component.string)) return null
		return splice(component.visualOrderText) ?: component
	}

	@JvmStatic
	fun replaceStringIfNeeded(text: String): String {
		if (!isActive()) return text
		if (!SERVER_ID.containsMatchIn(text)) return text
		return SERVER_ID.replace(text) { it.groupValues[1] }
	}

	@JvmStatic
	fun replaceSequenceIfNeeded(text: FormattedCharSequence): FormattedCharSequence {
		if (!isActive()) return text
		// Cheap first pass, same reason as CustomName: most sequences drawn hold no server id,
		// so the line is flattened once and dropped before the styled rebuild is worth doing.
		val full = StringBuilder()
		text.accept { _, _, cp ->
			full.appendCodePoint(cp)
			true
		}
		if (!SERVER_ID.containsMatchIn(full)) return text
		val rebuilt = splice(text) ?: return text
		return rebuilt.visualOrderText
	}

	// Flattens the line into code points with their styles, runs the rewrite over the whole
	// line at once, and rebuilds it keeping every run's style. Matching the whole line matters:
	// the sidebar builds the date line through the team formatter, so a colour change between
	// the date and the id leaves them in separate runs, and a per-run match never sees them
	// together. Returns null when the line has no server id.
	private fun splice(text: FormattedCharSequence): MutableComponent? {
		val codePoints = ArrayList<Int>()
		val styles = ArrayList<Style>()
		val line = StringBuilder()
		text.accept { _, style, codePoint ->
			codePoints.add(codePoint)
			styles.add(style)
			line.appendCodePoint(codePoint)
			true
		}
		val full = line.toString()
		if (!SERVER_ID.containsMatchIn(full)) return null

		// Character offsets and code point offsets only differ if a line contains a surrogate
		// pair, so map each character back to the code point it belongs to before dropping.
		val charToCodePoint = IntArray(full.length)
		var charIndex = 0
		for (i in codePoints.indices) {
			repeat(Character.charCount(codePoints[i])) { charToCodePoint[charIndex++] = i }
		}
		val removed = BooleanArray(codePoints.size)
		for (match in SERVER_ID.findAll(full)) {
			val keepThrough = match.range.first + match.groupValues[1].length - 1
			for (char in keepThrough + 1..match.range.last) removed[charToCodePoint[char]] = true
		}

		val out = Component.empty()
		val run = StringBuilder()
		var runStyle: Style? = null
		for (i in codePoints.indices) {
			if (removed[i]) continue
			val style = styles[i]
			if (runStyle != null && style != runStyle) {
				out.append(Component.literal(run.toString()).setStyle(runStyle))
				run.setLength(0)
			}
			runStyle = style
			run.appendCodePoint(codePoints[i])
		}
		if (run.isNotEmpty()) out.append(Component.literal(run.toString()).setStyle(runStyle ?: Style.EMPTY))
		return out
	}
}
