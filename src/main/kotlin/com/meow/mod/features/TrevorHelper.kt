package com.meow.mod.features

import com.google.gson.JsonParser
import com.meow.mod.api.ChatUtils
import com.meow.mod.api.Render3D
import com.meow.mod.api.humanReaction
import com.meow.mod.api.noControlCodes
import com.meow.mod.config.ConfigData
import com.meow.mod.events.MeowEvents
import com.meow.mod.skyblock.Island
import com.meow.mod.skyblock.LocationUtils
import com.meow.mod.ui.Render2D
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.animal.chicken.Chicken
import net.minecraft.world.entity.animal.cow.Cow
import net.minecraft.world.entity.animal.equine.Horse
import net.minecraft.world.entity.animal.pig.Pig
import net.minecraft.world.entity.animal.rabbit.Rabbit
import net.minecraft.world.entity.animal.sheep.Sheep
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.slf4j.LoggerFactory
import java.awt.Color
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Locale
import kotlin.concurrent.thread

// Trevor the Trapper helper: the two upstream features merged into one module.
//
//   * TrevorESP (DocilElm/devoniandoogan): ESP triggered by the animal's name changing to
//     carry a rarity word, auto accept, and the desert/trapper warps - unguarded, it works
//     anywhere the messages appear.
//   * TrevorHelper (Noamm9/NoammAddons): chat regex trigger with entity health matching,
//     rarity colors, area rendering once the player is far away, the cooldown timer HUD,
//     auto call with a "Call early" offset, and the world guard (upstream's
//     WorldType.TheBarn is the tab area "The Farming Islands", which is what meowmod's
//     Island.FarmingIsland reads).
//
// Every action that answers a chat message waits a fresh 250-500ms "human reaction" roll
// before the command is sent (see humanReaction), so nothing fires on the tick the
// message arrived.
//
// Upstream parity: the synced commit constants below record which upstream commit this
// code was ported from. While the module is enabled, the upstream repos are polled for
// their head commit; when one moves, the module says so and the port can be refreshed.
object TrevorHelper : Feature(
	"trevorHelper",
	"ESP, accept, warp, call, timer: Trevor.",
	subcategory = "Trevor Helper",
	displayName = "Trevor Helper",
	enabledByDefault = true,
	heading = "General"
) {
	private val LOGGER = LoggerFactory.getLogger("MeowMod")

	// ---------------------------------------------------------------- settings

	private val espEnabled = addSwitch(
		"esp", true,
		"Box and tracer on the current animal.",
		"Animal ESP", heading = "ESP"
	)

	private val espTracer = addSwitch(
		"tracer", true,
		"Line from the camera to the animal.",
		"Show tracer", heading = "ESP"
	)

	private val espFill = addSwitch(
		"fill", true,
		"Translucent fill in the wireframe box.",
		"Fill box", heading = "ESP"
	)

	private val worldGuard = addSwitch(
		"worldGuard", true,
		"Only act on The Farming Islands.",
		"Farming Islands only", heading = "ESP"
	)

	private val autoAccept = addSwitch(
		"autoAccept", true,
		"Accept the task on the [YES] prompt.",
		"Auto accept", heading = "Automation"
	)

	private val autoWarp = addSwitch(
		"autoWarp", true,
		"Warp to the desert, then back to Trevor.",
		"Auto warp", heading = "Automation"
	)

	private val autoCall = addSwitch(
		"autoCall", false,
		"Call Trevor as the cooldown ends.",
		"Auto call", heading = "Automation"
	)

	private val callEarly = addSlider(
		"callEarly", 2.0, 0.0, 5.0,
		"Call this many seconds before it ends.",
		"Call early (s)", heading = "Automation", step = 0.1
	)

	private val cooldownHud = addSwitch(
		"cooldownHud", true,
		"Cooldown timer in the top left corner.",
		"Cooldown timer", heading = "HUD"
	)

	private val colorTrackable = addText(
		"colorTrackable", "#CDD6F4",
		"Color for Trackable animals, as #RRGGBB.", "Trackable color", maxLength = 7, color = true, heading = "Colors"
	)

	private val colorUntrackable = addText(
		"colorUntrackable", "#A6E3A1",
		"Color for Untrackable, as #RRGGBB.", "Untrackable color", maxLength = 7, color = true, heading = "Colors"
	)

	private val colorUndetected = addText(
		"colorUndetected", "#89B4FA",
		"Color for Undetected animals, as #RRGGBB.", "Undetected color", maxLength = 7, color = true, heading = "Colors"
	)

	private val colorEndangered = addText(
		"colorEndangered", "#CBA6F7",
		"Color for Endangered animals, as #RRGGBB.", "Endangered color", maxLength = 7, color = true, heading = "Colors"
	)

	private val colorElusive = addText(
		"colorElusive", "#F9E2AF",
		"Color for Elusive animals, as #RRGGBB.", "Elusive color", maxLength = 7, color = true, heading = "Colors"
	)

	private val upstreamWatch = addSwitch(
		"upstreamWatch", true,
		"Warn in chat when the port falls behind.",
		"Watch upstream", heading = "Upstream"
	)

	private val upstreamButton = addButton(
		"upstreamCheck",
		"Compare synced bases with upstream now.",
		"Check upstream", label = "Check Now", heading = "Upstream",
		action = { maybeCheckUpstream(force = true) }
	)

	// ------------------------------------------------------------ state / types

	// The five animals Trevor hunts, with the base hit points the health match uses.
	// Upstream colors are the defaults; each can be recolored in the config.
	private enum class Rarity(val hp: Float, val default: Color) {
		Trackable(100f, Color(205, 214, 244)),
		Untrackable(500f, Color(166, 227, 161)),
		Undetected(1000f, Color(137, 180, 250)),
		Endangered(5000f, Color(203, 166, 247)),
		Elusive(10000f, Color(249, 226, 175));

		companion object {
			fun from(type: String?): Rarity? = entries.firstOrNull { it.name.equals(type?.trim(), true) }
		}
	}

	// The spots Trevor's animals can spawn in (taken from SkyHanni via NoammAddons).
	private enum class MobArea(val location: String, val pos: BlockPos) {
		OASIS("Oasis", BlockPos(126, 77, -456)),
		GORGE("Mushroom Gorge", BlockPos(300, 80, -509)),
		OVERGROWN("Overgrown Mushroom Cave", BlockPos(242, 60, -389)),
		SETTLEMENT("Desert Settlement", BlockPos(184, 86, -384)),
		GLOWING("Glowing Mushroom Cave", BlockPos(199, 50, -512)),
		MOUNTAIN("Desert Mountain", BlockPos(255, 148, -518));

		companion object {
			fun from(location: String?): MobArea? = entries.firstOrNull { it.location.equals(location?.trim(), true) }
		}
	}

	private val rarityWords = listOf("Trackable", "Untrackable", "Undetected", "Endangered", "Elusive")

	private val startRegex =
		Regex("\\[NPC] Trevor: You can find your (?<rarity>.+) animal near the (?<location>.+)\\.")
	private val returnRegex = Regex("^Return to the Trapper soon to get a new animal to hunt!$")

	// 400 ticks of cooldown after the start message, as upstream counts it.
	private const val COOLDOWN_MS = 400L * 50L

	// The animal is drawn from the name above it (devoniandoogan's NameChange trigger,
	// observed each tick instead of via a mixin) or, when the chat regex already named the
	// rarity, from the animal's max health matching it (NoammAddons). Identity keys:
	// vanilla entities compare by reference.
	private val nameRarities = HashMap<Entity, Rarity>()

	@Volatile private var mobRarity: Rarity? = null
	@Volatile private var mobArea: MobArea? = null
	@Volatile private var cooldownUntil = 0L

	// Commands waiting for their human reaction delay, sent from the tick handler so
	// nothing ever talks to the connection from a chat or worker thread.
	private class Pending(val at: Long, val command: String)

	private val pending = mutableListOf<Pending>()

	@Volatile private var enabled = false
	private var initialized = false

	// GAME and CHAT can both deliver the same line; a message is only handled once.
	private var lastMessage = ""
	private var lastMessageAt = 0L

	private val colorCache = HashMap<Rarity, Color>()

	// Which upstream commit this port mirrors. When a repo's head differs, the port is
	// behind and the module says so - the constants move whenever the code is re-synced.
	private class Upstream(val name: String, val repo: String, val synced: String)

	private val upstreams = listOf(
		Upstream("NoammAddons", "Noamm9/NoammAddons", "df72986"),
		Upstream("devoniandoogan", "DocilElm/devoniandoogan", "205f830")
	)

	private val notifiedUpstreams = HashSet<String>()
	private var lastUpstreamCheck = 0L
	private const val UPSTREAM_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L

	private val http = HttpClient.newBuilder()
		.connectTimeout(java.time.Duration.ofSeconds(10))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build()

	init {
		listOf(colorTrackable, colorUntrackable, colorUndetected, colorEndangered, colorElusive)
			.forEach { setting -> setting.onChange { colorCache.clear() } }
	}

	// ---------------------------------------------------------------- lifecycle

	fun initialize() {
		if (initialized) return
		initialized = true

		// Both channels hand over the full component: the accept prompt's click event lives
		// in it, and the dedup below makes sure a line delivered twice is only handled once.
		ClientReceiveMessageEvents.GAME.register { message, _ -> onChat(message.string, message) }
		ClientReceiveMessageEvents.CHAT.register { message, _, _, _, _ -> onChat(message.string, message) }
		ClientTickEvents.END_CLIENT_TICK.register { tick() }
		MeowEvents.onWorldLoad { reset() }
		LevelRenderEvents.END_MAIN.register { context -> render(context) }

		// addLast rather than attaching relative to a vanilla layer: the vanilla layers are
		// not all registered yet while entrypoints run, and a corner timer has no ordering
		// requirements against them (it never overlaps anything they draw).
		HudElementRegistry.addLast(
			Identifier.fromNamespaceAndPath("meowmod", "trevor_cooldown")
		) { graphics, _ -> drawCooldown(graphics) }
	}

	override fun onEnable() {
		enabled = true
		// First check lands on the next tick after being switched on.
		lastUpstreamCheck = 0L
	}

	override fun onDisable() {
		enabled = false
		synchronized(pending) { pending.clear() }
		reset()
	}

	// World changed (or the module restarted): nothing from the old world carries over.
	private fun reset() {
		mobRarity = null
		mobArea = null
		cooldownUntil = 0L
		nameRarities.clear()
		synchronized(pending) { pending.clear() }
		lastMessage = ""
	}

	// ---------------------------------------------------------------- chat hooks

	private fun onChat(text: String, component: Component) {
		if (!enabled) return
		val now = System.currentTimeMillis()
		if (text == lastMessage && now - lastMessageAt < 3000L) return
		lastMessage = text
		lastMessageAt = now

		val stripped = text.noControlCodes().trim()

		// Task started: remember rarity and area, start the cooldown, and head for the
		// desert if the animal spawned there (devoniandoogan's warp trigger).
		startRegex.find(stripped)?.let { match ->
			val rarity = Rarity.from(match.groups["rarity"]?.value)
			val location = match.groups["location"]?.value.orEmpty()
			if (rarity != null) {
				mobRarity = rarity
				mobArea = MobArea.from(location)
				cooldownUntil = System.currentTimeMillis() + COOLDOWN_MS
			}
			if (autoWarp.get() && location.equals("Desert Settlement", ignoreCase = true)) {
				schedule("warp desert")
			}
			return
		}

		// Task over: warp back to the trapper right away, and - if enabled - queue the
		// call for when the cooldown is about to end. Both wait their reaction roll.
		if (returnRegex.matches(stripped)) {
			val remaining = (cooldownUntil - System.currentTimeMillis()).coerceAtLeast(0L)
			if (autoWarp.get()) schedule("warp trapper")
			if (autoCall.get() && guarded()) {
				val wait = (remaining - (callEarly.get() * 1000.0).toLong()).coerceAtLeast(0L)
				schedule("call trevor", wait)
			}
			mobRarity = null
			mobArea = null
			cooldownUntil = 0L
			return
		}

		// The accept prompt: run the same command the [YES] click carries. Upstream is
		// split on the guard here (NoammAddons gates its whole chat handler, devoniandoogan
		// does not), so the toggle decides and the prompt is accepted anywhere.
		if (autoAccept.get() &&
			stripped.contains("Accept the trapper's task to hunt the animal?") &&
			stripped.contains("Click an option: [YES] - [NO]")
		) {
			val yes = findYesCommand(component) ?: return
			schedule(yes)
		}
	}

	// The click command of the [YES] option, searched through the component tree the way
	// devoniandoogan does (siblings first, styles included).
	private fun findYesCommand(component: Component): String? {
		val click = component.style.clickEvent
		if (click is ClickEvent.RunCommand && click.command().endsWith("YES")) return click.command()
		for (sibling in component.siblings) findYesCommand(sibling)?.let { return it }
		return null
	}

	// Queues a command to run after a human reaction delay of 250-500ms, on top of any
	// extra wait (the auto call's cooldown offset). Bounded so a burst of messages can
	// never build an unbounded queue.
	private fun schedule(command: String, after: Long = 0L) {
		if (!enabled) return
		synchronized(pending) {
			if (pending.size >= 16) return
			pending.add(Pending(System.currentTimeMillis() + after + humanReaction(), command))
		}
	}

	// ------------------------------------------------------------------- ticking

	private fun tick() {
		if (!enabled) return
		maybeCheckUpstream(force = false)

		val mc = Minecraft.getInstance()
		val level = mc.level ?: return

		// Name-change trigger: watch each entity's displayed name, keeping the ones that
		// carry a rarity word ("Trackable Cow" and friends), as devoniandoogan's
		// NameChangeEvent does - observed on tick instead of through a mixin.
		val matched = HashSet<Entity>()
		for (entity in level.entitiesForRendering()) {
			val name = entity.name.string.noControlCodes()
			val rarity = rarityIn(name) ?: continue
			nameRarities[entity] = rarity
			matched.add(entity)
		}
		nameRarities.keys.retainAll(matched)

		// Due commands are sent from the tick, never from the chat callback thread.
		val now = System.currentTimeMillis()
		synchronized(pending) {
			val iterator = pending.iterator()
			while (iterator.hasNext()) {
				val entry = iterator.next()
				if (entry.at > now) continue
				iterator.remove()
				mc.connection?.sendCommand(entry.command.removePrefix("/").trim())
			}
		}
	}

	private fun rarityIn(name: String): Rarity? {
		val word = rarityWords.firstOrNull { name.contains("$it ") } ?: return null
		return Rarity.from(word)
	}

	// ------------------------------------------------------------------ rendering

	private fun render(context: LevelRenderContext) {
		if (!enabled || !espEnabled.get()) return
		if (!guarded()) return
		val mc = Minecraft.getInstance()
		val level = mc.level ?: return
		val player = mc.player ?: return
		val pose = context.poseStack() ?: return
		val collector = context.submitNodeCollector() ?: return
		val cameraState = context.levelState()?.cameraRenderState ?: return
		val camera = cameraState.pos ?: return
		val partial = mc.deltaTracker.getGameTimeDeltaPartialTick(false)

		// The animal itself: rarity from its name, or from the chat regex plus a health
		// match. Both triggers color-code (upstream devoniandoogan was green-only).
		val chatRarity = mobRarity
		for (entity in level.entitiesForRendering()) {
			if (entity !is LivingEntity || !isTrevorAnimal(entity)) continue
			val rarity = nameRarities[entity] ?: chatRarity?.takeIf { matchesHealth(it, entity) } ?: continue
			if (entity.isDeadOrDying || entity.isRemoved) continue
			val color = colorOf(rarity)
			val position = entity.getPosition(partial)
			val box = entity.boundingBox.move(
				position.x - entity.x,
				position.y - entity.y,
				position.z - entity.z
			)
			Render3D.submitBox(collector, pose, camera, box, color, fill = espFill.get(), outline = true)
			if (espTracer.get()) {
				Render3D.submitTracer(
					collector, pose, camera,
					Vec3(position.x, position.y + entity.bbHeight / 2.0, position.z),
					color
				)
			}
		}

		// The area box + label, but only once the player is far from it: upstream skips
		// it below ~63 blocks (4000 squared) because you can see the place yourself.
		val area = mobArea ?: return
		val rarity = chatRarity ?: return
		val blockPos = player.blockPosition()
		val dx = (area.pos.x - blockPos.x).toDouble()
		val dy = (area.pos.y - blockPos.y).toDouble()
		val dz = (area.pos.z - blockPos.z).toDouble()
		if (dx * dx + dy * dy + dz * dz < 4000.0) return

		val color = colorOf(rarity)
		val block = AABB(area.pos)
		Render3D.submitBox(collector, pose, camera, block, color, fill = true, outline = true, lineWidth = 2.5f)
		Render3D.submitLabel(
			collector, pose, camera,
			Vec3(area.pos.x + 0.5, area.pos.y + 4.0, area.pos.z + 0.5),
			Component.literal(area.location).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(color.rgb and 0xFFFFFF))),
			cameraState
		)
	}

	// Upstream NoammAddons: the animal must have the rarity's health - horses count half,
	// and a Derpy mayor doubles every animal. The election feed is not fetched here, so
	// both the plain and the doubled value are accepted; the only ambiguity that leaves is
	// an Endangered/Elusive overlap while Derpy is mayor, which only miscolors a box.
	private fun matchesHealth(rarity: Rarity, living: LivingEntity): Boolean {
		var max = living.getAttributeBaseValue(Attributes.MAX_HEALTH).toFloat()
		if (living is Horse) max /= 2f
		return max == rarity.hp || max == rarity.hp * 2f
	}

	private fun isTrevorAnimal(entity: LivingEntity): Boolean = when (entity) {
		is Cow, is Pig, is Sheep, is Chicken, is Rabbit, is Horse -> true
		else -> false
	}

	// Upstream's guard: NoammAddons only runs on WorldType.TheBarn, whose tab name is
	// "The Farming Islands" - the area meowmod already reads from the tab list.
	private fun guarded(): Boolean = !worldGuard.get() || LocationUtils.currentArea == Island.FarmingIsland

	// ------------------------------------------------------------------- HUD

	private fun drawCooldown(graphics: GuiGraphicsExtractor) {
		if (!enabled || !cooldownHud.get()) return
		if (!guarded()) return
		val remaining = cooldownUntil - System.currentTimeMillis()
		if (remaining <= 0) return
		val seconds = remaining / 1000.0
		Render2D.text(
			graphics,
			String.format(Locale.ROOT, "Cooldown: §c%.1fs", seconds),
			6, 6, 0xFFFFFFFF.toInt()
		)
	}

	// ------------------------------------------------------------------ colors

	private fun colorOf(rarity: Rarity): Color = colorCache.getOrPut(rarity) {
		parseColor(colorSettings[rarity]?.get() ?: "", rarity.default)
	}

	private val colorSettings: Map<Rarity, ConfigData.Text> = mapOf(
		Rarity.Trackable to colorTrackable,
		Rarity.Untrackable to colorUntrackable,
		Rarity.Undetected to colorUndetected,
		Rarity.Endangered to colorEndangered,
		Rarity.Elusive to colorElusive
	)

	private fun parseColor(text: String, fallback: Color): Color {
		val hex = text.trim().removePrefix("#")
		return try {
			when (hex.length) {
				6 -> Color((0xFF shl 24) or hex.toInt(16), true)
				8 -> Color(hex.toInt(16), true)
				else -> fallback
			}
		} catch (_: NumberFormatException) {
			fallback
		}
	}

	// -------------------------------------------------------------- upstream sync

	// Polls both upstream repos (once every few hours, or on demand from the button) and
	// reports a head commit that differs from the synced base - this is how the module
	// keeps up "whenever they update their mod": the port is flagged for a re-sync rather
	// than silently drifting. Errors are only spoken about when the check was requested.
	private fun maybeCheckUpstream(force: Boolean) {
		if (!force && (!enabled || !upstreamWatch.get())) return
		val now = System.currentTimeMillis()
		if (!force && now - lastUpstreamCheck < UPSTREAM_CHECK_INTERVAL_MS) return
		lastUpstreamCheck = now

		thread(name = "MeowMod-TrevorUpstream", isDaemon = true) {
			for (upstream in upstreams) {
				try {
					val commits = getJson("https://api.github.com/repos/${upstream.repo}/commits?per_page=1")
						?.takeIf { it.isJsonArray }?.asJsonArray
						?: throw IllegalStateException("unexpected response")
					val headCommit = commits.firstOrNull()?.asJsonObject ?: throw IllegalStateException("no commits")
					val head = headCommit.get("sha")?.asString ?: throw IllegalStateException("no sha")
					val message = headCommit.getAsJsonObject("commit")?.get("message")?.asString
						?.lineSequence()?.firstOrNull().orEmpty().take(90)
					val shortHead = head.take(7)
					if (shortHead == upstream.synced) {
						if (force) {
							ChatUtils.sendMessage(
								"&a${upstream.name} is in sync with the synced base &f${upstream.synced}&a.",
								true
							)
						}
						continue
					}
					if (!force && !notifiedUpstreams.add(upstream.repo)) continue
					ChatUtils.sendMessage(
						"&e${upstream.name} updated: &f${upstream.synced}&r&7 -> &f$shortHead&e - re-sync Trevor Helper." +
							if (message.isEmpty()) "" else "\n&7$message",
						true
					)
				} catch (e: Exception) {
					if (force) {
						ChatUtils.sendMessage("&cCould not reach GitHub for ${upstream.name}: ${e.message}", true)
					} else {
						LOGGER.info("[MeowMod] Trevor upstream check failed for {}: {}", upstream.name, e.message)
					}
				}
			}
		}
	}

	private fun getJson(url: String) = try {
		val request = HttpRequest.newBuilder(URI.create(url))
			.header("Accept", "application/vnd.github+json")
			.header("User-Agent", "MeowMod-TrevorHelper")
			.timeout(java.time.Duration.ofSeconds(15))
			.GET()
			.build()
		val response = http.send(request, HttpResponse.BodyHandlers.ofString())
		if (response.statusCode() != 200) null
		else JsonParser.parseString(response.body())
	} catch (_: Exception) {
		null
	}
}
