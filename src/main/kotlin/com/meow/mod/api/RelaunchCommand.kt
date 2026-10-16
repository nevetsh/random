package com.meow.mod.api

import java.lang.management.ManagementFactory

/**
 * Puts back together the command line this client was started with.
 *
 * ProcessHandle cannot report it on Windows: `commandLine()` and `arguments()` both come back
 * empty there, which is why a restart could never be launched before. Everything needed is
 * available elsewhere — the executable being run, the runtime's own JVM arguments, the class
 * path, and the main class with its arguments.
 */
object RelaunchCommand {
	/** The line that starts this client again, or null when a piece of it is missing. */
	fun current(): String? = build(
		executable = ProcessHandle.current().info().command().orElse(null),
		jvmArgs = ManagementFactory.getRuntimeMXBean().inputArguments,
		// The class path is not part of the runtime arguments, so it is put back separately.
		classPath = System.getProperty("java.class.path"),
		program = System.getProperty("sun.java.command")
	)

	/**
	 * Null rather than a guess when anything is missing: a half-built line would start the wrong
	 * client, or none at all, and the caller reports that instead of closing the session.
	 *
	 * [program] is the main class and its arguments as the JVM received them, already flattened
	 * into one string, so a path with a space in it cannot be split back apart. Nothing here can
	 * fix that; it is the one case where this rebuild is no better than the launcher's own line.
	 */
	fun build(
		executable: String?,
		jvmArgs: List<String>,
		classPath: String?,
		program: String?
	): String? {
		if (executable.isNullOrBlank() || classPath.isNullOrBlank() || program.isNullOrBlank()) return null
		val args = jvmArgs.filter { it.isNotBlank() }.joinToString(" ") { quote(it) }
		val prefix = if (args.isEmpty()) "" else "$args "
		return "\"$executable\" $prefix-cp \"$classPath\" $program"
	}

	/** Quotes an argument only when it would otherwise arrive as two. */
	private fun quote(arg: String): String = if (arg.contains(' ')) "\"$arg\"" else arg
}
