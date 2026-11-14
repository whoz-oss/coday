package io.whozoss.agentos.plugins.bash

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.nio.file.Files

/**
 * Unit tests for [BashCommandExecutor], focused on the working-directory capture
 * (EXIT trap + marker) and on how it degrades when the trap cannot run.
 */
class BashCommandExecutorUnitSpec : StringSpec({

    val dir: File = Files.createTempDirectory("bash-executor-spec").toRealPath().toFile()

    fun run(
        command: String,
        timeoutSeconds: Long = 10L,
        maxOutputChars: Int = 100_000,
        environment: Map<String, String> = emptyMap(),
    ): BashExecutionResult =
        BashCommandExecutor.execute(command, dir, timeoutSeconds, maxOutputChars, environment)

    fun completed(command: String, maxOutputChars: Int = 100_000): BashExecutionResult.Completed =
        run(command, maxOutputChars = maxOutputChars).shouldBeInstanceOf<BashExecutionResult.Completed>()

    "should report the starting directory and leave stdout verbatim" {
        val result = completed("echo hello")
        result.stdout shouldBe "hello\n"
        result.workingDirectory shouldBe dir.path
        result.exitCode shouldBe 0
    }

    "should report the directory reached after a cd" {
        val result = completed("cd / && echo moved")
        result.stdout shouldBe "moved\n"
        result.workingDirectory shouldBe "/"
    }

    "should keep stdout without trailing newline intact" {
        val result = completed("printf abc")
        result.stdout shouldBe "abc"
        result.workingDirectory shouldBe dir.path
    }

    "should return empty stdout and the directory for a silent command" {
        val result = completed("true")
        result.stdout shouldBe ""
        result.workingDirectory shouldBe dir.path
    }

    "should report the directory on a non-zero exit" {
        val result = completed("exit 3")
        result.exitCode shouldBe 3
        result.stdout shouldBe ""
        result.workingDirectory shouldBe dir.path
    }

    "should strip the marker before truncating stdout" {
        val result = completed("printf abcdefghij", maxOutputChars = 4)
        result.stdout shouldBe "abcd"
        result.stdout shouldNotContain "__AGENTOS_PWD_"
        result.workingDirectory shouldBe dir.path
    }

    "should not shift line numbers of the user command" {
        val result = completed("echo \$LINENO")
        result.stdout shouldBe "1\n"
    }

    "exec should degrade cleanly: output intact, no marker, null directory" {
        val result = completed("exec echo replaced")
        result.stdout shouldBe "replaced\n"
        result.stdout shouldNotContain "__AGENTOS_PWD_"
        result.workingDirectory shouldBe null
    }

    "a user EXIT trap should degrade cleanly: output intact, no marker, null directory" {
        val result = completed("trap 'echo bye' EXIT; echo hi")
        result.stdout shouldBe "hi\nbye\n"
        result.stdout shouldNotContain "__AGENTOS_PWD_"
        result.workingDirectory shouldBe null
    }

    "a command with a syntax error should not leak the marker" {
        val result = completed("echo (")
        result.stdout shouldNotContain "__AGENTOS_PWD_"
        result.workingDirectory shouldBe null
        (result.exitCode != 0) shouldBe true
    }

    "an environment variable passed to the executor should be visible to the command" {
        val result =
            run("echo \$FOO-\$BAR", environment = mapOf("FOO" to "foo value", "BAR" to "bar"))
                .shouldBeInstanceOf<BashExecutionResult.Completed>()
        result.stdout shouldBe "foo value-bar\n"
    }

    "an environment variable should not leak into the next execution" {
        run("true", environment = mapOf("LEAKY_VAR" to "x"))
        val result = completed("printf %s \"\${LEAKY_VAR-unset}\"")
        result.stdout shouldBe "unset"
    }

    "a command exceeding the timeout should return Timeout" {
        val result = run("sleep 10", timeoutSeconds = 1L)
        result.shouldBeInstanceOf<BashExecutionResult.Timeout>().timeoutSeconds shouldBe 1L
    }
})
