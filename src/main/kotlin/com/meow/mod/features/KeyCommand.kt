package com.meow.mod.features

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.meow.mod.events.MeowEvents
import com.meow.mod.skyblock.DungeonUtils
import com.meow.mod.ui.KeybindsScreen
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

object KeyCommand : Feature(
	"keyCommand",
	"Bind any keyboard key to a command. Also includes Auto F7 (auto-requeue once the Blood room is cleared).",
	subcategory = "Key Command",
	displayName = "Key Command",
	enabledByDefault = true
) {
	data class Binding(var key: Int, var command: String)

	private val LOGGER = LoggerFactory.getLogger("MeowMod")
	private val gson = Gson()

	private val bindsData = addText(
		"binds",
		"[{\"key\":89,\"command\":\"joindungeon catacombs 7\"},{\"key\":0,\"command\":\"\"}]",
		"Open the editor below to add/remove key -> command bindings.",
		"Bindings"
	)

	private val editButton = addButton(
		"edit",
		"Opens the keybind editor.",
		"Edit Bindings",
		label = "Edit",
		action = {
			Minecraft.getInstance().setScreenAndShow(KeybindsScreen(Minecraft.getInstance().gui.screen()))
		}
	)

	private val settingAutoF7 = addSwitch(
		"autoF7",
		true,
		"Once the Blood room Giant and miniboss are killed, run the Auto F7 command and confirm the join menu. If the dungeon entry message never arrives it sends the command and clicks again; two ESC presses from you stop it.",
		"Auto F7"
	)

	private val settingF7Command = addText(
		"f7Command",
		"joindungeon catacombs 7",
		"The command Auto F7 runs after the Blood room.",
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

	private val giantNames = listOf("giant", "bigfoot", "l.a.s.r")
	private val miniNames = listOf("bonzo", "scarf", "spirit bear", "livid")

	private var triggered = false
	private var pendingTrigger = false
	private var awaitingConfirm = false
	private var confirmCooldown = 0L
	private var escapeAt = 0L
	private var escapeScreen: Screen? = null
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

	private fun tick() {
		if (!enabled) return
		val mc = Minecraft.getInstance()
		val window = mc.getWindow()

		// Never fire bindings while any screen (chat, sign, container, etc.) is open,
		// otherwise typing a bound key would run its command. The key edge is still
		// consumed so closing the screen doesn't immediately fire a held key.
		val canFire = mc.gui.screen() == null
		for (binding in bindings) {
			if (binding.key <= 0 || binding.command.isBlank()) continue
			val down = InputConstants.isKeyDown(window, binding.key)
			val prev = wasDown[binding.key] ?: false
			wasDown[binding.key] = down
			if (down && !prev && canFire) runCommand(binding.command)
		}

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

		// The join was clicked but the dungeon entry message never showed up, so the
		// requeue did not take: send the command and click the icon again.
		if (requeueing && !awaitingConfirm && enterDeadline > 0L && System.currentTimeMillis() > enterDeadline) {
			retryRequeue(mc, "no dungeon entry message")
		}

		// Once the confirm button has been clicked, close the leftover menu with ESC
		// so the player isn't stuck in it. Only close if that exact menu is still open.
		if (escapeScreen != null && System.currentTimeMillis() >= escapeAt) {
			val screen = escapeScreen
			escapeScreen = null
			if (screen != null && mc.gui.screen() === screen) screen.onClose()
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
			if (seenNames.add(name)) LOGGER.info("[MeowMod] AutoF7: saw entity \"$name\"")
			val lower = name.lowercase()
			if (giantNames.any { lower.contains(it) }) giantAlive = true
			if (miniNames.any { lower.contains(it) }) miniAlive = true
		}

		if (giantAlive) giantSeen = true
		if (miniAlive) miniSeen = true

		if (giantSeen && miniSeen && !giantAlive && !miniAlive) {
			triggered = true
			pendingTrigger = true
			LOGGER.info("[MeowMod] AutoF7: giant + miniboss killed, requeuing")
		}
	}

	private fun resetAutoF7() {
		triggered = false
		pendingTrigger = false
		awaitingConfirm = false
		escapeScreen = null
		escapeAt = 0L
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
		LOGGER.info("[MeowMod] AutoF7: requeue attempt $requeueAttempts")
		sendRequeueCommand(mc)
	}

	// Failsafe retry: same as a fresh attempt, but bounded so we never spam forever.
	private fun retryRequeue(mc: Minecraft, why: String) {
		if (requeueAttempts >= MAX_REQUEUE_ATTEMPTS) {
			LOGGER.info("[MeowMod] AutoF7: giving up after $requeueAttempts attempts ($why)")
			stopRequeue()
			return
		}
		requeueAttempts++
		enterDeadline = 0L
		awaitingConfirm = false
		loggedScreen = null
		LOGGER.info("[MeowMod] AutoF7: retry $requeueAttempts ($why)")
		sendRequeueCommand(mc)
	}

	// Sends the requeue command and waits for the join menu to appear. The delay scales
	// with ping (300-500ms) so the menu has time to render before we look for the icon.
	private fun sendRequeueCommand(mc: Minecraft) {
		runCommand(settingF7Command.get())
		awaitingConfirm = true
		confirmCooldown = System.currentTimeMillis() + 300L + pingMillis(mc)
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
				LOGGER.info("[MeowMod] AutoF7: player pressed ESC twice, stopping requeue")
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
		LOGGER.info("[MeowMod] AutoF7: requeue confirmed (\"$text\")")
		stopRequeue()
	}

	private fun confirmMenu(mc: Minecraft) {
		if (System.currentTimeMillis() > confirmCooldown + 6000L) {
			LOGGER.info("[MeowMod] AutoF7: timed out waiting for join menu")
			awaitingConfirm = false
			retryRequeue(mc, "join menu never appeared")
			return
		}
		val screen = mc.gui.screen() ?: return
		if (screen !== loggedScreen) {
			loggedScreen = screen
			val container = screen as? AbstractContainerScreen<*>
			if (container == null) {
				LOGGER.info("[MeowMod] AutoF7: screen ${screen.javaClass.simpleName} (not a container)")
			} else {
				LOGGER.info("[MeowMod] AutoF7: menu \"${container.title.string}\" slots=${container.menu.slots.size}")
				for (i in container.menu.slots.indices) {
					val stack = container.menu.slots[i].item
					if (stack.isEmpty) continue
					LOGGER.info("[MeowMod] AutoF7:   slot $i = ${stack.item} \"${stack.hoverName.string}\"")
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
		LOGGER.info("[MeowMod] AutoF7: clicked join button at slot $target")
		awaitingConfirm = false
		// Wait for Hypixel's "entered The Catacombs" message; if it never comes the
		// requeue is retried (see tick()).
		enterDeadline = System.currentTimeMillis() + ENTER_TIMEOUT
		// Press ESC shortly after so we don't stay stuck in the menu if it didn't close itself.
		escapeScreen = container
		escapeAt = System.currentTimeMillis() + 500L
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
