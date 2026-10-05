package io.whozoss.agentos.git

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.git.core.BoundedProcessOutput
import mu.KLogging
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeoutException

/**
 * Finds the processes that still use a workspace directory, even after an AgentOS restart or a
 * shell disown, through `lsof`. No observation, no purge: an inspection that fails keeps the files.
 */
@Component
@ConditionalOnProperty(prefix = "agentos.git.workspaces", name = ["enabled"], havingValue = "true")
class WorkspaceProcessGuard(
    private val binary: String = "lsof",
    private val timeout: Duration = Duration.ofSeconds(30),
) {
    /**
     * Stop the processes an agent left running in [path] (a background job, a tmux shell, an MCP
     * server): terminate, then kill what is still alive after [STOP_GRACE]. Only processes of the
     * service's own user are touched, never the service itself. [assertIdle] then confirms.
     */
    fun stopProcesses(path: Path) {
        val self = ProcessHandle.current()
        val user = self.info().user().orElse(null)
        val handles =
            inspect(path).stdout.lineSequence()
                .mapNotNull { it.trim().toLongOrNull() }
                .filter { it != self.pid() }
                .mapNotNull { ProcessHandle.of(it).orElse(null) }
                .filter { user != null && it.info().user().orElse(null) == user }
                .toList()
        if (handles.isEmpty()) return
        logger.info { "Stopping ${handles.size} process(es) still using workspace $path" }
        handles.forEach { it.destroy() }
        val deadline = System.nanoTime() + STOP_GRACE.toNanos()
        handles.forEach { handle ->
            val remaining = deadline - System.nanoTime()
            if (remaining > 0) runCatching { handle.onExit().get(remaining, java.util.concurrent.TimeUnit.NANOSECONDS) }
            if (handle.isAlive) handle.destroyForcibly()
        }
    }

    fun assertIdle(path: Path) {
        val output = inspect(path)
        if (output.exitCode != LSOF_NO_MATCH || output.stdout.isNotBlank()) {
            val holders = describeHolders(output.stdout).ifEmpty { "lsof exited with ${output.exitCode}" }
            throw ConflictException("Processes still hold files or a working directory in the workspace: $holders")
        }
    }

    /** The first holders by process id and executable name, never their arguments: those may carry secrets. */
    private fun describeHolders(pids: String): String =
        pids.lineSequence()
            .mapNotNull { it.trim().toLongOrNull() }
            .take(MAX_DESCRIBED_HOLDERS)
            .joinToString { pid ->
                val executable = ProcessHandle.of(pid).flatMap { it.info().command() }.map { " (${Path.of(it).fileName})" }
                pid.toString() + executable.orElse("")
            }

    /** `lsof` exits with [LSOF_NO_MATCH] when nothing uses the directory. */
    private fun inspect(path: Path): BoundedProcessOutput.Result {
        val process = try { ProcessBuilder(binary, "-t", "+D", path.toString()).start() }
        catch (e: Exception) { throw ConflictException("Cannot verify workspace processes; install lsof before cleanup", e) }
        val output = try {
            BoundedProcessOutput.await(process, timeout, MAX_INSPECTION_OUTPUT_CHARS)
        } catch (e: TimeoutException) {
            throw ConflictException("Workspace process inspection timed out", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ConflictException("Workspace process inspection was interrupted", e)
        } catch (e: Exception) {
            throw ConflictException("Workspace process inspection was incomplete", e)
        }
        if (output.truncated || output.stderr.isNotBlank()) {
            // lsof's own explanation, bounded, for the operator's log.
            val diagnostic = output.stderr.trim().take(MAX_DIAGNOSTIC_CHARS)
            val detail = if (diagnostic.isEmpty()) "" else ": $diagnostic"
            throw ConflictException("Workspace process inspection was incomplete$detail")
        }
        return output
    }

    companion object : KLogging() {
        private val STOP_GRACE: Duration = Duration.ofSeconds(5)

        /** Exit code of `lsof` when no process uses the inspected directory. */
        private const val LSOF_NO_MATCH = 1

        /** `lsof -t` prints one process id per line: this is thousands of processes. */
        private const val MAX_INSPECTION_OUTPUT_CHARS = 16_384

        /** Holders named in a refusal. The log only needs a lead, not the whole list. */
        private const val MAX_DESCRIBED_HOLDERS = 10

        /** Characters of lsof's error output kept in a refusal. */
        private const val MAX_DIAGNOSTIC_CHARS = 500
    }
}
