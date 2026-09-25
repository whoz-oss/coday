package io.whozoss.agentos.git

import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.git.core.BoundedProcessOutput
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeoutException

/** Detects workspace processes even after an AgentOS restart or shell disown. No observation, no purge. */
@Component
class WorkspaceProcessGuard(
    @param:Value("\${agentos.git.process-inspector:lsof}") private val binary: String = "lsof",
    @param:Value("\${agentos.git.process-inspection-timeout:30s}") private val timeout: Duration = Duration.ofSeconds(30),
) {
    fun assertIdle(path: Path) {
        val process = try { ProcessBuilder(binary, "-t", "+D", path.toString()).start() }
        catch (_: Exception) { throw ConflictException("Cannot verify workspace processes; install lsof before cleanup") }
        val output = try {
            BoundedProcessOutput.await(process, timeout, 16_384)
        } catch (_: TimeoutException) {
            throw ConflictException("Workspace process inspection timed out")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ConflictException("Workspace process inspection was interrupted")
        } catch (_: Exception) {
            throw ConflictException("Workspace process inspection was incomplete")
        }
        if (output.truncated || output.stderr.isNotBlank()) {
            throw ConflictException("Workspace process inspection was incomplete")
        }
        if (output.exitCode != 1 || output.stdout.isNotBlank()) {
            throw ConflictException("Processes still hold files or a working directory in the workspace")
        }
    }
}
