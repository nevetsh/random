package com.meow.mod

import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Limits MeowMod to the accounts named by the licence feed. The list lives on the licence bot,
// not in this repository, and is re-read every few minutes so a change lands without a restart.
object Access {
	private const val FEED = "https://mm.popsplit.top/licenses.json"
	private const val REFRESH_MINUTES = 5L

	private val client: HttpClient = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.build()

	private val worker = Executors.newSingleThreadScheduledExecutor { task ->
		Thread(task, "MeowMod-Licences").apply { isDaemon = true }
	}

	@Volatile
	private var licensed: Set<UUID> = emptySet()

	@Volatile
	var authorized: Boolean = false
		private set

	// True once the feed has been read at least once, so the "not licensed" notice is not shown
	// while the first request is still in flight.
	@Volatile
	var settled: Boolean = false
		private set

	@Volatile
	var notified: Boolean = false

	@Volatile
	var lastError: String? = null
		private set

	fun start(uuid: UUID?) {
		check(uuid)
		// First read on the worker, never on the thread that is bringing the client up.
		worker.scheduleWithFixedDelay({ refresh() }, 0, REFRESH_MINUTES, TimeUnit.MINUTES)
	}

	fun check(uuid: UUID?): Boolean {
		authorized = uuid != null && uuid in licensed
		return authorized
	}

	private fun refresh() {
		try {
			val request = HttpRequest.newBuilder(URI.create(FEED))
				.timeout(Duration.ofSeconds(15))
				.header("User-Agent", "MeowMod/" + MeowMod.VERSION)
				.GET()
				.build()
			val response = client.send(request, HttpResponse.BodyHandlers.ofString())
			if (response.statusCode() != 200) {
				lastError = "HTTP " + response.statusCode()
				return
			}
			licensed = parse(response.body())
			lastError = null
		} catch (error: Exception) {
			// Keep whatever the last good list said; a dropped connection must not revoke anyone.
			lastError = error.message ?: error.javaClass.simpleName
		} finally {
			settled = true
		}
	}

	private fun parse(body: String): Set<UUID> {
		val result = mutableSetOf<UUID>()
		try {
			val root = JsonParser.parseString(body)
			if (!root.isJsonObject) return result
			val list = root.asJsonObject.getAsJsonArray("licenses") ?: return result
			list.forEach { entry ->
				val raw = when {
					entry.isJsonObject -> entry.asJsonObject.get("uuid")?.asString
					entry.isJsonPrimitive -> entry.asString
					else -> null
				}
				uuidOf(raw)?.let { result.add(it) }
			}
		} catch (_: Exception) {
		}
		return result
	}

	private fun uuidOf(raw: String?): UUID? {
		val text = raw?.trim()?.replace("-", "") ?: return null
		if (text.length != 32) return null
		return try {
			UUID.fromString(text.replaceFirst(Regex("(.{8})(.{4})(.{4})(.{4})(.{12})"), "$1-$2-$3-$4-$5"))
		} catch (_: Exception) {
			null
		}
	}
}
