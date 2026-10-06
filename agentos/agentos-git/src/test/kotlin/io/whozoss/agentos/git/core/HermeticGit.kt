package io.whozoss.agentos.git.core

import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Plain Git for test fixtures, with no global, system or inherited configuration: what a test sets
 * up never depends on the machine running it. Fails with Git's output when the command fails.
 */
internal fun git(
    directory: Path,
    vararg args: String,
): String {
    val process = ProcessBuilder(listOf("git", *args)).directory(directory.toFile()).redirectErrorStream(true).also {
        it.environment().clear()
        it.environment()["PATH"] = System.getenv("PATH") ?: "/usr/bin:/bin"
        it.environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
        it.environment()["GIT_CONFIG_SYSTEM"] = "/dev/null"
        it.environment()["GIT_TERMINAL_PROMPT"] = "0"
    }.start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { output }
    return output.trim()
}
