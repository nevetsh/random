package com.meow.mod.api

import net.minecraft.network.chat.Component

// Hypixel's action bar, captured as the server sends it and read back as numbers. The text
// itself is kept, so `/mm testvitality` can show exactly what arrived - the stat glyphs are
// private-use code points (and older builds used literal ❤ / ✎ / ♨), so a reading that names
// a stat by its word is preferred over one that guesses at its symbol.
//
// The bar reads "<cur>/<max>❤  <cur>✎  <cur>/<max>❈  ..." - Mana comes through as a bare current
// value with no "/max", so a stat is matched by its trailing glyph or word rather than by being
// a "cur/max" pair. Vitality takes Mana's place for as long as a Vitality-spending item is held,
// so the bar is read while that item is in hand (see AutoHeal).
object ActionBar {
	@Volatile var raw: String = ""
	@Volatile var updatedAt: Long = 0L

	@Volatile var health: Int? = null
	@Volatile var maxHealth: Int? = null
	@Volatile var mana: Int? = null
	@Volatile var maxMana: Int? = null
	@Volatile var vitality: Int? = null
	@Volatile var maxVitality: Int? = null

	fun update(component: Component) = update(component.string)

	fun update(text: String) {
		raw = text
		updatedAt = System.currentTimeMillis()

		val clean = text.noControlCodes()
		val tokens = TOKEN.findAll(clean).toList()

		var h: Int? = null
		var hMax: Int? = null
		var m: Int? = null
		var mMax: Int? = null
		var v: Int? = null
		var vMax: Int? = null
		var bare: Pair<Int, Int>? = null

		tokens.forEachIndexed { index, match ->
			val current = number(match.groupValues[1]) ?: return@forEachIndexed
			// Mana (and Vitality) can be shown on their own, as just the current value with no
			// "/max" after it, so the maximum is optional here.
			val maximum = match.groupValues[2].takeIf { it.isNotEmpty() }?.let { number(it) }
			val end = match.range.last + 1
			val next = tokens.getOrNull(index + 1)?.range?.first ?: clean.length
			val tail = clean.substring(end, next).lowercase()

			when {
				names(tail, "vitality", GLYPH_VITALITY, GLYPH_VITALITY_ALT) -> { v = current; vMax = maximum }
				names(tail, "mana", GLYPH_MANA, GLYPH_MANA_ALT) -> { m = current; mMax = maximum }
				tail.contains("defense") || tail.contains(GLYPH_DEFENSE) -> {}
				names(tail, null, GLYPH_HEALTH, GLYPH_HEALTH_ALT) -> { h = current; hMax = maximum }
				maximum != null && bare == null -> bare = current to maximum
			}
		}

		// Health is the one stat whose word never appears, so when no glyph was recognised the
		// first unclaimed "cur/max" (health comes first on the bar) is taken as it.
		if (h == null && bare != null) {
			h = bare.first
			hMax = bare.second
		}

		health = h
		maxHealth = hMax
		mana = m
		maxMana = mMax
		vitality = v
		maxVitality = vMax
	}

	fun clear() {
		raw = ""
		updatedAt = 0L
		health = null
		maxHealth = null
		mana = null
		maxMana = null
		vitality = null
		maxVitality = null
	}

	// Health as a percentage of its maximum, or null while the bar has said nothing about it.
	fun hpPercent(): Double? {
		val current = health ?: return null
		val maximum = maxHealth ?: return null
		if (maximum <= 0) return null
		return current * 100.0 / maximum
	}

	// A one-line dump of what the last bar held, for /mm testvitality.
	fun describe(): String {
		if (raw.isEmpty()) return "no action bar has arrived yet"
		return "raw=[$raw] health=$health/$maxHealth mana=$mana/$maxMana vitality=$vitality/$maxVitality hp=${
			hpPercent()?.let { String.format("%.1f", it) } ?: "-"
		}%"
	}

	private fun names(tail: String, word: String?, vararg glyphs: Char): Boolean =
		(word != null && tail.contains(word)) || glyphs.any { tail.contains(it) }

	private fun number(text: String): Int? = text.replace(",", "").toIntOrNull()

	// A stat value: either "cur/max" or a bare "cur", commas allowed. The maximum is group 2 and
	// is empty when the server only wrote the current value (which it does for Mana).
	private val TOKEN = Regex("([0-9][0-9,]*)(?:\\s*/\\s*([0-9][0-9,]*))?")

	private const val GLYPH_HEALTH = '\uE010'
	private const val GLYPH_HEALTH_ALT = '\u2764'
	private const val GLYPH_MANA = '\uE003'
	private const val GLYPH_MANA_ALT = '\u270E'
	private const val GLYPH_VITALITY = '\uE028'
	private const val GLYPH_VITALITY_ALT = '\u2668'
	private const val GLYPH_DEFENSE = '\u2748'
}
