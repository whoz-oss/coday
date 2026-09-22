package io.whozoss.agentos.git

import io.whozoss.agentos.exception.ConflictException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Detects workspace processes even after an AgentOS restart or shell disown. No observation, no purge. */
@Component
class WorkspaceProcessGuard(@param:Value("\${agentos.git.process-inspector:lsof}") private val binary: String = "lsof") {
    fun assertIdle(path: Path) {
        val process = try { ProcessBuilder(binary, "-t", "+D", path.toString()).start() }
        catch (e: Exception) { throw ConflictException("Cannot verify workspace processes; install lsof before cleanup") }
        val out = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().use { it.readText() } }
        val err = CompletableFuture.supplyAsync { process.errorStream.bufferedReader().use { it.readText() } }
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) throw ConflictException("Workspace process inspection timed out")
            if (err.get(5, TimeUnit.SECONDS).isNotBlank()) throw ConflictException("Workspace process inspection was incomplete")
            if (process.exitValue() != 1 || out.get(5, TimeUnit.SECONDS).isNotBlank()) {
                throw ConflictException("Processes still hold files or a working directory in the workspace")
            }
        } finally { if (process.isAlive) process.destroyForcibly() }
    }
}
