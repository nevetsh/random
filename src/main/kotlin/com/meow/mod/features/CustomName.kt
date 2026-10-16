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
	"Shows a custom name instead of yours, and redirects those commands.",
	subcategory = "General",
	displayName = "Custom Name",
	enabledByDefault = true
) {
	private val settingName = addText(
		"name",
		"femboy",
		"Shown instead of your username; commands typed with it are redirected.",
		"Name"
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
	fun replaceComponentIfNeeded(component: Component): Component? {
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
		if (texts.none { regex.containsMatchIn(it) }) return text

		val root = Component.empty()
		for (i in texts.indices) {
			val holder = Component.empty().setStyle(styles[i])
			appendReplaced(holder, texts[i], regex, replacement)
			root.append(holder)
		}
		return root.visualOrderText
	}
}
