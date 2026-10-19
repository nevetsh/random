package com.meow.mod.features

import com.meow.mod.api.ChatUtils
import com.meow.mod.api.ScreenUtils
import com.meow.mod.api.noControlCodes
import com.meow.mod.ui.ColorPalette
import com.meow.mod.ui.Render2D
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.tags.ItemTags
import net.minecraft.world.item.AxeItem
import net.minecraft.world.item.BowItem
import net.minecraft.world.item.CrossbowItem
import net.minecraft.world.item.FishingRodItem
import net.minecraft.world.item.HoeItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory
import kotlin.random.Random

// The blacksmith's reforge helper: type the reforge you want into the panel at the left of
// the reforge menu and the anvil is clicked until the item above it carries that prefix -
// "Fabled Bouquet of Lies" has "fabled", and that is the whole test: the typed word
// lowercased against the first word of the item's name. Nothing else about the item is
// read, and nothing about it, the typed reforge, or the clicks leaves the client - there
// is no telemetry here, the only logs name slots and reforge words.
//
// The panel also lists the basic reforges (the coin ones from the Blacksmith, not the
// stones) for the kind of item in the slot, and clicking one types it. Which list shows
// follows the item: armor gets the armor words, a bow the ranged ones, and so on.
object AutoReforge : Feature(
	"autoReforge",
	"Types your reforge, clicks the anvil.",
	subcategory = "Automation",
	heading = "Auto Reforge",
	displayName = "Auto Reforge"
) {
	private val settingDelay = addSlider(
		"delay", 300.0, 50.0, 1000.0,
		"Delay after the item updates.", "Click Delay (ms)"
	)

	private val settingJitter = addSlider(
		"jitter", 50.0, 0.0, 500.0,
		"Plus or minus on the delay.", "Jitter (ms)"
	)

	private val settingUiClicks = addSwitch(
		"uiClicks",
		true,
		"Only click again after the menu changes.",
		"UI-Based Clicks"
	)

	private val LOGGER = LoggerFactory.getLogger("MeowMod")

	// The reforge being chased, exactly as typed: matching ignores case, as asked.
	private var wanted = ""
	private var focused = false
	private var nextClick = 0L
	private var announced = false

	// Change detection: the item's name when the anvil was last clicked, and when. Hypixel
	// rewrites the item's prefix on a successful reforge, so the name changing is the game
	// answering the click. The click delay only starts once that answer has been seen, so the
	// anvil is never clicked faster than it can reply - no "Wait a moment" refusals, no spam.
	private var awaitingChange = false
	private var clickedName: String? = null
	private var clickAt = 0L

	// Where the panel drew itself last frame, so a click in it is recognised. Cleared at the
	// top of every draw: a panel that is no longer on screen must never eat a click.
	private var boxRect: Rect? = null
	private var hintRows: List<Pair<Rect, String>> = emptyList()

	@Volatile
	private var enabled = false
	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		ClientTickEvents.START_CLIENT_TICK.register {
			if (enabled) tick()
		}
	}

	// ---------------------------------------------------------------- the click loop --

	private fun tick() {
		val screen = Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*>
		if (screen == null) {
			resetWait()
			return
		}
		val items = screen.menu.items
		val anvil = anvilSlot(items) ?: return
		val above = items.getOrNull(anvil - 9)
		if (above == null || above.isEmpty) {
			// The slot empties for a moment while the reforge is applied. That says nothing
			// either way, so the wait is left as it is rather than restarted.
			announced = false
			return
		}
		val reforge = reforgeOf(above) ?: run {
			announced = false
			return
		}
		val want = wanted.trim()
		if (want.isNotEmpty() && reforge.equals(want, ignoreCase = true)) {
			if (!announced) {
				announced = true
				LOGGER.info("[MeowMod] AutoReforge: \"{}\" reached \"{}\"", want, reforge)
				ChatUtils.sendMessage(
					"&aAuto Reforge: &f${above.hoverName.string.noControlCodes()}&a is now &f$reforge&a.",
					true
				)
			}
			resetWait()
			return
		}
		announced = false
		if (want.isEmpty()) {
			resetWait()
			return
		}

		val now = System.currentTimeMillis()
		val name = above.hoverName.string.noControlCodes().trim()
		val gated = settingUiClicks.get()

		// A click has been sent and the game has not answered yet: wait for the name to change.
		// A reforge that rolls the same prefix twice reads as no change at all, so a click left
		// unanswered for too long is taken as answered rather than stalling the loop for good.
		// With UI-Based Clicks turned off there is no answer to wait on, so the delay paces it.
		if (gated && awaitingChange) {
			if (name != clickedName || now - clickAt >= CHANGE_TIMEOUT_MS) {
				awaitingChange = false
				nextClick = now + settingDelay.get().toLong() + jitter()
			}
			return
		}
		awaitingChange = false

		if (now < nextClick) return
		LOGGER.info("[MeowMod] AutoReforge: clicking the anvil (have \"{}\", want \"{}\")", reforge, want)
		ScreenUtils.click(anvil, false)
		if (gated) {
			awaitingChange = true
			clickedName = name
			clickAt = now
		} else {
			nextClick = now + settingDelay.get().toLong() + jitter()
		}
	}

	// The click delay plus or minus the jitter, so the gap after an answer is never the same
	// number twice and swings both ways around the configured delay.
	private fun jitter(): Long {
		val max = settingJitter.get().toLong()
		return if (max <= 0L) 0L else Random.nextLong(-max, max + 1L)
	}

	private fun resetWait() {
		awaitingChange = false
		clickedName = null
		clickAt = 0L
	}

	// The reforge button of the menu, an anvil that names itself "Reforge Item".
	private fun anvilSlot(items: List<ItemStack>): Int? =
		items.indices.firstOrNull {
			val stack = items[it]
			stack.item == Items.ANVIL && stack.hoverName.string.contains("Reforge", ignoreCase = true)
		}

	// The reforge an item already carries: the first word of its name, which is where
	// Hypixel puts the prefix. Null when there is no name to read.
	private fun reforgeOf(stack: ItemStack): String? =
		stack.hoverName.string.noControlCodes().trim().split(' ').firstOrNull { it.isNotEmpty() }

	// ---------------------------------------------------------------- the side panel --

	@JvmStatic
	fun renderOverlay(
		screen: AbstractContainerScreen<*>,
		graphics: GuiGraphicsExtractor,
		mouseX: Int,
		mouseY: Int,
		left: Int,
		top: Int
	) {
		boxRect = null
		hintRows = emptyList()
		val items = screen.menu.items
		val anvil = anvilSlot(items) ?: return
		val above = items.getOrNull(anvil - 9)

		val x = (left - PANEL_W - 8).coerceAtLeast(6)
		val y = top + 4

		// The search box: what is being looked for, and the caret while it is being typed.
		val box = Rect(x + 4, y + 17, PANEL_W - 8, 12)
		val hints = hintsFor(above)
		val shownHints = if (hints.size > MAX_HINT_ROWS) hints.take(MAX_HINT_ROWS) else hints

		// One line of state, and only while there is state to report: with nothing typed
		// and nothing happening the panel is just the header and the box.
		val status = when {
			announced && above != null && !above.isEmpty ->
				"Reached: ${reforgeOf(above)}" to ColorPalette.argb(ColorPalette.ENABLED)

			wanted.isNotBlank() && above != null && !above.isEmpty ->
				"Clicking the anvil..." to ColorPalette.argb(ColorPalette.ACCENT)

			else -> null
		}
		val baseH = if (status != null) 46 else 33
		val panelH = baseH + (if (hints.isEmpty()) 0 else 10 + shownHints.size * 9) + 8

		Render2D.rect(graphics, x, y, PANEL_W, panelH, ColorPalette.PRIMARY)
		Render2D.outline(graphics, x, y, PANEL_W, panelH, ColorPalette.argb(ColorPalette.OUTLINE))
		Render2D.text(
			graphics, "Auto Reforge", x + 5, y + 5,
			ColorPalette.argb(ColorPalette.TEXT), false, 1f
		)

		Render2D.rect(graphics, box.x, box.y, box.w, box.h, ColorPalette.TERTIARY)
		Render2D.outline(
			graphics, box.x, box.y, box.w, box.h,
			ColorPalette.argb(if (focused) ColorPalette.ACCENT else ColorPalette.OUTLINE)
		)
		val shown = when {
			focused -> wanted + "_"
			wanted.isEmpty() -> "(type a reforge)"
			else -> wanted
		}
		val boxColor = when {
			focused -> ColorPalette.argb(ColorPalette.ACCENT)
			wanted.isEmpty() -> ColorPalette.argb(ColorPalette.LIGHT_TEXT)
			else -> ColorPalette.argb(ColorPalette.TEXT)
		}
		Render2D.text(graphics, truncate(shown, box.w - 6, 0.9f), box.x + 3, box.y + 3, boxColor, false, 0.9f)
		boxRect = box

		if (status != null) {
			Render2D.text(graphics, truncate(status.first, PANEL_W - 8, 0.8f), x + 4, y + 33, status.second, false, 0.8f)
		}

		// The basic reforges for the kind of item in the slot, each one clickable.
		if (hints.isEmpty()) return
		var hy = if (status != null) y + 46 else y + 33
		Render2D.text(
			graphics, "Hints:", x + 4, hy,
			ColorPalette.argb(ColorPalette.LIGHT_TEXT), false, 0.85f
		)
		hy += 10
		val rows = mutableListOf<Pair<Rect, String>>()
		for (name in shownHints) {
			val row = Rect(x + 4, hy - 1, PANEL_W - 8, 9)
			val hover = row.contains(mouseX, mouseY)
			val selected = wanted.equals(name, ignoreCase = true)
			if (hover) Render2D.rect(graphics, row.x, row.y, row.w, row.h, ColorPalette.OUTLINE2)
			Render2D.text(
				graphics, name, x + 6, hy,
				ColorPalette.argb(if (selected || hover) ColorPalette.ACCENT else ColorPalette.TEXT),
				false, 0.8f
			)
			rows.add(row to name)
			hy += 9
		}
		hintRows = rows
	}

	// A click in the panel: the box takes focus, a hint writes itself into it. A click
	// anywhere else only drops the focus, so the menu underneath still works.
	@JvmStatic
	fun handleMouse(mx: Int, my: Int): Boolean {
		if (!enabled) return false
		val box = boxRect ?: return false
		if (box.contains(mx, my)) {
			focused = true
			return true
		}
		for ((rect, name) in hintRows) {
			if (rect.contains(mx, my)) {
				wanted = name
				focused = false
				LOGGER.info("[MeowMod] AutoReforge: hint picked \"{}\"", name)
				return true
			}
		}
		if (focused) focused = false
		return false
	}

	// Typing into the box, key by key: the box only ever holds a reforge word, so the letters
	// are read straight off their keys. While it has focus every one of those keys - letters,
	// digits, space - is taken, so typing never also closes the menu.
	@JvmStatic
	fun handleKey(key: Int): Boolean {
		if (!enabled || !focused) return false
		when (key) {
			GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
				focused = false
				return true
			}

			GLFW.GLFW_KEY_BACKSPACE -> {
				if (wanted.isNotEmpty()) wanted = wanted.dropLast(1)
				return true
			}

			GLFW.GLFW_KEY_DELETE -> {
				wanted = ""
				return true
			}

			GLFW.GLFW_KEY_SPACE -> {
				if (wanted.length < MAX_LENGTH) wanted += ' '
				return true
			}

			GLFW.GLFW_KEY_MINUS -> {
				if (wanted.length < MAX_LENGTH) wanted += '-'
				return true
			}

			GLFW.GLFW_KEY_APOSTROPHE -> {
				if (wanted.length < MAX_LENGTH) wanted += '\''
				return true
			}

			else -> {
				val letter = when {
					key in GLFW.GLFW_KEY_A..GLFW.GLFW_KEY_Z -> 'a' + (key - GLFW.GLFW_KEY_A)
					key in GLFW.GLFW_KEY_0..GLFW.GLFW_KEY_9 -> '0' + (key - GLFW.GLFW_KEY_0)
					else -> return false
				}
				if (wanted.length < MAX_LENGTH) wanted += letter
				return true
			}
		}
	}

	// ---------------------------------------------------------------------- the lists --

	// Which basic reforges suit the item in the slot: the wiki's own grouping, so armor
	// shows what armor can roll and a bow what bows can roll. Anything unrecognised is
	// shown every combat reforge rather than nothing.
	private fun hintsFor(stack: ItemStack?): List<String> {
		if (stack == null || stack.isEmpty) return emptyList()
		// Armor, swords and pickaxes are identified by their tags: the item classes these
		// lists used to be read from are gone in current versions, and the tags are what
		// the game itself decides them by.
		return when {
			stack.`is`(ItemTags.HEAD_ARMOR) || stack.`is`(ItemTags.CHEST_ARMOR) ||
				stack.`is`(ItemTags.LEG_ARMOR) || stack.`is`(ItemTags.FOOT_ARMOR) -> ARMOR

			stack.`is`(ItemTags.SWORDS) || stack.item is FishingRodItem -> MELEE
			stack.item is BowItem || stack.item is CrossbowItem -> RANGED
			stack.item is AxeItem -> AXES
			stack.item is HoeItem -> HOES
			stack.`is`(ItemTags.PICKAXES) -> PICKAXES
			else -> (MELEE + RANGED + ARMOR).distinct()
		}
	}

	private data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
		fun contains(mx: Int, my: Int): Boolean = mx >= x && mx < x + w && my >= y && my < y + h
	}

	private fun truncate(text: String, maxPx: Int, scale: Float): String {
		if (maxPx <= 0) return ""
		if ((Render2D.width(text) * scale).toInt() <= maxPx) return text
		var end = text.length
		while (end > 0 && (Render2D.width(text.substring(0, end)) * scale).toInt() > maxPx) end--
		return text.substring(0, end)
	}

	override fun onEnable() {
		enabled = true
		focused = false
		announced = false
		nextClick = 0L
		resetWait()
	}

	override fun onDisable() {
		enabled = false
		focused = false
		announced = false
		resetWait()
	}

	// The basic (coin) reforges, per the Hypixel wiki's Reforging tables: reforges that
	// come from stones - Ancient, Warped, Fabled, Withered and the rest - are not here,
	// because the Blacksmith menu cannot roll them.
	private val MELEE = listOf(
		"Gentle", "Odd", "Fast", "Fair", "Epic", "Sharp", "Heroic", "Spicy", "Legendary"
	)
	private val RANGED = listOf(
		"Deadly", "Fine", "Grand", "Hasty", "Neat", "Rapid", "Unreal", "Awkward", "Rich"
	)
	private val ARMOR = listOf(
		"Clean", "Fierce", "Heavy", "Light", "Mythic", "Pure", "Smart", "Titanic", "Wise"
	)
	private val AXES = listOf("Double-Bit", "Lumberjack's", "Great", "Rugged", "Lush")
	private val HOES = listOf("Green Thumb", "Peasant's", "Robust", "Zooming")
	private val PICKAXES = listOf("Unyielding", "Prospector's", "Excellent", "Sturdy", "Fortunate")

	private const val PANEL_W = 100
	private const val MAX_HINT_ROWS = 16
	private const val MAX_LENGTH = 24

	// How long a click is given to change the item's name before it is taken as answered
	// anyway, so a reforge that rolls the same prefix twice cannot stall the loop.
	private const val CHANGE_TIMEOUT_MS = 1000L
}
