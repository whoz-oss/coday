package io.whozoss.agentos.git

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.whozoss.agentos.exception.ConflictException
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class WorkspaceProcessGuardSpec : StringSpec({
    "an independent process holding the workspace prevents deletion even without an in-memory registry" {
        val path = Files.createTempDirectory("agentos-process-guard-")
        val process = ProcessBuilder("sleep", "120").directory(path.toFile()).start()
        try { shouldThrow<ConflictException> { WorkspaceProcessGuard().assertIdle(path) } }
        finally { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS) }
        WorkspaceProcessGuard().assertIdle(path)
        Files.delete(path)
    }
    "an unavailable process inspector fails closed" {
        shouldThrow<ConflictException> { WorkspaceProcessGuard("/nonexistent/lsof").assertIdle(Files.createTempDirectory("agentos-process-unknown-")) }
    }
})
