package com.meow.mod.features

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.meow.mod.MeowMod
import com.meow.mod.api.ChatUtils
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.jar.JarFile
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

// Config Sharing: bundles cached config files from this computer into the mod so other players
// can pick one and apply it. Applying copies the selected config into the game's config folder;
// then use Restart to restart the client.
//
// On top of that, a small share loop over GitHub exists for the handful of players who use this
// mod together: Upload publishes this computer's config folder plus (optionally) its modlist as
// configs.zip / modlist.txt / manifest.json, Fetch downloads them back into the config folder
// and, when modlist sync is on, reports which mods the two computers do not have in common.
// Before anything leaves the computer it is blanked: tokens, webhooks, UUIDs, account names,
// user paths and IPs are replaced, so what lands on GitHub carries the configuration but not
// the person. The GitHub token itself never leaves - it only signs the requests.
//
// Only configs that contain no personal data are bundled into the jar: no Discord tokens,
// channel IDs, custom names, player UUIDs, or inventory data. meowmod.json, skyhud-item-search/,
// storagelens/ and skyblockapi/ are excluded for that reason when bundling; the upload leaves
// out the same inventory-style paths and blanks the rest instead (meowmod.json included, so
// friends can share the settings - with their secrets stripped out).
object ConfigSharing : Feature(
	"configSharing",
	"Install a cached config, then restart.",
	subcategory = "Config Sharing",
	displayName = "Config Sharing",
	enabledByDefault = true
) {
	private val configChoice = addChoice(
		"config",
		"skyblockpv",
		listConfigs(),
		"Select which cached config to copy into your config folder. The list is scrollable.",
		"Config"
	)

	private val applyButton = addButton(
		"apply",
		"Copy the selected config into mine.",
		"Apply Config",
		label = "Apply Config",
		action = { applySelected() }
	)

	private val restartButton = addButton(
		"restart",
		"Restart so the copied config loads.",
		"Restart",
		label = "Restart Game",
		action = { restartGame() }
	)

	private val shareModlist = addSwitch(
		"shareModlist",
		true,
		"Share this modlist and compare on fetch.",
		"Sync Modlist"
	)

	private val ghRepo = addText(
		"ghRepo", "",
		"Share repository, as owner/repo.",
		"GitHub Repository", maxLength = 128
	)

	private val ghBranch = addText(
		"ghBranch", "main",
		"Branch the shared files are kept on.",
		"GitHub Branch", maxLength = 64
	)

	private val ghFolder = addText(
		"ghFolder", "meowmod-share",
		"Repository folder holding the files.",
		"Share Folder", maxLength = 64
	)

	private val ghToken = addText(
		"ghToken", "",
		"Contents read/write token, kept here.",
		"GitHub Token", maxLength = 128, secret = true
	)

	private val uploadButton = addButton(
		"upload",
		"Upload blanked configs and the modlist.",
		"Upload to GitHub",
		label = "Upload",
		action = { uploadToGitHub() }
	)

	private val fetchButton = addButton(
		"fetch",
		"Fetch shared configs, report modlist.",
		"Fetch from GitHub",
		label = "Fetch",
		action = { fetchFromGitHub() }
	)

	// Upload bounds: the GitHub contents API tops out around 1MB per file once base64
	// encoded, and a share should never drag in the whole config folder.
	private const val MAX_ENTRIES = 400
	private const val MAX_ENTRY_BYTES = 512L * 1024L
	private const val MAX_PACK_BYTES = 600L * 1024L

	// Paths that hold somebody's inventory, profile or account rather than settings.
	// They are left out of the share entirely: an NBT inventory cannot be blanked
	// without being destroyed, and a profile is the person, not the configuration.
	// The same paths are kept out of the jar, so what is shared here is what the
	// mod hands out.
	private val EXCLUDED_PATHS = setOf(
		"skyhud-item-search", "storagelens", "skyblockapi",
		"NoammAddons/storage", "NoammAddons/hub-map", "NoammAddons/data",
		"NoammAddons/mojang_cache.json", "NoammAddons/petCache.json",
		"NoammAddons/petsCache.json", "NoammAddons/item_protection.json",
		"skyblocker/backpack-preview", "skyblocker/equipment", "skyblocker/garden_plots",
		"skyblocker/reward-trackers", "skyblocker/config_backups", "skyblocker/item-repo",
		"skyblocker/collected_accessories.json", "skyblocker/dungeon_split_bests.json",
		"skyblocker/end.json", "skyblocker/museum_item_cache.json", "skyblocker/pet_cache.json",
		"skyblocker/slayer_personal_best.json", "skyblocker/slayers_data.json",
		"skyblocker/update_notifications_v2.json", "skyblocker/found_fairy_souls.json",
		"skyblocker/found_enigma_souls.json", "skyblocker/found_relics.json",
		"skyhud-storage-catalog.json", "ChatTriggers/cache"
	)

	private val TEXT_EXTENSIONS = setOf(
		"json", "txt", "toml", "cfg", "conf", "properties", "ini", "yaml", "yml",
		"js", "ts", "md", "lang", "xml", "csv", "log"
	)

	private val http = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build()

	private val configDir get() = FabricLoader.getInstance().configDir
	private val gameDir get() = FabricLoader.getInstance().gameDir

	// ---------------------------------------------------------------- applying

	private fun listConfigs(): List<String> {
		val jar = runningJar() ?: return emptyList()
		return try {
			JarFile(jar).use { jarFile ->
				val names = mutableSetOf<String>()
				jarFile.entries().asSequence().forEach { entry ->
					// Only files count: a folder that is left in the jar without any
					// file in it is an empty choice, not a config.
					if (entry.isDirectory) return@forEach
					val name = entry.name
					if (name.startsWith("configs/")) {
						val rest = name.substring("configs/".length)
						val slash = rest.indexOf('/')
						val top = if (slash >= 0) rest.substring(0, slash) else rest
						if (top.isNotEmpty()) names.add(top)
					}
				}
				names.toList().sorted()
			}
		} catch (e: Exception) {
			emptyList()
		}
	}

	private fun runningJar(): File? {
		return try {
			val location = MeowMod::class.java.protectionDomain?.codeSource?.location ?: return null
			val file = File(location.toURI())
			if (file.isDirectory) null else file
		} catch (_: Exception) {
			null
		}
	}

	private fun applySelected() {
		val selected = configChoice.get()
		val jar = runningJar() ?: run {
			ChatUtils.sendMessage("&cCannot find the mod jar to copy configs from.", true)
			return
		}

		try {
			JarFile(jar).use { jarFile ->
				val prefix = "configs/$selected/"
				jarFile.entries().asSequence().forEach { entry ->
					if (!entry.isDirectory && entry.name.startsWith(prefix)) {
						val relative = entry.name.substring(prefix.length)
						val target = configDir.resolve(relative)
						Files.createDirectories(target.parent)
						jarFile.getInputStream(entry).use { input ->
							Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
						}
					}
				}
				val single = jarFile.getEntry("configs/$selected")
				if (single != null && !single.isDirectory) {
					val target = configDir.resolve(selected)
					Files.createDirectories(target.parent)
					jarFile.getInputStream(single).use { input ->
						Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
					}
				}
			}
			ChatUtils.sendMessage("&aApplied config &f$selected&a to your config folder.", true)
		} catch (e: Exception) {
			ChatUtils.sendMessage("&cFailed to apply config: ${e.message}", true)
		}
	}

	private fun restartGame() {
		thread(isDaemon = true, start = true) {
			Thread.sleep(1000)
			Minecraft.getInstance().execute { Minecraft.getInstance().stop() }
		}
	}

	// ---------------------------------------------------------------- modlist

	// One line per installed mod: id, version and the jar's name, tab separated. The
	// personal data is in the file paths, not the mod ids, and those are blanked on the
	// way out (see sanitize).
	private fun collectModlist(): String {
		val builder = StringBuilder("# meowmod modlist\n")
		for (jar in modJars()) {
			var id = "?"
			var version = "?"
			try {
				JarFile(jar.toFile()).use { archive ->
					val entry = archive.getEntry("fabric.mod.json") ?: return@use
					val meta = JsonParser.parseString(
						archive.getInputStream(entry).reader(StandardCharsets.UTF_8).readText()
					).asJsonObject
					id = meta.get("id")?.asString ?: id
					version = meta.get("version")?.asString ?: version
				}
			} catch (_: Exception) {
				// A jar without readable metadata still earns a line, just with placeholders.
			}
			builder.append(id).append('\t').append(version).append('\t').append(jar.fileName).append('\n')
		}
		return builder.toString()
	}

	private fun modJars(): List<Path> = runCatching {
		val dir = gameDir.resolve("mods")
		Files.list(dir).use { stream ->
			stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar", ignoreCase = true) }
				.sorted()
				.toList()
		}
	}.getOrDefault(emptyList())

	private fun modIds(listing: String): Set<String> = listing.lineSequence()
		.filter { it.isNotBlank() && !it.startsWith("#") }
		.mapNotNull { line -> line.split('\t').firstOrNull()?.trim()?.takeIf { it.isNotEmpty() && it != "?" } }
		.toSet()

	// ------------------------------------------------------------------ upload

	private fun uploadToGitHub() {
		val repo = ghRepo.get().trim()
		val token = ghToken.get().trim()
		if (!Regex("[\\w.-]+/[\\w.-]+").matches(repo)) {
			ChatUtils.sendMessage("&cSet the GitHub repository first (owner/repo).", true)
			return
		}
		if (token.isEmpty()) {
			ChatUtils.sendMessage("&cSet a GitHub token first - it is only stored on this computer.", true)
			return
		}
		val branch = ghBranch.get().trim().ifEmpty { "main" }
		val folder = ghFolder.get().trim().trim('/')
		val withModlist = shareModlist.get()
		ChatUtils.sendMessage("&7Collecting config files (personal values blanked)...", true)

		thread(name = "MeowMod-ShareUpload", isDaemon = true) {
			try {
				val pack = collectConfigZip()
				val modlist = if (withModlist) sanitize(collectModlist()).toByteArray(StandardCharsets.UTF_8) else null
				val manifest = JsonObject().apply {
					addProperty("format", 1)
					addProperty("modVersion", MeowMod.VERSION)
					addProperty("uploadedAt", Instant.now().toString())
					addProperty("files", pack.files)
					addProperty("skipped", pack.skipped)
					addProperty("modlist", withModlist)
				}
				var uploaded = 0
				if (modlist != null) {
					putFile(repo, branch, "$folder/modlist.txt", token, modlist, "Share modlist")
					uploaded++
				}
				putFile(repo, branch, "$folder/configs.zip", token, pack.zip, "Share config files")
				uploaded++
				putFile(
					repo, branch, "$folder/manifest.json", token,
					manifest.toString().toByteArray(StandardCharsets.UTF_8), "Share manifest"
				)
				uploaded++
				ChatUtils.sendMessage(
					"&aUploaded &f${pack.files}&a config files" +
						(if (modlist != null) " + modlist" else "") +
						(if (pack.skipped > 0) " &7(${pack.skipped} too large or excluded)" else "") +
						"&a to &f$repo/$folder&a.",
					true
				)
			} catch (e: Exception) {
				ChatUtils.sendMessage("&cUpload failed: ${e.message}", true)
			}
		}
	}

	// Packs the config folder, blanking anything personal out of text files as it goes.
	private class SharePack(val zip: ByteArray, val files: Int, val skipped: Int)

	private fun collectConfigZip(): SharePack {
		val out = ByteArrayOutputStream()
		var files = 0
		var skipped = 0
		ZipOutputStream(out).use { zip ->
			val paths = Files.walk(configDir).use { stream ->
				stream.filter { Files.isRegularFile(it) }.sorted().toList()
			}
			for (path in paths) {
				val relative = configDir.relativize(path).toString().replace('\\', '/')
				if (EXCLUDED_PATHS.any { relative == it || relative.startsWith("$it/") }) {
					skipped++
					continue
				}
				if (Files.size(path) > MAX_ENTRY_BYTES || files >= MAX_ENTRIES || out.size() > MAX_PACK_BYTES) {
					skipped++
					continue
				}
				var bytes = Files.readAllBytes(path)
				if (isText(relative)) {
					bytes = sanitize(String(bytes, StandardCharsets.UTF_8)).toByteArray(StandardCharsets.UTF_8)
				}
				zip.putNextEntry(ZipEntry(relative))
				zip.write(bytes)
				zip.closeEntry()
				files++
			}
		}
		return SharePack(out.toByteArray(), files, skipped)
	}

	private fun isText(path: String): Boolean =
		path.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS

	// ------------------------------------------------------------------ fetch

	private fun fetchFromGitHub() {
		val repo = ghRepo.get().trim()
		if (!Regex("[\\w.-]+/[\\w.-]+").matches(repo)) {
			ChatUtils.sendMessage("&cSet the GitHub repository first (owner/repo).", true)
			return
		}
		val token = ghToken.get().trim()
		val branch = ghBranch.get().trim().ifEmpty { "main" }
		val folder = ghFolder.get().trim().trim('/')
		ChatUtils.sendMessage("&7Fetching the shared config files...", true)

		thread(name = "MeowMod-ShareFetch", isDaemon = true) {
			try {
				val zip = getFile(repo, branch, "$folder/configs.zip", token)
				if (zip == null) {
					ChatUtils.sendMessage("&cNothing shared at &f$repo/$folder&c yet - upload first.", true)
					return@thread
				}
				val modlist = getFile(repo, branch, "$folder/modlist.txt", token)
				var applied = 0
				ZipInputStream(ByteArrayInputStream(zip)).use { stream ->
					while (true) {
						val entry = stream.nextEntry ?: break
						if (entry.isDirectory) continue
						// The zip comes from GitHub, but a path that escapes the config
						// folder is never written no matter where the zip came from.
						val target = configDir.resolve(entry.name).normalize()
						if (!target.startsWith(configDir)) continue
						Files.createDirectories(target.parent)
						Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING)
						applied++
					}
				}
				if (shareModlist.get() && modlist != null) {
					reportModlist(String(modlist, StandardCharsets.UTF_8))
				}
				ChatUtils.sendMessage("&aFetched &f$applied&a config files. Use Restart to apply them.", true)
			} catch (e: Exception) {
				ChatUtils.sendMessage("&cFetch failed: ${e.message}", true)
			}
		}
	}

	// The sync part of the modlist option: what the shared computer runs that this one
	// does not (missing), and what this one runs that the share does not (extra).
	private fun reportModlist(listing: String) {
		val shared = modIds(listing)
		val local = modIds(collectModlist())
		val missing = (shared - local).sorted()
		val extra = (local - shared).sorted()
		if (missing.isEmpty() && extra.isEmpty()) {
			ChatUtils.sendMessage("&aModlist matches the mods on this computer.", true)
			return
		}
		if (missing.isNotEmpty()) {
			ChatUtils.sendMessage("&eNot installed here: &f${preview(missing)}", true)
		}
		if (extra.isNotEmpty()) {
			ChatUtils.sendMessage("&eNot in the shared modlist: &f${preview(extra)}", true)
		}
	}

	private fun preview(ids: List<String>): String =
		ids.take(6).joinToString(", ") + if (ids.size > 6) " (+${ids.size - 6} more)" else ""

	// ------------------------------------------------------------- blanking

	// Personal values are replaced before any bytes are encoded: account UUIDs, Discord
	// webhooks, tokens and similar keys, user names in paths, and IP addresses. What is
	// left is the configuration itself, which is the part worth sharing.
	private val uuidPattern = Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")
	private val webhookPattern = Regex("""https?://(?:[\w.-]+\.)?discord(?:app)?\.com/api/webhooks/[\w/?=&%-]+""")
	private val jsonSecretPattern = Regex(
		"""("[^"]*(?:token|secret|password|passwd|api[._-]?key|webhook|session|uuid|username|nickname|customname|channelid|discordid|profileid)[^"]*"\s*:\s*")[^"]*(")""",
		RegexOption.IGNORE_CASE
	)
	// A key that ends in "channel" holds a Discord channel id; a key that only
	// contains it ("channelFormat") is a setting and is left alone.
	private val jsonChannelPattern = Regex(
		"""("[^"]*channel"\s*:\s*")[^"]*(")""",
		RegexOption.IGNORE_CASE
	)
	private val lineSecretPattern = Regex(
		"""^([ \t]*[\w.-]*(?:token|secret|password|passwd|api[._-]?key|webhook|session|uuid|username|nickname|customname|channelid|discordid|profileid)[\w.-]*[ \t]*[=:][ \t]*).+$""",
		setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
	)
	private val pathPattern = Regex("""([A-Za-z]:\\+Users\\+|/(?:home|Users)/)[^\\/\s"']+""")
	private val ipPattern = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")

	private fun sanitize(text: String): String = text
		.replace(webhookPattern, "<blanked webhook>")
		.replace(jsonSecretPattern, "$1<blanked>$2")
		.replace(jsonChannelPattern, "$1<blanked>$2")
		.replace(lineSecretPattern, "$1<blanked>")
		.replace(uuidPattern, "00000000-0000-0000-0000-000000000000")
		.replace(pathPattern, "$1<blanked>")
		.replace(ipPattern, "<blanked>")

	// ------------------------------------------------------------- GitHub API

	private fun putFile(repo: String, branch: String, path: String, token: String, bytes: ByteArray, message: String) {
		val sha = headSha(repo, branch, path, token)
		val payload = JsonObject().apply {
			addProperty("message", message)
			addProperty("content", Base64.getEncoder().encodeToString(bytes))
			addProperty("branch", branch)
			if (sha != null) addProperty("sha", sha)
		}
		val response = send(
			"PUT",
			"https://api.github.com/repos/$repo/contents/${encodePath(path)}",
			token,
			payload.toString()
		)
		if (response.statusCode() !in 200..299) {
			throw IllegalStateException("HTTP ${response.statusCode()} for $path: ${response.body().take(200)}")
		}
	}

	private fun headSha(repo: String, branch: String, path: String, token: String): String? {
		val response = send(
			"GET",
			"https://api.github.com/repos/$repo/contents/${encodePath(path)}?ref=${encodePath(branch)}",
			token,
			null
		)
		return when (response.statusCode()) {
			200 -> JsonParser.parseString(response.body()).asJsonObject.get("sha")?.asString
			404 -> null
			else -> throw IllegalStateException("HTTP ${response.statusCode()} for $path")
		}
	}

	private fun getFile(repo: String, branch: String, path: String, token: String): ByteArray? {
		val response = send(
			"GET",
			"https://api.github.com/repos/$repo/contents/${encodePath(path)}?ref=${encodePath(branch)}",
			token,
			null
		)
		when (response.statusCode()) {
			404 -> return null
			200 -> {}
			else -> throw IllegalStateException("HTTP ${response.statusCode()} for $path")
		}
		val content = JsonParser.parseString(response.body()).asJsonObject.get("content")?.asString
			?: throw IllegalStateException("no content for $path")
		return Base64.getDecoder().decode(content.replace("\n", "").replace("\r", ""))
	}

	private fun send(method: String, url: String, token: String, body: String?): HttpResponse<String> {
		val builder = HttpRequest.newBuilder(URI.create(url))
			.header("Accept", "application/vnd.github+json")
			.header("User-Agent", "MeowMod-ConfigSharing")
			.timeout(Duration.ofSeconds(60))
		if (token.isNotEmpty()) builder.header("Authorization", "Bearer $token")
		val publisher = if (body != null) {
			builder.header("Content-Type", "application/json")
			HttpRequest.BodyPublishers.ofString(body)
		} else {
			HttpRequest.BodyPublishers.noBody()
		}
		builder.method(method, publisher)
		return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
	}

	private fun encodePath(path: String): String = path.split('/')
		.joinToString("/") { URLEncoder.encode(it, StandardCharsets.UTF_8).replace("+", "%20") }
}
