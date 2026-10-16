package com.meow.mod.features

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.meow.mod.MeowMod
import com.meow.mod.api.ChatUtils
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

object ModUpdater : Feature(
	"modUpdater",
	"Checks GitHub for a newer MeowMod release, downloads it, swaps out the old jar and restarts the client to apply it.",
	subcategory = "Updater",
	displayName = "Auto Updater",
	enabledByDefault = true
) {
	private const val REPO = "nevetsh/random"
	private val LOGGER = LoggerFactory.getLogger("MeowMod")

	private val SETTING_AUTO_RESTART = addSwitch(
		"autoRestart",
		true,
		"Restart the client automatically once an update has been downloaded and staged.",
		"Auto Restart"
	)

	private val started = AtomicBoolean(false)

	override fun onEnable() {
		if (started.getAndSet(true)) return
		Thread({ checkForUpdates() }, "MeowMod-Updater").apply { isDaemon = true }.start()
	}

	private fun loader() = FabricLoader.getInstance()

	private fun currentVersion(): String =
		loader().getModContainer(MeowMod.MOD_ID).map { it.metadata.version.friendlyString }.orElse(MeowMod.VERSION)

	private fun checkForUpdates() {
		try {
			if (loader().isDevelopmentEnvironment) return

			val codeSource = File(MeowMod::class.java.protectionDomain.codeSource.location.toURI())
			if (codeSource.isDirectory) return

			val client = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(10))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build()

			val request = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/$REPO/releases/latest"))
				.header("Accept", "application/vnd.github+json")
				.header("User-Agent", "MeowMod-Updater")
				.timeout(Duration.ofSeconds(15))
				.GET()
				.build()

			val response = client.send(request, HttpResponse.BodyHandlers.ofString())
			if (response.statusCode() == 404) {
				LOGGER.info("[MeowMod] Auto updater: no releases published on $REPO yet")
				return
			}
			if (response.statusCode() != 200) {
				LOGGER.warn("[MeowMod] Update check failed with HTTP {}", response.statusCode())
				return
			}

			val json = JsonParser.parseString(response.body()).asJsonObject
			val tag = json.get("tag_name")?.takeIf { it.isJsonPrimitive }?.asString ?: return

			if (!isNewer(parseVersion(tag), parseVersion(currentVersion()))) {
				LOGGER.info("[MeowMod] Auto updater: already up to date (v{})", currentVersion())
				return
			}

			val asset = findAsset(json)
			if (asset == null) {
				LOGGER.warn("[MeowMod] Release {} has no downloadable .jar asset", tag)
				return
			}

			val gameDir = loader().gameDir
			val modsDir = gameDir.resolve("mods")
			val staging = gameDir.resolve("meowmod-update.jar")
			Files.deleteIfExists(staging)
			download(client, asset.first, staging)
			LOGGER.info("[MeowMod] Auto updater: downloaded {} ({})", tag, asset.second)

			ChatUtils.sendMessage("&aMeowMod update &f$tag&a downloaded (current &f${currentVersion()}&a).", true)

			install(gameDir, modsDir, staging, codeSource, tag)
		} catch (e: Exception) {
			LOGGER.warn("[MeowMod] Auto updater error: {}", e.message)
		}
	}

	private fun findAsset(json: JsonObject): Pair<String, String>? {
		val assets = json.getAsJsonArray("assets") ?: return null
		val objects = assets.map { it.asJsonObject }
		val chosen = objects.firstOrNull {
			val name = it.get("name")?.asString ?: return@firstOrNull false
			name.startsWith("meowmod") && name.endsWith(".jar")
		} ?: objects.firstOrNull { (it.get("name")?.asString ?: "").endsWith(".jar") }
			?: return null

		val name = chosen.get("name")?.asString ?: return null
		val url = chosen.get("browser_download_url")?.asString ?: return null
		return url to name
	}

	private fun download(client: HttpClient, url: String, dest: Path) {
		val request = HttpRequest.newBuilder(URI.create(url))
			.header("User-Agent", "MeowMod-Updater")
			.timeout(Duration.ofSeconds(60))
			.GET()
			.build()
		client.send(request, HttpResponse.BodyHandlers.ofFile(dest))
	}

	private fun install(gameDir: Path, modsDir: Path, staging: Path, currentJar: File, tag: String) {
		val safeTag = tag.replace(Regex("[^A-Za-z0-9._-]"), "_")
		val newJar = modsDir.resolve("meowmod-$safeTag.jar")
		val canRestart = SETTING_AUTO_RESTART.get()
		val relaunch = ProcessHandle.current().info().commandLine().orElse(null)
		val isWindows = System.getProperty("os.name").lowercase().contains("win")

		val old = currentJar.absolutePath
		val staged = staging.toAbsolutePath().toString()
		val target = newJar.toAbsolutePath().toString()
		val dir = gameDir.toAbsolutePath().toString()

		val relaunchLine = if (canRestart && relaunch != null) {
			if (isWindows) "start \"\" $relaunch" else "nohup $relaunch >/dev/null 2>&1 &"
		} else ""

		val script = gameDir.resolve(if (isWindows) "meowmod-updater.cmd" else "meowmod-updater.sh")
		if (isWindows) {
			val content = """
@echo off
cd /d "$dir"
:wait
del /f /q "$old"
if exist "$old" ( timeout /t 1 /nobreak >nul & goto wait )
move /y "$staged" "$target"
$relaunchLine
del "%~f0"
""".trimIndent().replace("\n", "\r\n")
			Files.writeString(script, content)
		} else {
			val content = """
#!/bin/sh
cd "$dir" || exit 1
while [ -e "$old" ]; do rm -f "$old"; sleep 1; done
mv -f "$staged" "$target"
$relaunchLine
rm -f "${'$'}0"
""".trimIndent()
			Files.writeString(script, content)
			script.toFile().setExecutable(true)
		}

		try {
			if (isWindows) {
				ProcessBuilder("cmd.exe", "/c", "start", "", script.toString()).start()
			} else {
				ProcessBuilder("sh", script.toString()).start()
			}
		} catch (e: Exception) {
			LOGGER.warn("[MeowMod] Auto updater: failed to launch swap script: {}", e.message)
			return
		}

		LOGGER.info("[MeowMod] Auto updater: swap script launched at {}", script)

		if (canRestart && relaunch != null) {
			ChatUtils.sendMessage("&aInstalling update and restarting the client...", true)
			Minecraft.getInstance().execute { Minecraft.getInstance().stop() }
		} else {
			ChatUtils.sendMessage("&aUpdate staged. It will install the next time you close the game.", true)
		}
	}

	private fun parseVersion(version: String): List<Int> =
		version.trim()
			.removePrefix("v").removePrefix("V")
			.substringBefore('-').substringBefore('+')
			.split('.')
			.map { it.toIntOrNull() ?: 0 }

	private fun isNewer(remote: List<Int>, local: List<Int>): Boolean {
		for (i in 0 until maxOf(remote.size, local.size)) {
			val a = remote.getOrElse(i) { 0 }
			val b = local.getOrElse(i) { 0 }
			if (a != b) return a > b
		}
		return false
	}
}
