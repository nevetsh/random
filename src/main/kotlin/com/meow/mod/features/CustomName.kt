package com.meow.mod.features

import com.meow.mod.Access
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.contents.PlainTextContents
import net.minecraft.util.FormattedCharSequence

object CustomName : Feature(
	"customName",
	"Shows a custom name in chat.",
	subcategory = "General",
	displayName = "Custom Name",
	enabledByDefault = true,
	heading = "Cosmetics"
) {
	private val settingName = addText(
		"name",
		"femboy",
		"Shown instead of your username.",
		"Name",
		heading = "Cosmetics"
	)

	private var cacheReal = ""
	private var cacheCustom = ""
	private var displayRegex: Regex? = null

	private fun customName(): String = settingName.get().trim()

	private fun realName(): String = Minecraft.getInstance().user.name

	@JvmStatic
	fun isActive(): Boolean {
		if (!configSwitch.get() || !Access.authorized) return false
		val custom = customName()
		if (custom.isEmpty()) return false
		val real = realName()
		return real.isNotEmpty() && !custom.equals(real, ignoreCase = true)
	}

	private fun displayPattern(): Regex? {
		if (!isActive()) return null
		val real = realName()
		val custom = customName()
		if (real != cacheReal || custom != cacheCustom) {
			cacheReal = real
			cacheCustom = custom
			displayRegex = wordRegex(real)
		}
		return displayRegex
	}

	private fun wordRegex(word: String): Regex =
		Regex("(?<![A-Za-z0-9_])${Regex.escape(word)}(?![A-Za-z0-9_])")

	@JvmStatic
	fun replaceStringIfNeeded(text: String): String {
		val regex = displayPattern() ?: return text
		if (!regex.containsMatchIn(text)) return text
		return regex.replace(text) { customName() }
	}

	@JvmStatic
	fun rewriteOutgoing(message: String): String {
		if (!message.startsWith("/")) return message
		if (!isActive()) return message
		val custom = customName()
		if (!message.contains(custom)) return message
		return wordRegex(custom).replace(message) { realName() }
	}

	@JvmStatic
	fun replaceComponentIfNeeded(component: Component?): Component? {
		if (component == null) return null
		val regex = displayPattern() ?: return null
		if (!regex.containsMatchIn(component.string)) return null
		return transform(component, regex, customName())
	}

	private fun transform(component: Component, regex: Regex, replacement: String): MutableComponent {
		val text = (component.contents as? PlainTextContents)?.text()
		val out = if (text != null && regex.containsMatchIn(text)) {
			Component.empty().also { appendReplaced(it, text, regex, replacement) }
		} else {
			component.plainCopy()
		}
		out.setStyle(component.style)
		component.siblings.forEach { out.append(transform(it, regex, replacement)) }
		return out
	}

	private fun appendReplaced(out: MutableComponent, text: String, regex: Regex, replacement: String) {
		var start = 0
		for (match in regex.findAll(text)) {
			if (match.range.first > start) out.append(Component.literal(text.substring(start, match.range.first)))
			out.append(Component.literal(replacement))
			start = match.range.last + 1
		}
		if (start < text.length) out.append(Component.literal(text.substring(start)))
	}

	@JvmStatic
	fun replaceSequenceIfNeeded(text: FormattedCharSequence): FormattedCharSequence {
		val regex = displayPattern() ?: return text
		// A cheap first pass: this runs for every text sequence drawn, and almost none of them
		// mention the name, so the line is flattened once into a single builder and dropped
		// when it holds no match. The styled rebuild below only runs for the few that do, which
		// keeps the per-frame allocation - and the GC pauses it caused - off the render path.
		val full = StringBuilder()
		text.accept { _, _, cp ->
			full.appendCodePoint(cp)
			true
		}
		if (!regex.containsMatchIn(full)) return text
		val replacement = customName()

		val styles = ArrayList<Style>()
		val texts = ArrayList<String>()
		var curStyle: Style? = null
		val cur = StringBuilder()
		text.accept { _, style, cp ->
			if (curStyle != null && style != curStyle) {
				styles.add(curStyle!!)
				texts.add(cur.toString())
				cur.setLength(0)
			}
			curStyle = style
			cur.appendCodePoint(cp)
			true
		}
		if (cur.isNotEmpty()) {
			styles.add(curStyle ?: Style.EMPTY)
			texts.add(cur.toString())
		}

		val root = Component.empty()
		for (i in texts.indices) {
			val holder = Component.empty().setStyle(styles[i])
			appendReplaced(holder, texts[i], regex, replacement)
			root.append(holder)
		}
		return root.visualOrderText
	}
}
