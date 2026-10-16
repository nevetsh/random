package com.meow.mod.features

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.meow.mod.MeowMod
import com.meow.mod.api.ChatUtils
import com.meow.mod.api.RelaunchCommand
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
import java.util.concurrent.atomic.AtomicInteger

object ModUpdater : Feature(
	"modUpdater",
	"Downloads newer builds from GitHub.",
	subcategory = "General",
	displayName = "Auto Updater",
	enabledByDefault = true,
	heading = "Mod Information"
) {
	private const val REPO = "nevetsh/random"
	private const val API = "https://api.github.com/repos/$REPO"
	private const val STAGING_JAR = "meowmod-update.jar"

	// How long the client gets to shut down on its own before the process is halted. A kill
	// switch that can hang on a stuck client is not a kill switch, and the swap script is
	// waiting for this process to disappear before it touches the jar.
	private const val KILL_WATCHDOG_MS = 15_000L
	private val LOGGER = LoggerFactory.getLogger("MeowMod")

	private val SETTING_AUTO_RESTART = addSwitch(
		"autoRestart",
		true,
		"Restart once an update is downloaded.",
		"Auto Restart",
		heading = "Mod Information"
	)

	// The kill switch in the General screen: end this session, install a newer build if there
	// is one, then start the client again from the same command line.
	private val restartButton = addButton(
		"restart",
		"Install any update, then restart.",
		"Kill Switch",
		label = "Restart",
		heading = "Mod Information",
		action = { killAndRelaunch() }
	)

	private val started = AtomicBoolean(false)
	private val restartRequested = AtomicBoolean(false)
	private val restartScriptLaunched = AtomicBoolean(false)
	private val scriptCount = AtomicInteger()

	private data class Candidate(val tag: String, val url: String, val name: String)

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

			val codeSource = jarFile() ?: return

			val client = httpClient()

			val candidate = fetchFromReleases(client) ?: fetchFromFolder(client)
			if (candidate == null) {
				LOGGER.info("[MeowMod] Auto updater: no update source found on $REPO")
				return
			}

			if (compareVersions(parseVersion(candidate.tag), parseVersion(currentVersion())) <= 0) {
				LOGGER.info("[MeowMod] Auto updater: already up to date (v{})", currentVersion())
				return
			}

			val gameDir = loader().gameDir
			val staging = gameDir.resolve(STAGING_JAR)
			Files.deleteIfExists(staging)
			download(client, candidate.url, staging)
			LOGGER.info("[MeowMod] Auto updater: downloaded {} ({})", candidate.tag, candidate.name)

			ChatUtils.sendMessage("&aMeowMod update &f${candidate.tag}&a downloaded (current &f${currentVersion()}&a).", true)

			// A restart needs the command line this client was started with. Without one the swap
			// still happens, the client just cannot bring itself back.
			val canRestart = SETTING_AUTO_RESTART.get() && relaunchCommand() != null
			if (!install(gameDir, gameDir.resolve("mods"), staging, codeSource, candidate.tag, canRestart)) return
			if (canRestart) {
				ChatUtils.sendMessage("&aInstalling update and restarting the client...", true)
				endSession()
			} else {
				ChatUtils.sendMessage("&aUpdate staged. It will install the next time you close the game.", true)
			}
		} catch (e: Exception) {
			LOGGER.warn("[MeowMod] Auto updater error: {}", e.message)
		}
	}

	private fun fetchFromReleases(client: HttpClient): Candidate? {
		val json = getJson(client, "$API/releases/latest") as? JsonObject ?: return null
		val tag = json.get("tag_name")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
		val assets = json.getAsJsonArray("assets") ?: return null
		val objects = assets.map { it.asJsonObject }
		val chosen = objects.firstOrNull {
			val name = it.get("name")?.asString ?: return@firstOrNull false
			name.startsWith("meowmod") && name.endsWith(".jar")
		} ?: objects.firstOrNull { (it.get("name")?.asString ?: "").endsWith(".jar") }
			?: return null
		val name = chosen.get("name")?.asString ?: return null
		val url = chosen.get("browser_download_url")?.asString ?: return null
		return Candidate(tag, url, name)
	}

	private fun fetchFromFolder(client: HttpClient): Candidate? {
		val json = getJson(client, "$API/contents/releases") as? com.google.gson.JsonArray ?: return null
		val jars = json.mapNotNull { element ->
			val obj = element.asJsonObject
			val name = obj.get("name")?.asString ?: return@mapNotNull null
			if (!name.startsWith("meowmod") || !name.endsWith(".jar")) return@mapNotNull null
			val url = obj.get("download_url")?.asString ?: return@mapNotNull null
			val tag = name.removePrefix("meowmod-").removeSuffix(".jar")
			Candidate(tag, url, name)
		}
		return jars.maxWithOrNull { a, b -> compareVersions(parseVersion(a.tag), parseVersion(b.tag)) }
	}

	private fun getJson(client: HttpClient, url: String): com.google.gson.JsonElement? {
		val request = HttpRequest.newBuilder(URI.create(url))
			.header("Accept", "application/vnd.github+json")
			.header("User-Agent", "MeowMod-Updater")
			.timeout(Duration.ofSeconds(15))
			.GET()
			.build()
		val response = client.send(request, HttpResponse.BodyHandlers.ofString())
		if (response.statusCode() != 200) {
			LOGGER.info("[MeowMod] Auto updater: {} returned HTTP {}", url, response.statusCode())
			return null
		}
		return JsonParser.parseString(response.body())
	}

	private fun download(client: HttpClient, url: String, dest: Path) {
		val request = HttpRequest.newBuilder(URI.create(url))
			.header("User-Agent", "MeowMod-Updater")
			.timeout(Duration.ofSeconds(60))
			.GET()
			.build()
		client.send(request, HttpResponse.BodyHandlers.ofFile(dest))
	}

	// Writes the swap/relaunch script and starts it detached. Returns false when that script
	// could not be started, in which case nothing has been touched. staging == null means there
	// is nothing to swap: the script only waits for this process to exit and starts the client
	// again.
	private fun install(
		gameDir: Path,
		modsDir: Path,
		staging: Path?,
		currentJar: File,
		tag: String?,
		allowRestart: Boolean
	): Boolean {
		// Only one relaunch-capable script may ever be started: two of them would each start a
		// client, and the auto updater and the kill switch can both get this far.
		if (allowRestart && !restartScriptLaunched.compareAndSet(false, true)) return false

		// The client holds its own jar open for as long as it runs, so the swap waits for
		// this process to exit before it touches the file and only then relaunches. The
		// leading "v" is dropped from the file name so it matches the release asset.
		val pid = ProcessHandle.current().pid()
		val relaunch = if (allowRestart) relaunchCommand() else null
		val isWindows = System.getProperty("os.name").lowercase().contains("win")

		val old = currentJar.absolutePath
		val dir = gameDir.toAbsolutePath().toString()
		val staged = staging?.toAbsolutePath()?.toString()
		val target = tag?.replace(Regex("[^A-Za-z0-9._-]"), "_")?.removePrefix("v")
			?.let { modsDir.resolve("meowmod-$it.jar").toAbsolutePath().toString() }

		val relaunchLine = when {
			relaunch == null -> ""
			isWindows -> "start \"\" $relaunch"
			else -> "nohup $relaunch >/dev/null 2>&1 &"
		}

		// A name of its own per run: a script that is already waiting for this process must never
		// be overwritten while cmd is still reading it.
		val script = gameDir.resolve(
			"meowmod-updater-${pid}-${scriptCount.incrementAndGet()}" + if (isWindows) ".cmd" else ".sh"
		)
		if (isWindows) {			// Wait-Process blocks until this client is gone, so nothing here touches the jar
			// while it is still open. The retry loop is a fallback for a handle that outlives
			// the process (an antivirus scan), it is bounded so it can never spin, and it
			// sleeps through PowerShell rather than `timeout`, which a Git install on PATH
			// shadows with the Unix one. Every line that could print is redirected: the whole
			// point is that this script stays silent.
			val lines = mutableListOf(
				"@echo off",
				"cd /d \"$dir\"",
				"powershell -NoProfile -NonInteractive -Command \"Wait-Process -Id $pid -ErrorAction SilentlyContinue\" >nul 2>&1"
			)
			if (staged != null && target != null) {
				lines += listOf(
					// Another script may have consumed the staged file first; that only means there is
					// nothing left to swap, not that the relaunch should be skipped.
					"if not exist \"$staged\" goto relaunch",
					"set /a tries=0",
					":swap",
					"del /f /q \"$old\" >nul 2>&1",
					"if not exist \"$old\" goto install",
					"set /a tries+=1",
					"if %tries% GEQ 30 goto relaunch",
					"powershell -NoProfile -NonInteractive -Command \"Start-Sleep -Seconds 1\" >nul 2>&1",
					"goto swap",
					":install",
					"move /y \"$staged\" \"$target\" >nul 2>&1"
				)
			}
			lines += ":relaunch"
			if (relaunchLine.isNotEmpty()) lines += relaunchLine
			lines += listOf(":cleanup", "(goto) 2>nul & del \"%~f0\" >nul 2>&1")
			Files.writeString(script, lines.joinToString("\r\n") + "\r\n")
		} else {
			val lines = mutableListOf(
				"#!/bin/sh",
				"cd \"$dir\" || exit 1",
				"while kill -0 $pid 2>/dev/null; do sleep 1; done"
			)
			if (staged != null && target != null) {
				lines += listOf(
					"if [ -e \"$staged\" ]; then",
					"rm -f \"$old\"",
					"mv -f \"$staged\" \"$target\"",
					"fi"
				)
			}
			if (relaunchLine.isNotEmpty()) lines += relaunchLine
			lines += "rm -f \"${'$'}0\""
			Files.writeString(script, lines.joinToString("\n") + "\n")
			script.toFile().setExecutable(true)
		}

		return try {
			if (isWindows) {
				ProcessBuilder("cmd.exe", "/c", "start", "", "/min", script.toString()).start()
			} else {
				ProcessBuilder("sh", script.toString()).start()
			}
			LOGGER.info(
				"[MeowMod] Swap script launched at {} (relaunch {})",
				script,
				if (relaunchLine.isEmpty()) "not requested" else "included"
			)
			true
		} catch (e: Exception) {
			LOGGER.warn("[MeowMod] Failed to launch the swap script: {}", e.message)
			false
		}
	}

	/**
	 * The command line that starts this client again. ProcessHandle cannot supply it on Windows, so
	 * it is rebuilt from the running JVM instead (see RelaunchCommand).
	 */
	private fun relaunchCommand(): String? = RelaunchCommand.current()

	/** The jar this client is running from, or null when there is none (dev environment). */
	private fun jarFile(): File? {
		val file = try {
			val location = MeowMod::class.java.protectionDomain?.codeSource?.location ?: return null
			File(location.toURI())
		} catch (e: Exception) {
			LOGGER.warn("[MeowMod] Cannot read the running jar location: {}", e.message)
			return null
		}
		return if (file.isDirectory) null else file
	}

	private fun httpClient(): HttpClient = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build()

	/**
	 * The kill switch: end this session, install a newer build when one exists, and start the
	 * client again. Runs off the render thread, and only once: a second click while the first is
	 * still waiting on the network is ignored.
	 */
	fun killAndRelaunch() {
		if (!restartRequested.compareAndSet(false, true)) return
		ChatUtils.sendMessage("&cKill switch: ending this session, then starting the client again.", true)
		Thread({ relaunch() }, "MeowMod-KillSwitch").apply { isDaemon = true }.start()
	}

	private fun relaunch() {
		val gameDir = loader().gameDir
		// Ending the session without being able to start it again would only close the game, so
		// both pieces are checked, and the one that is missing is named.
		val currentJar = jarFile() ?: return cannotRelaunch("its own jar")
		if (relaunchCommand() == null) return cannotRelaunch("the command it was started with")

		if (restartScriptLaunched.get()) {
			// The auto updater is already restarting the client: a second restart would race it.
			ChatUtils.sendMessage("&cKill switch: a relaunch is already under way, not starting a second one.", true)
			restartRequested.set(false)
			return
		}

		val (staging, tag) = stageUpdate(gameDir)
		if (!install(gameDir, gameDir.resolve("mods"), staging, currentJar, tag, true)) {
			ChatUtils.sendMessage("&cKill switch: the relaunch script would not start; the session is left running.", true)
			restartRequested.set(false)
			return
		}

		if (tag != null) {
			ChatUtils.sendMessage("&aInstalling MeowMod &f$tag&a, then starting the client again.", true)
		} else {
			ChatUtils.sendMessage("&7MeowMod is up to date; starting the client again.", true)
		}
		endSession()
	}

	/**
	 * Refuses the restart and says which piece is missing, because closing the session without a
	 * way back would just end the game.
	 */
	private fun cannotRelaunch(what: String) {
		LOGGER.warn("[MeowMod] Kill switch: cannot see {}", what)
		ChatUtils.sendMessage("&cKill switch: MeowMod cannot see $what, so it cannot restart itself. Start the client from the launcher.", true)
		restartRequested.set(false)
	}

	/**
	 * Downloads a newer build into the staging file, if there is one. Returns the staged file and
	 * its tag, or (null, null) when already up to date or the check failed: the kill switch still
	 * restarts, it just has nothing new to install.
	 */
	private fun stageUpdate(gameDir: Path): Pair<Path?, String?> {
		if (loader().isDevelopmentEnvironment) return null to null
		return try {
			val client = httpClient()
			val candidate = fetchFromReleases(client) ?: fetchFromFolder(client)
			if (candidate == null) {
				LOGGER.info("[MeowMod] Kill switch: no update source found on {}", REPO)
				return null to null
			}
			if (compareVersions(parseVersion(candidate.tag), parseVersion(currentVersion())) <= 0) {
				LOGGER.info("[MeowMod] Kill switch: already up to date (v{})", currentVersion())
				return null to null
			}
			val staging = gameDir.resolve(STAGING_JAR)
			Files.deleteIfExists(staging)
			download(client, candidate.url, staging)
			LOGGER.info("[MeowMod] Kill switch: downloaded {} ({})", candidate.tag, candidate.name)
			staging to candidate.tag
		} catch (e: Exception) {
			// A failed check must not cost the user the restart they asked for.
			LOGGER.warn("[MeowMod] Kill switch: update check failed: {}", e.message)
			null to null
		}
	}

	/**
	 * Ends this session. The client is asked to shut down normally, and a watchdog halts the
	 * process if it is somehow still alive well after that: the swap script waits for this process
	 * to disappear, so a client that never exits would also never relaunch.
	 */
	private fun endSession() {
		LOGGER.info("[MeowMod] Ending the session for a relaunch")
		Thread({
			try {
				Thread.sleep(KILL_WATCHDOG_MS)
			} catch (_: InterruptedException) {
				return@Thread
			}
			LOGGER.warn("[MeowMod] The client did not exit; halting the process")
			Runtime.getRuntime().halt(0)
		}, "MeowMod-KillWatchdog").apply { isDaemon = true }.start()
		Minecraft.getInstance().execute { Minecraft.getInstance().stop() }
	}

	private fun parseVersion(version: String): List<Int> =
		version.trim()
			.removePrefix("v").removePrefix("V")
			.substringBefore('-').substringBefore('+')
			.split('.')
			.map { it.toIntOrNull() ?: 0 }

	private fun compareVersions(a: List<Int>, b: List<Int>): Int {
		for (i in 0 until maxOf(a.size, b.size)) {
			val x = a.getOrElse(i) { 0 }
			val y = b.getOrElse(i) { 0 }
			if (x != y) return x.compareTo(y)
		}
		return 0
	}
}
