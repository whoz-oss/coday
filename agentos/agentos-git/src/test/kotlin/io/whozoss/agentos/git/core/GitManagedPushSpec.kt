package io.whozoss.agentos.git.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.writeText

/** Real Git against local bare remotes: a managed push reads nothing from the shared configuration. */
class GitManagedPushSpec :
    StringSpec({
        timeout = 120_000
        val runner = GitCommandRunner(
            GitExecutionProperties(allowedRemoteProtocols = setOf("file"), defaultTimeout = Duration.ofSeconds(10)),
        )
        val credentials = GitCredentials.UsernamePassword("synthetic-user", "synthetic-test-token")

        fun git(directory: Path, vararg args: String): String {
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

        fun remote(): Path = Files.createTempDirectory("agentos-push-remote-").also { git(it, "init", "--quiet", "--bare") }

        fun workspace(): Path {
            val root = Files.createTempDirectory("agentos-push-local-")
            git(root, "init", "--quiet", "--initial-branch=main")
            git(root, "config", "user.name", "CI")
            git(root, "config", "user.email", "ci@example.com")
            git(root, "config", "commit.gpgsign", "false")
            root.resolve("README.md").writeText("initial\n")
            git(root, "add", ".")
            git(root, "commit", "--quiet", "-m", "initial")
            git(root, "switch", "--quiet", "-c", "feature")
            root.resolve("feature.txt").writeText("work\n")
            git(root, "add", ".")
            git(root, "commit", "--quiet", "-m", "work")
            return root
        }

        fun push(local: Path, url: String, vararg options: String): GitCommandResult =
            runner.run(
                GitInvocation(
                    listOf("push") + options + listOf("--", url, "refs/heads/feature:refs/heads/feature"),
                    gitDir = local.resolve(".git"),
                    credentials = credentials,
                ),
            )

        fun Path.url(): String = toUri().toString()

        "a push publishes the branch to the explicit remote and records its tracking ref" {
            val origin = remote()
            val local = workspace()

            val result = push(local, origin.url())

            result.shouldBeInstanceOf<GitCommandResult.Completed>().successful shouldBe true
            val head = git(local, "rev-parse", "HEAD")
            git(origin, "rev-parse", "refs/heads/feature") shouldBe head
            git(local, "rev-parse", "refs/remotes/origin/feature") shouldBe head
        }

        "shared configuration can neither redirect a push nor run a hook" {
            val origin = remote()
            val attacker = remote()
            val local = workspace()
            val marker = local.resolve("hook-fired")
            val hooks = Files.createDirectories(local.resolve("planted-hooks"))
            hooks.resolve("pre-push").writeText("#!/bin/sh\ntouch '$marker'\n")
            hooks.resolve("pre-push").toFile().setExecutable(true)
            git(local, "config", "url.${attacker.url()}.pushInsteadOf", origin.url())
            git(local, "config", "core.hooksPath", hooks.toString())
            // Control: ordinary Git obeys both and pushes to the attacker's remote.
            git(local, "push", "--quiet", origin.url(), "refs/heads/feature:refs/heads/feature")
            git(attacker, "rev-parse", "refs/heads/feature") shouldBe git(local, "rev-parse", "HEAD")
            marker.exists() shouldBe true
            Files.delete(marker)

            val result = push(local, origin.url())

            result.shouldBeInstanceOf<GitCommandResult.Completed>().successful shouldBe true
            git(origin, "rev-parse", "refs/heads/feature") shouldBe git(local, "rev-parse", "HEAD")
            marker.exists() shouldBe false
        }

        "a lease refuses to overwrite remote work it has not seen" {
            val origin = remote()
            val local = workspace()
            push(local, origin.url()).shouldBeInstanceOf<GitCommandResult.Completed>().successful shouldBe true
            val seen = git(local, "rev-parse", "HEAD")
            // Someone else advances the remote branch.
            val other = Files.createTempDirectory("agentos-push-other-")
            git(other, "clone", "--quiet", "--branch", "feature", origin.url(), ".")
            git(other, "-c", "user.name=Other", "-c", "user.email=other@example.com", "commit", "--quiet", "--allow-empty", "-m", "other")
            git(other, "push", "--quiet", "origin", "feature")
            val theirs = git(origin, "rev-parse", "refs/heads/feature")
            // The agent rewrites its branch.
            git(local, "commit", "--quiet", "--amend", "-m", "rewritten")

            push(local, origin.url()).shouldBeInstanceOf<GitCommandResult.Completed>().successful shouldBe false
            push(local, origin.url(), "--force-with-lease=refs/heads/feature:$seen")
                .shouldBeInstanceOf<GitCommandResult.Completed>().successful shouldBe false
            git(origin, "rev-parse", "refs/heads/feature") shouldBe theirs

            push(local, origin.url(), "--force-with-lease=refs/heads/feature:$theirs")
                .shouldBeInstanceOf<GitCommandResult.Completed>().successful shouldBe true
            git(origin, "rev-parse", "refs/heads/feature") shouldBe git(local, "rev-parse", "HEAD")
        }

        "only one branch can be pushed to the branch of the same name" {
            val origin = remote()
            val local = workspace()
            val refused = listOf(
                listOf("push", "--", origin.url(), "refs/heads/feature:refs/heads/main"),
                listOf("push", "--", origin.url(), ":refs/heads/feature"),
                listOf("push", "--", origin.url(), "+refs/heads/feature:refs/heads/feature"),
                listOf("push", "--", origin.url(), "refs/heads/*:refs/heads/*"),
                listOf("push", "--", origin.url(), "refs/tags/v1:refs/tags/v1"),
                listOf("push", "--mirror", "--", origin.url(), "refs/heads/feature:refs/heads/feature"),
                listOf("push", "--receive-pack=touch /tmp/pwned", "--", origin.url(), "refs/heads/feature:refs/heads/feature"),
                listOf("push", "--force-with-lease", "--", origin.url(), "refs/heads/feature:refs/heads/feature"),
                listOf("push", origin.url(), "refs/heads/feature:refs/heads/feature"),
            )

            refused.forEach { args ->
                runner.run(GitInvocation(args, gitDir = local.resolve(".git"), credentials = credentials))
                    .shouldBeInstanceOf<GitCommandResult.Failed>()
            }
            git(origin, "for-each-ref", "--format=%(refname)") shouldBe ""
        }
    })
