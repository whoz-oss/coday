package io.whozoss.agentos.plugins.bash

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

class WorkspaceBashProcessesSpec : StringSpec({
    "a workspace command can return while its redirected background job remains alive" {
        val directory = Files.createTempDirectory("workspace-bash-background-").toFile()
        val workspace = UUID.randomUUID().toString()
        try {
            val result = BashCommandExecutor.execute(
                command = "sleep 30 >/dev/null 2>&1 & echo \$! > background.pid; pwd -P",
                workingDirectory = directory,
                timeoutSeconds = 1,
                workspaceId = workspace,
            )

            val completed = result.shouldBeInstanceOf<BashExecutionResult.Completed>()
            completed.exitCode shouldBe 0
            completed.stdout.trim() shouldBe directory.canonicalPath
            ProcessHandle.of(directory.resolve("background.pid").readText().trim().toLong()).get().isAlive shouldBe true
        } finally {
            // A shell can exit before descendant polling observes its child. Always clean up
            // the fixture by its recorded pid as well; production cleanup also checks lsof.
            directory.resolve("background.pid").takeIf { it.exists() }?.let {
                ProcessHandle.of(it.readText().trim().toLong()).orElse(null)?.let { handle ->
                    handle.destroyForcibly()
                    // A Linux PID-1 test container may retain a dead orphan as a zombie.
                    withTimeout(5_000) { while (WorkspaceBashProcesses.isRunning(handle)) delay(10) }
                }
            }
            WorkspaceBashProcesses.release(workspace)
            directory.deleteRecursively()
        }
    }

    "workspace release stops a live shell and its child without stopping another workspace" {
        val directory = Files.createTempDirectory("workspace-bash-release-").toFile()
        val workspace = UUID.randomUUID().toString()
        val otherWorkspace = UUID.randomUUID().toString()
        val process = ProcessBuilder("/bin/bash", "-c", "sleep 30 & echo \$! > child.pid; wait")
            .directory(directory).start()
        val other = ProcessBuilder("sleep", "30").start()
        try {
            withTimeout(5_000) {
                while (!directory.resolve("child.pid").exists() || directory.resolve("child.pid").readText().isBlank()) delay(10)
            }
            val child = ProcessHandle.of(directory.resolve("child.pid").readText().trim().toLong()).get()
            WorkspaceBashProcesses.track(workspace, process)
            WorkspaceBashProcesses.track(otherWorkspace, other)

            BashToolProvider().releaseWorkspace(workspace, directory.path)

            process.isAlive shouldBe false
            WorkspaceBashProcesses.isRunning(child) shouldBe false
            other.isAlive shouldBe true
            // Repeated cleanup is safe after all handles have been released.
            BashToolProvider().releaseWorkspace(workspace, directory.path)
        } finally {
            WorkspaceBashProcesses.release(workspace)
            WorkspaceBashProcesses.release(otherWorkspace)
            process.destroyForcibly()
            other.destroyForcibly()
            directory.deleteRecursively()
        }
    }

    "workspace release lets a process handle SIGTERM before forcing it" {
        val directory = Files.createTempDirectory("workspace-bash-term-").toFile()
        val workspace = UUID.randomUUID().toString()
        // A daemon's shutdown hook takes a moment on TERM; SIGKILL right after TERM would cut it short.
        val process = ProcessBuilder(
            "/bin/bash", "-c",
            "trap 'sleep 0.5; echo graceful > terminated; exit 0' TERM; echo ready > ready; while true; do sleep 0.05; done",
        ).directory(directory).start()
        try {
            withTimeout(5_000) { while (!directory.resolve("ready").exists()) delay(10) }
            WorkspaceBashProcesses.track(workspace, process)

            WorkspaceBashProcesses.release(workspace)

            process.isAlive shouldBe false
            directory.resolve("terminated").readText().trim() shouldBe "graceful"
        } finally {
            process.destroyForcibly()
            WorkspaceBashProcesses.release(workspace)
            directory.deleteRecursively()
        }
    }

    "Linux zombies do not prevent cleanup but stopped processes remain running".config(
        enabled = Files.exists(java.nio.file.Path.of("/proc/self/stat")),
    ) {
        val directory = Files.createTempDirectory("workspace-bash-zombie-").toFile()
        val workspace = UUID.randomUUID().toString()
        val parent = ProcessBuilder("/bin/bash", "-c", "sleep 0.2 & echo \$! > child.pid; kill -STOP \$\$")
            .directory(directory).start()
        try {
            withTimeout(5_000) {
                while (!directory.resolve("child.pid").exists() || directory.resolve("child.pid").readText().isBlank()) delay(10)
            }
            val child = ProcessHandle.of(directory.resolve("child.pid").readText().trim().toLong()).get()
            withTimeout(5_000) {
                while (WorkspaceBashProcesses.isRunning(child)) delay(10)
            }
            // Its stopped parent cannot reap it yet: this is an actual zombie, not a missing pid.
            child.isAlive shouldBe true
            WorkspaceBashProcesses.isRunning(parent.toHandle()) shouldBe true
            WorkspaceBashProcesses.track(workspace, parent)
            WorkspaceBashProcesses.release(workspace)
            parent.waitFor()
            parent.isAlive shouldBe false
            WorkspaceBashProcesses.release(workspace)
        } finally {
            parent.destroyForcibly()
            WorkspaceBashProcesses.release(workspace)
            directory.deleteRecursively()
        }
    }
})
