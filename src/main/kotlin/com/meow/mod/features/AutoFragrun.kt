package com.meow.mod.features

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.meow.mod.Access
import com.meow.mod.api.ItemUtils
import com.meow.mod.api.humanDelay
import com.meow.mod.events.MeowEvents
import com.meow.mod.skyblock.DungeonUtils
import com.meow.mod.ui.KeybindsScreen
import com.meow.mod.ui.ModuleKeysScreen
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Items
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList

// The module is the frag run itself: the mob scan, the requeue command and the confirm click.
// The keys that run commands, and both key editors, are listed in General instead - that is
// where the player looks for their keys, and they work whatever this module is set to.
object AutoFragrun : Feature(
	"keyCommand",
	"Requeues F7 after the Blood room.",
	subcategory = "Auto Fragrun",
	displayName = "Auto Fragrun",
	enabledByDefault = true
) {
	data class Binding(var key: Int, var command: String)

	private val LOGGER = LoggerFactory.getLogger("MeowMod")
	private val gson = Gson()

	// Command keys are not the requeue, so they have a switch of their own: turning Auto Fragrun
	// off must not take the player's command keys away with it.
	private val bindsEnabled = addSwitch(
		"bindsEnabled",
		true,
		"Fires the command keys.",
		"Command Keys",
		subcategory = "General",
		heading = "Keybinds"
	)

	private val bindsData = addText(
		"binds",
		"[{\"key\":89,\"command\":\"joindungeon catacombs 7\"}]",
		"Key to command bindings.",
		"Command Binds",
		subcategory = "General",
		heading = "Keybinds"
	)

	private val editButton = addButton(
		"edit",
		"Edit the command keys.",
		"Edit Command Binds",
		label = "Edit",
		subcategory = "General",
		heading = "Keybinds",
		action = {
			Minecraft.getInstance().setScreenAndShow(KeybindsScreen(Minecraft.getInstance().gui.screen()))
		}
	)

	private val moduleKeysButton = addButton(
		"moduleKeys",
		"Bind a key to any module.",
		"Module Keybinds",
		label = "Edit",
		subcategory = "General",
		heading = "Keybinds",
		action = {
			Minecraft.getInstance().setScreenAndShow(ModuleKeysScreen(Minecraft.getInstance().gui.screen()))
		}
	)

	private val settingAutoF7 = addSwitch(
		"autoF7",
		true,
		"Requeue after the Blood room.",
		"Auto F7"
	)

	private val settingF7Command = addText(
		"f7Command",
		"joindungeon catacombs 7",
		"Command run after the Blood room.",
		"Auto F7 Command"
	)

	val bindings = CopyOnWriteArrayList<Binding>()

	@Volatile
	private var enabled = false

	private val wasDown = HashMap<Int, Boolean>()

	// How long to wait for the dungeon entry message after clicking join, and how many
	// times the requeue may be retried before we give up.
	private const val ENTER_TIMEOUT = 5000L
	private const val MAX_REQUEUE_ATTEMPTS = 5

	// A party that is queued sits on Hypixel's "Creating instance..." map (the screen advertises
	// something like 14 seconds) and the entry message only arrives once that is done, so the
	// wait for it is refreshed for as long as that map is open.
	private const val QUEUE_TIMEOUT = 60000L

	// Every fifth reopen is logged, so a queue that keeps fighting back is visible in the log
	// without filling it.
	private const val CLOSE_MENU_LOG_EVERY = 5

	// How long after the join click the queue menu is still looked for. Hypixel can swap it in
	// after our first close, so the search does not stop the moment one menu has been closed.
	private const val QUEUE_GRACE = 10000L

	private val giantNames = listOf("giant", "bigfoot", "l.a.s.r")
	private val miniNames = listOf("bonzo", "scarf", "spirit bear", "livid")

	private var triggered = false
	private var pendingTrigger = false
	private var awaitingConfirm = false
	private var confirmCooldown = 0L
	private var escapeAt = 0L
	private var escapeScreen: Screen? = null
	private var escapeArmed = false
	private var escapeAttempts = 0
	private var escapeUntil = 0L

	// Set once the instance map shows up: the party is queued, so the command must not be
	// sent again and the entry message is worth waiting for.
	private var queued = false
	private var giantSeen = false
	private var miniSeen = false
	private val seenNames = HashSet<String>()
	private var loggedScreen: Any? = null

	// Failsafe state: the requeue only counts as done once Hypixel announces that we
	// entered the dungeon. Until then the command is re-sent and the join icon clicked
	// again, unless the player backs out themselves (two ESC presses).
	private val enteredPattern = Regex("entered The Catacombs", RegexOption.IGNORE_CASE)
	private var requeueing = false
	private var requeueAttempts = 0
	private var enterDeadline = 0L
	private var escapePresses = 0
	private var escapeDown = false

	init {
		bindsData.onChange { parse(it) }
		parse(bindsData.get())
	}

	fun initialize() {
		ClientTickEvents.END_CLIENT_TICK.register { tick() }
		MeowEvents.onWorldLoad { resetAutoF7() }
		ClientReceiveMessageEvents.GAME.register { message, _ -> onGameMessage(message.string) }
		ClientReceiveMessageEvents.CHAT.register { message, _, _, _, _ -> onGameMessage(message.string) }
	}

	override fun onEnable() {
		enabled = true
	}

	override fun onDisable() {
		enabled = false
		wasDown.clear()
		resetAutoF7()
	}

	fun save() {
		bindsData.set(gson.toJson(bindings))
	}

	// The command a key runs, if it runs one. The module keys leave a key that is already a
	// command key alone: binding a module onto it must never quietly take the command away.
	fun commandForKey(key: Int): String? =
		bindings.firstOrNull { it.key == key && it.command.isNotBlank() }?.command

	private fun tick() {
		val mc = Minecraft.getInstance()
		val window = mc.getWindow()

		// Never fire bindings while any screen (chat, sign, container, etc.) is open,
		// otherwise typing a bound key would run its command. The key edge is still
		// consumed so closing the screen doesn't immediately fire a held key. The command
		// keys are their own switch, so they run even with the requeue turned off.
		if (Access.authorized && bindsEnabled.get()) {
			val canFire = mc.gui.screen() == null
			for (binding in bindings) {
				if (binding.key <= 0 || binding.command.isBlank()) continue
				val down = InputConstants.isKeyDown(window, binding.key)
				val prev = wasDown[binding.key] ?: false
				wasDown[binding.key] = down
				if (down && !prev && canFire) runCommand(binding.command)
			}
		}

		if (!enabled) return

		if (settingAutoF7.get() && !triggered) scanBloodMobs(mc)

		if (requeueing) trackPlayerEscape(mc)

		if (pendingTrigger) {
			pendingTrigger = false
			loggedScreen = null
			startRequeue(mc)
		}

		if (awaitingConfirm && System.currentTimeMillis() >= confirmCooldown) {
			confirmMenu(mc)
		}

		// The join was clicked but the dungeon entry message never showed up, so the requeue
		// did not take: send the command and click the icon again. While the instance map is
		// open the party is queued but not in yet, so the wait is refreshed instead - sending
		// the command again there would only start a second queue.
		if (requeueing && !awaitingConfirm && enterDeadline > 0L && System.currentTimeMillis() > enterDeadline) {
			if (isInstanceMap(mc.gui.screen())) {
				noteQueued()
				enterDeadline = System.currentTimeMillis() + QUEUE_TIMEOUT
			} else {
				retryRequeue(mc, "no dungeon entry message")
			}
		}

		// Once the confirm button has been clicked, close the leftover menu with ESC so the
		// player isn't stuck in it. The join menu turns into Hypixel's instance map while the
		// party is set up, and Hypixel replaces that menu with a new screen, so the map itself
		// is the clue that this is still the queue menu: matching only the screen that was
		// clicked in leaves it open on the "Creating instance..." map.
		if (escapeArmed && System.currentTimeMillis() >= escapeAt) {
			val menu = mc.gui.screen() as? AbstractContainerScreen<*>
			if (isInstanceMap(menu)) noteQueued()
			val queueMenu = menu != null && (menu === escapeScreen || isInstanceMap(menu))
			if (queueMenu) {
				escapeAttempts++
				if (escapeAttempts == 1) {
					LOGGER.info("[MeowMod] Auto Fragrun: closing the queue menu")
				} else if (escapeAttempts % CLOSE_MENU_LOG_EVERY == 0) {
					LOGGER.info("[MeowMod] Auto Fragrun: the queue menu keeps coming back, closing it again (attempt $escapeAttempts)")
				}
				menu.onClose()
			}
			// Hypixel reopens the instance map while the instance is being made, so it is held
			// closed for as long as the party is queued and released the moment we are in.
			escapeArmed = requeueing && (queued || queueMenu || System.currentTimeMillis() < escapeUntil)
			escapeAt = System.currentTimeMillis() + humanDelay(600L)
		}
	}

	// Vanilla-reported ping in ms, clamped so the requeue delay stays within 300-500ms.
	private fun pingMillis(mc: Minecraft): Long {
		val player = mc.player ?: return 0L
		val info = player.connection.getPlayerInfo(player.uuid) ?: return 0L
		return info.latency.coerceIn(0, 200).toLong()
	}

	// Frag/blood running: the relevant mobs are one Sadan giant and one previous-boss
	// miniboss. Once both have spawned and are gone, the room is done and we requeue.
	private fun scanBloodMobs(mc: Minecraft) {
		if (!DungeonUtils.inDungeons) return
		val player = mc.player ?: return
		val level = mc.level ?: return

		var giantAlive = false
		var miniAlive = false
		val area = player.boundingBox.inflate(48.0)
		for (entity in level.getEntities(player, area) { it !== player }) {
			val name = entity.name.string
			if (name.isEmpty()) continue
			if (seenNames.add(name)) LOGGER.info("[MeowMod] Auto Fragrun: saw entity \"$name\"")
			val lower = name.lowercase()
			if (giantNames.any { lower.contains(it) }) giantAlive = true
			if (miniNames.any { lower.contains(it) }) miniAlive = true
		}

		if (giantAlive) giantSeen = true
		if (miniAlive) miniSeen = true

		if (giantSeen && miniSeen && !giantAlive && !miniAlive) {
			triggered = true
			pendingTrigger = true
			LOGGER.info("[MeowMod] Auto Fragrun: giant + miniboss killed, requeuing")
		}
	}

	private fun resetAutoF7() {
		triggered = false
		pendingTrigger = false
		awaitingConfirm = false
		escapeScreen = null
		escapeAt = 0L
		escapeArmed = false
		escapeAttempts = 0
		escapeUntil = 0L
		queued = false
		giantSeen = false
		miniSeen = false
		seenNames.clear()
		loggedScreen = null
		requeueing = false
		requeueAttempts = 0
		enterDeadline = 0L
		escapePresses = 0
		escapeDown = false
	}

	// Runs the requeue command and arms the confirm click.
	private fun startRequeue(mc: Minecraft) {
		requeueing = true
		requeueAttempts = 1
		escapePresses = 0
		enterDeadline = 0L
		awaitingConfirm = false
		loggedScreen = null
		queued = false
		escapeAttempts = 0
		LOGGER.info("[MeowMod] Auto Fragrun: requeue attempt $requeueAttempts")
		sendRequeueCommand(mc)
	}

	// Failsafe retry: same as a fresh attempt, but bounded so we never spam forever.
	private fun retryRequeue(mc: Minecraft, why: String) {
		if (requeueAttempts >= MAX_REQUEUE_ATTEMPTS) {
			LOGGER.info("[MeowMod] Auto Fragrun: giving up after $requeueAttempts attempts ($why)")
			stopRequeue()
			return
		}
		requeueAttempts++
		enterDeadline = 0L
		awaitingConfirm = false
		loggedScreen = null
		queued = false
		escapeAttempts = 0
		LOGGER.info("[MeowMod] Auto Fragrun: retry $requeueAttempts ($why)")
		sendRequeueCommand(mc)
	}

	// Sends the requeue command and waits for the join menu to appear. The delay scales
	// with ping (300-500ms) so the menu has time to render before we look for the icon.
	private fun sendRequeueCommand(mc: Minecraft) {
		runCommand(settingF7Command.get())
		awaitingConfirm = true
		confirmCooldown = System.currentTimeMillis() + humanDelay(300L + pingMillis(mc))
	}

	private fun stopRequeue() {
		requeueing = false
		awaitingConfirm = false
		enterDeadline = 0L
		escapePresses = 0
	}

	// Two real ESC presses while the requeue is in flight mean the player closed the
	// menu themselves, so we stop sending the command. Our own programmatic onClose()
	// does not create a key press, so it never counts here.
	private fun trackPlayerEscape(mc: Minecraft) {
		val down = InputConstants.isKeyDown(mc.getWindow(), InputConstants.KEY_ESCAPE)
		if (down && !escapeDown) {
			escapePresses++
			if (escapePresses >= 2) {
				LOGGER.info("[MeowMod] Auto Fragrun: player pressed ESC twice, stopping requeue")
				stopRequeue()
			}
		}
		escapeDown = down
	}

	// Hypixel announces "<name> entered The Catacombs, Floor VII!" once the requeue
	// actually worked, so that is our success signal.
	private fun onGameMessage(text: String) {
		if (!enabled || !requeueing) return
		if (!enteredPattern.containsMatchIn(text)) return
		val name = Minecraft.getInstance().user.name
		if (name.isNotEmpty() && !text.contains(name)) return
		LOGGER.info("[MeowMod] Auto Fragrun: requeue confirmed (\"$text\")")
		stopRequeue()
	}

	private fun confirmMenu(mc: Minecraft) {
		if (System.currentTimeMillis() > confirmCooldown + 6000L) {
			LOGGER.info("[MeowMod] Auto Fragrun: timed out waiting for join menu")
			awaitingConfirm = false
			retryRequeue(mc, "join menu never appeared")
			return
		}
		val screen = mc.gui.screen() ?: return
		if (screen !== loggedScreen) {
			loggedScreen = screen
			val container = screen as? AbstractContainerScreen<*>
			if (container == null) {
				LOGGER.info("[MeowMod] Auto Fragrun: screen ${screen.javaClass.simpleName} (not a container)")
			} else {
				LOGGER.info("[MeowMod] Auto Fragrun: menu \"${container.title.string}\" slots=${container.menu.slots.size}")
				for (i in container.menu.slots.indices) {
					val stack = container.menu.slots[i].item
					if (stack.isEmpty) continue
					LOGGER.info("[MeowMod] Auto Fragrun:   slot $i = ${stack.item} \"${stack.hoverName.string}\"")
				}
			}
		}

		val container = screen as? AbstractContainerScreen<*> ?: return
		val player = mc.player ?: return
		val menu = container.menu

		// The confirmation button is named "Undersized party!" ("Click to play anyway!").
		val target = menu.slots.indices.firstOrNull { i ->
			val stack = menu.slots[i].item
			if (stack.isEmpty) return@firstOrNull false
			val name = stack.hoverName.string.lowercase()
			name.contains("undersized") || name.contains("play anyway")
		} ?: menu.slots.indices.firstOrNull { i ->
			val stack = menu.slots[i].item
			!stack.isEmpty && stack.item == Items.PLAYER_HEAD
		} ?: return

		mc.gameMode?.handleContainerInput(menu.containerId, target, 0, ContainerInput.PICKUP, player)
		LOGGER.info("[MeowMod] Auto Fragrun: clicked join button at slot $target")
		awaitingConfirm = false
		// Wait for Hypixel's "entered The Catacombs" message; if it never comes the
		// requeue is retried (see tick()).
		enterDeadline = System.currentTimeMillis() + ENTER_TIMEOUT
		// Press ESC shortly after so we don't stay stuck in the menu if it didn't close itself.
		escapeScreen = container
		escapeArmed = true
		escapeAttempts = 0
		escapeUntil = System.currentTimeMillis() + QUEUE_GRACE
		escapeAt = System.currentTimeMillis() + humanDelay(500L)
	}

	// The instance map showing up means the queue was accepted: from then on the entry
	// message is worth waiting for and the command must not be sent again.
	private fun noteQueued() {
		if (queued) return
		queued = true
		enterDeadline = System.currentTimeMillis() + QUEUE_TIMEOUT
		LOGGER.info("[MeowMod] Auto Fragrun: party is queued, waiting for the dungeon entry")
	}

	// Hypixel swaps the join menu for a map that counts the instance down - "Creating
	// instance...", "Opening The Catacombs for your party!", "Estimated: N seconds" - which is
	// both the sign that the party is queued and the menu that has to be closed.
	private fun isInstanceMap(screen: Screen?): Boolean {
		val container = screen as? AbstractContainerScreen<*> ?: return false
		for (slot in container.menu.slots) {
			val stack = slot.item
			if (stack.isEmpty) continue
			if (stack.item != Items.MAP && stack.item != Items.FILLED_MAP) continue
			val lines = listOf(stack.hoverName.string) + ItemUtils.lore(stack)
			if (lines.any {
					it.contains("Creating instance", ignoreCase = true) ||
						it.contains("Opening The Catacombs", ignoreCase = true) ||
						it.contains("Estimated:", ignoreCase = true)
				}
			) return true
		}
		return false
	}

	private fun runCommand(raw: String) {
		val player = Minecraft.getInstance().player ?: return
		player.connection.sendCommand(raw.removePrefix("/").trim())
	}

	private fun parse(raw: String) {
		bindings.clear()
		try {
			val array = JsonParser.parseString(raw).asJsonArray
			array.forEach { element ->
				val obj = element.asJsonObject
				val key = obj.get("key")?.asInt ?: 0
				val command = obj.get("command")?.asString ?: ""
				bindings.add(Binding(key, command))
			}
		} catch (_: Exception) {
		}
	}
}
