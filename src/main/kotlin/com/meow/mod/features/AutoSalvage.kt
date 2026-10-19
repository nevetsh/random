package com.meow.mod.features

import com.meow.mod.api.ClickWait
import com.meow.mod.api.ScreenUtils
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.slf4j.LoggerFactory
import kotlin.random.Random

// The blacksmith's salvage run, done one button at a time: the purple wool moves Epic drops
// into the menu and the blue the Rares, then the beacon salvages what is in it, the
// confirmation that follows is clicked, and the menu closes.
//
// The rarities are added highest first. The menu's own slots are limited, and an inventory
// is usually mostly Epics and Rares, so those are what must not be left out - the Epics
// first, then the Rares. Uncommons are not gathered at all.
//
// A click is answered by the menu changing, not by a timer: after a wool is pressed the
// run waits for the menu to actually move something before the next button is touched,
// which is what makes one press per tier - a tier that adds nothing changes nothing, and
// the run either hears the game say so in chat ("You don't have any Rare items in your
// inventory to salvage!") or gives that press one more press after the answer window, in
// case the first was throttled away. It never presses faster than the menu answers, so
// Hypixel's throttle ("Whoa! Slow down there!") has nothing left to fire on.
//
// Every wait between presses is a configured delay plus or minus its jitter. The wool-to-wool
// and wool-to-beacon waits have their own, longer, settings - a beat after the Epics before the
// Rares, and a shorter one after the Rares before the beacon - so the rarities are not rushed.
object AutoSalvage : Feature(
	"autoSalvage",
	"Fills the salvage menu and salvages.",
	subcategory = "Automation",
	heading = "Auto Salvage",
	displayName = "Auto Salvage"
) {
	private val settingDelay = addSlider(
		"delay", 300.0, 50.0, 1000.0,
		"Base wait between the clicks.", "Click Delay (ms)"
	)
	private val settingJitter = addSlider(
		"jitter", 50.0, 0.0, 200.0,
		"Plus or minus on the delay.", "Jitter (ms)"
	)
	private val settingEpicRare = addSlider(
		"epicRare", 750.0, 0.0, 2000.0,
		"Wait after the Epic wool.", "Epic to Rare (ms)"
	)
	private val settingRareSalvage = addSlider(
		"rareSalvage", 500.0, 0.0, 2000.0,
		"Wait after the Rare wool.", "Before Salvage (ms)"
	)
	private val settingUiClicks = addSwitch(
		"uiClicks",
		true,
		"Only click again after the menu changes.",
		"UI-Based Clicks"
	)

	private val LOGGER = LoggerFactory.getLogger("MeowMod")

	// Where the run is: three wools, each followed by a wait for the menu to answer it,
	// then the beacon, its confirmation, and the close. The wools run highest rarity first.
	private var step = S_EPIC
	private var running = false
	private var nextAt = 0L

	// Whether the press that made no change has been pressed a second time already. At most
	// two presses per button - the first, and one more after the answer window - so a tier
	// that has nothing to add is never clicked at a pace the throttle can notice.
	private var retried = false

	// The menu each press was made in, so "answered" means the menu moved (see ClickWait).
	private val clickWait = ClickWait(0L, 0)

	@Volatile
	private var enabled = false
	private var initialized = false

	fun initialize() {
		if (initialized) return
		initialized = true
		ClientTickEvents.START_CLIENT_TICK.register { tick() }
		ClientReceiveMessageEvents.GAME.register { message, _ -> onChat(message.string) }
		ClientReceiveMessageEvents.CHAT.register { message, _, _, _, _ -> onChat(message.string) }
	}

	private fun tick() {
		if (!enabled) return
		val screen = Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*>
		if (screen == null || !screen.title.string.contains(SALVAGE_TITLE, ignoreCase = true)) {
			// The menu the run was working in is gone: it finished, or the player left it.
			if (running) {
				LOGGER.info("[MeowMod] AutoSalvage: the salvage menu closed, run ended at step {}", step)
				reset()
			}
			return
		}
		if (!running) {
			running = true
			step = S_EPIC
			// The first wool is not pressed the moment the menu appears: the run waits its
			// delay first, so it starts at the pace it keeps.
			nextAt = System.currentTimeMillis() + wait()
			retried = false
			LOGGER.info("[MeowMod] AutoSalvage: starting a salvage run in \"{}\"", screen.title.string)
		}

		val now = System.currentTimeMillis()
		if (now < nextAt) return
		wake(screen, now)
	}

	private fun wake(screen: AbstractContainerScreen<*>, now: Long) {
		val items = screen.menu.items
		val own = (screen.menu.slots.size - PLAYER_SLOTS).coerceAtLeast(0)

		when (step) {
			S_EPIC, S_RARE -> {
				val wool = if (step == S_EPIC) Items.WOOL.purple() else Items.WOOL.blue()
				val slot = (0 until own).firstOrNull { items[it].item == wool }
				if (slot == null) {
					// None of that rarity to add, so the wait it would have led to decides.
					step = waitAfter(step)
					nextAt = now
					return
				}
				LOGGER.info("[MeowMod] AutoSalvage: pressing the wool at slot {} (step {})", slot, step)
				// Each wool leads to the next at its own pace: a long beat after the Epics
				// before the Rares, and a shorter one after the Rares before the beacon.
				val pause = if (step == S_EPIC) settingEpicRare.get() else settingRareSalvage.get()
				press(screen, slot)
				step = waitAfter(step)
				nextAt = now + pauseOf(pause)
			}

			S_WAIT_EPIC, S_WAIT_RARE -> {
				// The answer is the menu moving: items arrived, or the button changed. Until
				// it does, nothing happens - the next button is not touched while the last
				// one is still unanswered. With UI-Based Clicks off the delay alone paces it.
				val answered = answered(screen)
				if (!answered && clickWait.since() <= ANSWER_TIMEOUT) return
				if (!answered && !retried) {
					// Nothing moved and the game never said this tier had nothing: the press
					// was probably throttled away, so it is pressed once more - once.
					LOGGER.info("[MeowMod] AutoSalvage: the menu has not moved, pressing step {} again", pressOf(step))
					retried = true
					step = pressOf(step)
					nextAt = now + wait()
					return
				}
				LOGGER.info(
					"[MeowMod] AutoSalvage: {} items in the menu after step {}",
					filled(items, own), step
				)
				step = clickAfter(step)
				// The tier is done with, however it ended, so the next one starts with its
				// own retry still available.
				retried = false
				nextAt = now
			}

			S_BEACON -> {
				val slot = (0 until own).firstOrNull { items[it].item == Items.BEACON }
				if (slot == null) {
					LOGGER.warn("[MeowMod] AutoSalvage: no salvage beacon found, closing instead")
					step = S_CLOSE
					nextAt = now
					return
				}
				LOGGER.info("[MeowMod] AutoSalvage: salvaging at slot {}", slot)
				press(screen, slot)
				step = S_WAIT_BEACON
				nextAt = now + wait()
			}

			S_WAIT_BEACON -> {
				val answered = !clickWait.unanswered(ScreenUtils.signature(screen))
				if (!answered) {
					// The confirmation is the game's answer to the beacon, and it takes a
					// moment to arrive - and if the salvage needed no confirmation, the
					// menu says that by never changing, and the run closes after a while.
					if (clickWait.since() > BEACON_TIMEOUT) {
						LOGGER.warn("[MeowMod] AutoSalvage: the salvage beacon was answered by nothing, closing")
						step = S_CLOSE
						nextAt = now
					}
					return
				}
				val confirm = (0 until own).firstOrNull {
					items[it].hoverName.string.contains(CONFIRM_NAME, ignoreCase = true)
				}
				if (confirm == null) {
					// The menu changed without a confirmation: the salvage is through.
					step = S_CLOSE
					nextAt = now
					return
				}
				LOGGER.info("[MeowMod] AutoSalvage: confirming the salvage at slot {}", confirm)
				press(screen, confirm)
				step = S_WAIT_CONFIRM
				nextAt = now + wait()
			}

			S_WAIT_CONFIRM -> {
				// The confirmation is clicked once: the run closes the menu as soon as it is
				// answered, and gives it a few seconds when it is not - the menu says
				// whether the salvage went through, so nothing is pressed again here.
				val answered = !clickWait.unanswered(ScreenUtils.signature(screen))
				if (answered || clickWait.since() > CONFIRM_TIMEOUT) {
					step = S_CLOSE
					nextAt = now
				}
			}

			else -> {
				Minecraft.getInstance().player?.closeContainer()
				reset()
			}
		}
	}

	// The game naming a tier it had nothing of: the wool for it was pressed and the answer
	// is that there was nothing to add. No menu change is coming, so the run moves on after
	// its delay rather than waiting out the answer window for one. A message about some
	// other moment of the run is not this run's business.
	private fun onChat(text: String) {
		if (!enabled || !running) return
		if (!text.contains(NO_ITEMS)) return
		if (step != S_WAIT_EPIC && step != S_WAIT_RARE) return
		LOGGER.info("[MeowMod] AutoSalvage: the game reports that tier had nothing, moving on")
		val pause = if (step == S_WAIT_EPIC) settingEpicRare.get() else settingRareSalvage.get()
		step = clickAfter(step)
		retried = false
		nextAt = System.currentTimeMillis() + pauseOf(pause)
	}

	// One press: the menu it happens in is remembered, so the wait after it is a wait for
	// the menu to actually move rather than for a timer to run out.
	private fun press(screen: AbstractContainerScreen<*>, slot: Int) {
		clickWait.clicked(ScreenUtils.signature(screen))
		ScreenUtils.click(slot, false)
	}

	// A configured delay, plus or minus the jitter, never below a millisecond.
	private fun pauseOf(base: Double): Long {
		val jitter = settingJitter.get().toLong()
		val wobble = if (jitter > 0) Random.nextLong(-jitter, jitter + 1) else 0L
		return (base.toLong() + wobble).coerceAtLeast(1L)
	}

	// The general delay, used where no step-specific wait applies.
	private fun wait(): Long = pauseOf(settingDelay.get())

	// Whether the last press can be moved past. With UI-Based Clicks on that means the menu
	// actually changed (see ClickWait); with it off there is no answer to wait on, so the
	// configured delay alone paces the wools. The beacon and its confirmation still wait for
	// the menu, since the confirmation only exists once the server has answered the beacon.
	private fun answered(screen: AbstractContainerScreen<*>): Boolean =
		!settingUiClicks.get() || !clickWait.unanswered(ScreenUtils.signature(screen))

	// The salvaged items in the menu: every slot of its own that is not the furniture the
	// menu is built from - the border panes, the beacon, the wools and the buttons.
	private fun filled(items: List<ItemStack>, own: Int): Int =
		(0 until own).count { i -> !items[i].isEmpty && !isFurniture(items[i]) }

	private fun isFurniture(stack: ItemStack): Boolean {
		val item = stack.item
		if (item == Items.BARRIER || item == Items.BEACON || item == Items.TORCH ||
			item == Items.ARROW || item == Items.WOOL.lime() || item == Items.WOOL.blue() ||
			item == Items.WOOL.purple()
		) return true
		// The border panes and anything else the menu marks itself with, by class or name,
		// so a pane cannot be counted as a gathered item however Hypixel words it.
		if (item.javaClass.simpleName.contains("Pane")) return true
		val name = stack.hoverName.string
		return name.contains("Stained Glass", ignoreCase = true) || name.contains("Add ", ignoreCase = true)
	}

	private fun reset() {
		running = false
		step = S_EPIC
		retried = false
		clickWait.reset()
		nextAt = 0L
	}

	override fun onEnable() {
		enabled = true
		reset()
	}

	override fun onDisable() {
		enabled = false
		reset()
	}

	// The run's steps: one press per wool, a wait for the menu to answer it after each,
	// then the beacon, its confirmation, and the close. The wools run highest rarity first.
	private const val S_EPIC = 0
	private const val S_WAIT_EPIC = 1
	private const val S_RARE = 2
	private const val S_WAIT_RARE = 3
	private const val S_BEACON = 6
	private const val S_WAIT_BEACON = 7
	private const val S_WAIT_CONFIRM = 8
	private const val S_CLOSE = 9

	// The wool pressed after each wait, the wait after each press, and the press each wait
	// belongs to - the retry goes back to the button that made no change, never forward.
	private fun waitAfter(clickStep: Int): Int = clickStep + 1
	private fun pressOf(waitStep: Int): Int = waitStep - 1
	private fun clickAfter(waitStep: Int): Int = when (waitStep) {
		S_WAIT_EPIC -> S_RARE
		else -> S_BEACON
	}

	private const val PLAYER_SLOTS = 36
	private const val SALVAGE_TITLE = "Salvage Items"
	private const val CONFIRM_NAME = "Confirm Salvage"
	private const val NO_ITEMS = "items in your inventory to salvage"

	// How long a press is waited on for the menu to move before the run decides nothing
	// happened, and how long the beacon and its confirmation are given to answer.
	private const val ANSWER_TIMEOUT = 2500L
	private const val BEACON_TIMEOUT = 4000L
	private const val CONFIRM_TIMEOUT = 3000L
}
