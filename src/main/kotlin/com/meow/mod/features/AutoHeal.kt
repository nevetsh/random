package com.meow.mod.features

import com.meow.mod.api.ActionBar
import com.meow.mod.api.PlayerUtils
import com.meow.mod.api.humanDelay
import com.meow.mod.api.noControlCodes
import com.meow.mod.events.MeowEvents
import com.meow.mod.skyblock.LocationUtils
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket
import net.minecraft.world.item.ItemStack
import org.slf4j.LoggerFactory

// Heals the player with a chosen item when Health falls below a threshold. The item is looked
// for on the hotbar; when it is not the one in hand the client switches to its slot, uses it,
// and switches back, so the player keeps whatever they were holding.
//
// How much a cast costs is what decides whether it happens: a wand and the Florid Zombie Sword
// draw 25 Vitality, Wither Impact draws 50 Vitality and 300 Mana. Those pools are read from the
// action bar, and Hypixel puts Vitality there in Mana's place while a Vitality-spending item is
// held - so the bar is read after the switch and before the cast (see ActionBar).
//
// Wither Impact also needs the player looking down, so its cast waits for the pitch to reach
// the aim window before it right-clicks, and gives up and swaps back if the player never does.
object AutoHeal : Feature(
	"autoHeal",
	"Auto-heals with a chosen item.",
	subcategory = "Auto Heal",
	displayName = "Auto Heal",
	privateSwitch = true
) {
	// Off / On / Strict is the module's own control, listed instead of the plain on/off switch.
	// Strict is On plus a lock on the player's own hotbar: the slot the cast is using is held
	// until the heal is finished (see tick()).
	private val settingMode = addChoice(
		"mode",
		MODE_OFF,
		listOf(MODE_OFF, MODE_ON, MODE_STRICT),
		"Heal automatically, strictly, or not at all.", "Mode"
	)
	private val settingItem = addChoice(
		"item",
		Mode.WAND.label,
		Mode.labels,
		"Which item to heal with.", "Item"
	)
	private val settingJitter = addSlider(
		"jitter", 250.0, 0.0, 1000.0,
		"Wait between heals.", "Jitter (ms)"
	)
	private val settingHp = addSlider(
		"hp", 70.0, 1.0, 100.0,
		"Heal below this HP.", "HP Threshold"
	)

	private val LOGGER = LoggerFactory.getLogger("MeowMod")

	// Whether the player's own hotbar is held while a cast is in flight (Strict mode).
	@Volatile
	private var strictMode = false

	// When the world last changed. A lobby swap or a dungeon transfer leaves the action bar
	// stale for a moment, and a stale bar can read as low HP, so no heal is started for a few
	// seconds afterwards (see tick()).
	@Volatile
	private var worldChangedAt = 0L

	init {
		configSwitch.control = settingMode
		settingMode.onChange { onModeChanged(it) }
	}

	// The dropdown is the module's switch: On and Strict are on, Off is off. Driving the real
	// switch keeps the keybind and the enable/disable wiring working unchanged.
	private fun onModeChanged(value: String) {
		strictMode = value == MODE_STRICT
		configSwitch.set(value != MODE_OFF)
	}

	// Where the cast is: waiting, swapped in, gated on the pools, aiming, or swapping back.
	private var step = S_IDLE
	private var nextAt = 0L
	private var originalSlot = -1
	private var targetSlot = -1
	private var swappedAt = 0L
	private var warnedMissingVitality = false

	// The HP the bar showed when the last cast went out, and when it went out. The bar lags the
	// heal, so it can still read the pre-heal number when the module next looks: nothing is cast
	// again until HP has moved past that reading, which is what keeps one low reading from
	// firing the same heal twice.
	private var preCastHp: Double? = null
	private var lastCastAt = 0L

	@Volatile
	private var enabled = false
	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		ClientTickEvents.START_CLIENT_TICK.register { tick() }
		MeowEvents.onWorldLoad {
			reset()
			worldChangedAt = System.currentTimeMillis()
		}
	}

	private fun tick() {
		if (!enabled) return
		val mc = Minecraft.getInstance()
		val player = mc.player ?: return
		// A menu is not a place a heal can be cast from, and the action bar is not being
		// updated while one is open.
		if (mc.gui.screen() != null) return
		if (!LocationUtils.isInSkyblock) return
		if (player.health <= 0f) return

		val now = System.currentTimeMillis()
		// A world change has just happened: the action bar can still be showing the old world's
		// numbers, so nothing is cast until it has settled.
		if (now - worldChangedAt < WORLD_CHANGE_GRACE) return
		// The dropdown is the module's switch; Off means exactly that.
		if (settingMode.get() == MODE_OFF) return
		// Strict: hold the slot the cast is using while it is in flight, so a hotbar switch by
		// the player cannot take the item away mid-cast. The switch is set back locally and
		// announced with the same packet vanilla sends for a hotbar switch - nothing is dropped.
		if (strictMode && (step == S_GATE || step == S_AIM)) {
			val held = targetSlot
			if (held in 0..8 && player.inventory.selectedSlot != held) switchTo(mc, held)
		}
		val mode = mode()

		when (step) {
			S_IDLE -> {
				if (now < nextAt) return
				val hp = ActionBar.hpPercent() ?: return
				if (hp >= settingHp.get()) {
					preCastHp = null
					return
				}
				// The bar lags the heal, so it can still be showing the reading that triggered
				// the last cast. HP has to move past that before another cast, or one low
				// reading fires the same heal twice; a cast that never landed is retried once
				// the wait runs out.
				val previous = preCastHp
				if (previous != null && hp <= previous && now - lastCastAt < POST_CAST_WAIT) return
				val slot = findSlot(mc, mode) ?: return
				preCastHp = null
				targetSlot = slot
				originalSlot = player.inventory.selectedSlot
				swappedAt = now
				if (slot == originalSlot) {
					step = S_GATE
					nextAt = now
					return
				}
				switchTo(mc, slot)
				step = S_GATE
				nextAt = now + SWAP_WAIT
			}

			S_GATE -> {
				if (now < nextAt) return
				if (!affordable(mode)) {
					LOGGER.info(
						"[MeowMod] AutoHeal: not enough resource for {}, swapping back",
						mode.label
					)
					step = S_SWAP_OUT
					nextAt = now
					return
				}
				step = S_AIM
				nextAt = now
			}

			S_AIM -> {
				if (now < nextAt) return
				if (mode == Mode.WITHER_IMPACT && player.xRot < PITCH_MIN) {
					// The cast is held until the player looks down; if they never do, the
					// item is put away rather than kept in hand indefinitely.
					if (now - swappedAt > AIM_TIMEOUT) {
						LOGGER.info("[MeowMod] AutoHeal: no downward aim in time, swapping back")
						step = S_SWAP_OUT
						nextAt = now
					}
					return
				}
				preCastHp = ActionBar.hpPercent()
				lastCastAt = now
				LOGGER.info("[MeowMod] AutoHeal: using {} at {:.0f}% HP", mode.label, preCastHp ?: -1.0)
				PlayerUtils.rightClick()
				step = S_SWAP_OUT
				nextAt = now + CLICK_WAIT
			}

			S_SWAP_OUT -> {
				if (now < nextAt) return
				if (originalSlot in 0..8 && originalSlot != targetSlot) switchTo(mc, originalSlot)
				step = S_IDLE
				nextAt = now + interval()
			}
		}
	}

	private fun mode(): Mode = Mode.fromLabel(settingItem.get())

	// The hotbar slot holding the chosen item, or null when it is not on the hotbar.
	private fun findSlot(mc: Minecraft, mode: Mode): Int? {
		val inventory = mc.player?.inventory ?: return null
		for (slot in 0 until HOTBAR_SLOTS) {
			val stack = inventory.getItem(slot)
			if (stack.isEmpty) continue
			if (mode.matches(nameOf(stack))) return slot
		}
		return null
	}

	// Whether the pools the cast draws from are bigger than its cost, as read from the action
	// bar. A pool the bar says nothing about cannot block the cast: Vitality only appears after
	// the first heal of a session, and a cast that waited for it would never happen.
	private fun affordable(mode: Mode): Boolean {
		val vitality = ActionBar.vitality
		if (vitality == null && !warnedMissingVitality) {
			warnedMissingVitality = true
			LOGGER.info("[MeowMod] AutoHeal: the action bar is not showing Vitality yet")
		}
		if (vitality != null && vitality <= mode.vitalityCost) return false
		val mana = ActionBar.mana
		if (mode.manaCost > 0 && mana != null && mana <= mode.manaCost) return false
		return true
	}

	// The module's slot switch: set locally and announced to the server with exactly the packet
	// vanilla sends for a hotbar switch. Nothing is dropped and nothing is made up.
	private fun switchTo(mc: Minecraft, slot: Int) {
		mc.player?.inventory?.setSelectedSlot(slot)
		mc.connection?.send(ServerboundSetCarriedItemPacket(slot))
	}

	private fun nameOf(stack: ItemStack): String = stack.hoverName.string.noControlCodes()

	private fun interval(): Long = humanDelay(settingJitter.get().toLong()).coerceAtLeast(1L)

	private fun reset() {
		step = S_IDLE
		nextAt = 0L
		originalSlot = -1
		targetSlot = -1
		swappedAt = 0L
		warnedMissingVitality = false
		preCastHp = null
		lastCastAt = 0L
	}

	override fun onEnable() {
		enabled = true
		reset()
	}

	override fun onDisable() {
		enabled = false
		reset()
	}

	// What each item costs to cast, and how a hotbar stack is recognised as it.
	private enum class Mode(val label: String, val vitalityCost: Int, val manaCost: Int) {
		WAND("Wand", 25, 0),
		FLORID("Florid Zombie Sword", 25, 0),
		WITHER_IMPACT("Wither Impact", 50, 300);

		fun matches(name: String): Boolean = when (this) {
			WAND -> WANDS.any { name.contains(it, ignoreCase = true) }
			FLORID -> name.contains(FLORID_NAME, ignoreCase = true)
			WITHER_IMPACT -> BLADES.any { name.contains(it, ignoreCase = true) }
		}

		companion object {
			val labels: List<String> = entries.map { it.label }

			fun fromLabel(label: String): Mode = entries.firstOrNull { it.label == label } ?: WAND

			private const val FLORID_NAME = "Florid Zombie Sword"
			private val WANDS = listOf(
				"Wand of Healing", "Wand of Mending", "Wand of Restoration", "Wand of Atonement"
			)
			private val BLADES = listOf("Hyperion", "Valkyrie", "Astraea", "Scylla")
		}
	}

	private const val S_IDLE = 0
	private const val S_GATE = 1
	private const val S_AIM = 2
	private const val S_SWAP_OUT = 3

	private const val HOTBAR_SLOTS = 9

	// Looking down is a positive pitch: the aim window opens well before straight down.
	private const val PITCH_MIN = 78.0f

	private const val SWAP_WAIT = 100L
	private const val CLICK_WAIT = 100L
	private const val AIM_TIMEOUT = 5000L

	// How long a cast that has not yet moved the action bar's HP reading is given before another
	// is allowed, in case the cast did not land at all.
	private const val POST_CAST_WAIT = 1500L

	// How long after a world change the action bar is left alone, so a stale reading cannot be
	// mistaken for a missing heal.
	private const val WORLD_CHANGE_GRACE = 4000L

	// The dropdown's options, kept as constants so the comparisons are the values themselves.
	private const val MODE_OFF = "Off"
	private const val MODE_ON = "On"
	private const val MODE_STRICT = "Strict"
}
