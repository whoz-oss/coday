package io.whozoss.agentos.plugins.bash

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class BashCommandExecutorSpec : StringSpec({
    "background jobs holding output pipes do not retain readers or lose later output" {
        val directory = Files.createTempDirectory("workspace-bash-pipes-").toFile()
        val workspace = UUID.randomUUID().toString()
        try {
            repeat(3) { index ->
                val result = BashCommandExecutor.execute(
                    "sleep 30 & echo \$! > child-$index.pid; echo result-$index; sleep 0.1",
                    directory,
                    timeoutSeconds = 1,
                    workspaceId = workspace,
                ).shouldBeInstanceOf<BashExecutionResult.Completed>()
                result.stdout shouldBe "result-$index\n"
            }
            CompletableFuture.supplyAsync { "available" }.get(1, TimeUnit.SECONDS) shouldBe "available"
            BashCommandExecutor.execute("echo after", directory, 1, workspaceId = workspace)
                .shouldBeInstanceOf<BashExecutionResult.Completed>().stdout shouldBe "after\n"
        } finally {
            directory.listFiles().orEmpty().filter { it.extension == "pid" }.forEach { file ->
                ProcessHandle.of(file.readText().trim().toLong()).ifPresent { it.destroyForcibly() }
            }
            WorkspaceBashProcesses.release(workspace)
            directory.deleteRecursively()
        }
    }

    "a workspace command runs with the family HOME and cache directory" {
        val directory = Files.createTempDirectory("workspace-bash-home-").toFile()
        val home = directory.resolve("support")
        try {
            val result = BashCommandExecutor.execute(
                "printf '%s|%s' \"\$HOME\" \"\$XDG_CACHE_HOME\"",
                directory,
                timeoutSeconds = 5,
                home = home,
            ).shouldBeInstanceOf<BashExecutionResult.Completed>()

            result.stdout shouldBe "${home.absolutePath}|${home.resolve(".cache").absolutePath}"
            home.resolve(".cache").isDirectory shouldBe true
        } finally {
            directory.deleteRecursively()
        }
    }

    "both pipes are drained after the output limit without blocking the shell" {
        val directory = Files.createTempDirectory("workspace-bash-volume-").toFile()
        try {
            val result = BashCommandExecutor.execute(
                "(head -c 2097152 /dev/zero | tr '\\0' e >&2) & head -c 2097152 /dev/zero | tr '\\0' o; wait",
                directory,
                timeoutSeconds = 10,
                maxOutputChars = 127,
            ).shouldBeInstanceOf<BashExecutionResult.Completed>()
            result.exitCode shouldBe 0
            result.stdout shouldBe "o".repeat(127)
            result.stderr shouldBe "e".repeat(127)
        } finally {
            directory.deleteRecursively()
        }
    }

    "multibyte output is decoded across pipe read boundaries and capped by characters" {
        val directory = Files.createTempDirectory("workspace-bash-unicode-").toFile()
        try {
            val result = BashCommandExecutor.execute(
                "printf 'é'; sleep 0.05; printf '漢😀fin'",
                directory,
                timeoutSeconds = 2,
                maxOutputChars = 4,
            ).shouldBeInstanceOf<BashExecutionResult.Completed>()
            result.stdout shouldBe "é漢😀"
            BashCommandExecutor.execute("echo discarded", directory, 1, maxOutputChars = 0)
                .shouldBeInstanceOf<BashExecutionResult.Completed>().stdout shouldBe ""
        } finally {
            directory.deleteRecursively()
        }
    }

    "timeout stops the workspace shell and its child while output is being produced" {
        val directory = Files.createTempDirectory("workspace-bash-timeout-").toFile()
        val workspace = UUID.randomUUID().toString()
        try {
            BashCommandExecutor.execute(
                "sleep 30 & echo \$! > child.pid; while :; do printf x; done",
                directory,
                timeoutSeconds = 1,
                maxOutputChars = 10,
                workspaceId = workspace,
            ).shouldBeInstanceOf<BashExecutionResult.Timeout>()
            WorkspaceBashProcesses.release(workspace)
            ProcessHandle.of(directory.resolve("child.pid").readText().trim().toLong())
                .map(WorkspaceBashProcesses::isRunning).orElse(false) shouldBe false
        } finally {
            WorkspaceBashProcesses.release(workspace)
            directory.deleteRecursively()
        }
    }
})
