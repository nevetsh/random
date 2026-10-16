package com.meow.mod.features

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.meow.mod.MeowMod
import com.meow.mod.api.ChatUtils
import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors

// Two-way chat relay between Minecraft and a Discord channel using a bot token.
// Outgoing: your chat (and party/guild commands) are POSTed to the channel.
// Incoming: the channel is polled over REST and new messages are shown in chat.
object DiscordChat : Feature(
	"discordChat",
	"Relays chat to and from a Discord channel (needs a bot token).",
	subcategory = "Discord Chat",
	displayName = "Discord Chat",
	enabledByDefault = true
) {
	private const val BASE = "https://discord.com/api/v10"
	private val USER_AGENT = "MeowMod (https://github.com/nevetsh/random, " + MeowMod.VERSION + ")"

	private val LOGGER = LoggerFactory.getLogger("MeowMod")

	private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
	private val gson = Gson()
	private val executor = Executors.newSingleThreadExecutor { r ->
		Thread(r, "MeowMod-Discord").apply { isDaemon = true }
	}

	private val tokenData = addText(
		"token",
		"",
		"Discord bot token. Set it in meowmod.json; not shown in the UI.",
		"Bot Token",
		maxLength = 128,
		hidden = true
	)

	private val channelData = addText(
		"channel",
		"1557216865002979369",
		"Discord channel ID to relay with.",
		"Channel ID",
		maxLength = 32
	)

	private val settingOutgoing = addSwitch(
		"outgoing",
		true,
		"Send your Minecraft chat (and party/guild commands) to Discord.",
		"Relay Outgoing"
	)

	private val settingIncoming = addSwitch(
		"incoming",
		true,
		"Show new Discord messages in your Minecraft chat.",
		"Relay Incoming"
	)

	private val settingPoll = addSlider(
		"poll",
		2.0,
		1.0, 10.0,
		"Seconds between Discord message polls.",
		"Poll Interval (s)"
	)

	@Volatile
	private var enabled = false

	@Volatile
	private var botId: String? = null
	private var lastMessageId: String? = null
	private var lastPoll = 0L
	private var initialized = false

	private val chatCommands = mapOf(
		"pc" to "Party",
		"gc" to "Guild",
		"ac" to "All",
		"oc" to "Officer",
		"cc" to "Coop"
	)

	// Typing /dc <msg> or /irc <msg> sends the message straight to Discord.
	// /cc <msg> is a real SkyBlock command, so it passes through to the server and is
	// relayed to Discord by onOutgoingCommand (see chatCommands above).
	private val directCommands = setOf("dc", "irc")

	init {
		tokenData.onChange {
			botId = null
			lastMessageId = null
			if (enabled) fetchBotId()
		}
		channelData.onChange { lastMessageId = null }
	}

	fun initialize() {
		if (initialized) return
		initialized = true
		ClientSendMessageEvents.CHAT.register { message -> onOutgoing(message) }
		ClientSendMessageEvents.COMMAND.register { command -> onOutgoingCommand(command) }
		registerDirectCommands()
		ClientTickEvents.END_CLIENT_TICK.register { tick() }
	}

	override fun onEnable() {
		enabled = true
		lastPoll = 0L
		fetchBotId()
	}

	override fun onDisable() {
		enabled = false
	}

	private fun playerName(): String = Minecraft.getInstance().user.name

	private fun onOutgoing(message: String) {
		if (!enabled || !settingOutgoing.get() || message.isBlank()) return
		relay("${playerName()}: $message")
	}

	private fun onOutgoingCommand(command: String) {
		if (!enabled || !settingOutgoing.get()) return
		val root = command.substringBefore(' ').lowercase()
		val label = chatCommands[root] ?: return
		val body = command.substringAfter(' ', "").trim()
		if (body.isEmpty()) return
		relay("${playerName()}: [$label] $body")
	}

	// /dc and /irc are registered as real client commands so they get tab-completion and
	// don't produce "Unknown or incomplete command" errors.
	private fun registerDirectCommands() {
		ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
			for (name in directCommands) {
				dispatcher.register(
					literal(name)
						.then(
							argument("message", StringArgumentType.greedyString())
								.executes { ctx ->
									sendDirect(ctx.source, name, StringArgumentType.getString(ctx, "message"))
									1
								}
						)
						.executes { ctx ->
							ChatUtils.sendMessage("&8[&9Discord&8] &7Usage: /$name <message>")
							1
						}
				)
			}
		}
	}

	private fun sendDirect(source: FabricClientCommandSource, name: String, body: String) {
		val message = body.trim()
		val token = tokenData.get().trim()
		val channel = channelData.get().trim()
		if (message.isEmpty()) {
			source.sendFeedback(ChatUtils.literal("&8[&9Discord&8] &7Usage: /$name <message>"))
		} else if (token.isEmpty() || channel.isEmpty()) {
			source.sendFeedback(ChatUtils.literal("&8[&9Discord&8] &7No bot token/channel set."))
		} else {
			relay("${playerName()}: $message")
			// Mirror what actually landed in Discord instead of a generic "Sent:" line.
			source.sendFeedback(ChatUtils.literal("&8[&9Discord&8] &b${playerName()}&7: &f$message"))
		}
	}

	private fun tick() {
		if (!enabled || !settingIncoming.get()) return
		val interval = (settingPoll.get() * 1000).toLong().coerceAtLeast(500)
		val now = System.currentTimeMillis()
		if (now - lastPoll < interval) return
		lastPoll = now

		val token = tokenData.get().trim()
		val channel = channelData.get().trim()
		if (token.isEmpty() || channel.isEmpty()) return

		val after = lastMessageId
		executor.submit {
			val url = if (after == null) {
				"$BASE/channels/$channel/messages?limit=1"
			} else {
				"$BASE/channels/$channel/messages?limit=50&after=$after"
			}
			val body = httpGet(url, token) ?: return@submit
			Minecraft.getInstance().execute { handlePollResponse(body) }
		}
	}

	private fun handlePollResponse(body: String) {
		val array = try {
			JsonParser.parseString(body).asJsonArray
		} catch (_: Exception) {
			return
		}

		if (lastMessageId == null) {
			if (array.size() > 0) lastMessageId = array[0].asJsonObject.get("id")?.asString
			return
		}

		val messages = array.mapNotNull { element ->
			element.takeIf { it.isJsonObject }?.asJsonObject
		}.sortedBy { snowflake(it.get("id")?.asString) }

		for (message in messages) {
			val id = message.get("id")?.asString ?: continue
			lastMessageId = id

			val author = message.getAsJsonObject("author") ?: continue
			if (author.get("id")?.asString == botId) continue

			val content = message.get("content")?.asString ?: continue
			if (content.isBlank()) continue

			val name = author.get("global_name")?.takeIf { !it.isJsonNull }?.asString
				?: author.get("username")?.asString
				?: "unknown"
			ChatUtils.sendMessage(ChatUtils.literal("&8[&9Discord&8] &b$name&7: &f$content"))
		}
	}

	private fun fetchBotId() {
		val token = tokenData.get().trim()
		if (token.isEmpty()) return
		executor.submit {
			val body = httpGet("$BASE/users/@me", token) ?: return@submit
			val id = try {
				JsonParser.parseString(body).asJsonObject.get("id")?.asString
			} catch (_: Exception) {
				null
			}
			botId = id
			LOGGER.info("[MeowMod] Discord: authenticated as bot id $id")
		}
	}

	private fun relay(text: String) {
		val token = tokenData.get().trim()
		val channel = channelData.get().trim()
		if (token.isEmpty() || channel.isEmpty()) {
			LOGGER.info("[MeowMod] Discord: cannot relay, token/channel not set")
			return
		}
		val content = text.take(1900)
		executor.submit {
			try {
				val payload = JsonObject().apply { addProperty("content", content) }
				val request = HttpRequest.newBuilder(URI.create("$BASE/channels/$channel/messages"))
					.header("Authorization", "Bot $token")
					.header("Content-Type", "application/json")
					.header("User-Agent", USER_AGENT)
					.POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
					.build()
				val response = client.send(request, HttpResponse.BodyHandlers.ofString())
				if (response.statusCode() >= 300) {
					LOGGER.warn("[MeowMod] Discord: send failed (${response.statusCode()}) ${response.body()}")
				}
			} catch (e: Exception) {
				LOGGER.warn("[MeowMod] Discord: send error: ${e.message}")
			}
		}
	}

	private fun httpGet(url: String, token: String): String? {
		return try {
			val request = HttpRequest.newBuilder(URI.create(url))
				.header("Authorization", "Bot $token")
				.header("User-Agent", USER_AGENT)
				.GET()
				.build()
			val response = client.send(request, HttpResponse.BodyHandlers.ofString())
			if (response.statusCode() >= 300) {
				LOGGER.warn("[MeowMod] Discord: request failed (${response.statusCode()}) ${url.substringBefore('?')}")
				null
			} else {
				response.body()
			}
		} catch (e: Exception) {
			LOGGER.warn("[MeowMod] Discord: request error: ${e.message}")
			null
		}
	}

	private fun snowflake(id: String?): Long = id?.toLongOrNull() ?: 0L
}
