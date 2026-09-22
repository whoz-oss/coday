package io.whozoss.agentos.git

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Security contract of [GitCommandRunner].
 *
 * These cases encode the attacks that motivated the runner. Each one that claims "the runner
 * blocks X" is paired with an adversarial control proving a plain `git` invocation does *not*
 * block X — without the control, a test that plants a hook and observes nothing would pass even
 * if hooks never fired for an unrelated reason.
 *
 * Requires a `git` binary on PATH (always true wherever the repository itself was cloned).
 */
class GitCommandRunnerSpec :
    StringSpec({
        timeout = 120_000

        val properties = GitExecutionProperties(allowedRemoteProtocols = setOf("https", "file"))
        val runner = GitCommandRunner(properties)

        /** Plain git, inheriting the ambient environment: the behaviour the runner protects against. */
        fun rawGit(
            directory: Path,
            vararg args: String,
        ): String {
            val process =
                ProcessBuilder(listOf("git", *args))
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .also {
                        // Keep the developer's ~/.gitconfig out of the fixture, but otherwise leave
                        // the environment inherited, exactly as an unguarded call would.
                        it.environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
                        it.environment()["GIT_CONFIG_SYSTEM"] = "/dev/null"
                    }.start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(60, TimeUnit.SECONDS)
            return output
        }

        fun newRepository(): Path {
            val root = Files.createTempDirectory("agentos-git-spec-")
            rawGit(root, "init", "--quiet", "--initial-branch=main")
            rawGit(root, "config", "user.email", "ci@example.com")
            rawGit(root, "config", "user.name", "CI")
            rawGit(root, "config", "commit.gpgsign", "false")
            root.resolve("README.md").writeText("v1\n")
            rawGit(root, "add", "-A")
            rawGit(root, "commit", "--quiet", "-m", "base")
            return root
        }

        fun plantReferenceTransactionHook(
            repository: Path,
            marker: Path,
        ) {
            val hooks = repository.resolve(".git/hooks")
            Files.createDirectories(hooks)
            val hook = hooks.resolve("reference-transaction")
            hook.writeText("#!/bin/sh\necho fired >> \"$marker\"\nexit 0\n")
            runCatching { Files.setPosixFilePermissions(hook, PosixFilePermissions.fromString("rwx------")) }
        }

        "a hook planted in the shared git directory does not run under the trusted runner" {
            val repository = newRepository()
            val marker = repository.resolve("hook-fired.txt")
            plantReferenceTransactionHook(repository, marker)

            // Adversarial control: an unguarded git DOES execute the planted hook.
            rawGit(repository, "branch", "control-branch")
            marker.exists() shouldBe true

            Files.delete(marker)

            // The trusted runner performs the same ref update with hooks neutralised.
            val result =
                runner.run(
                    GitInvocation(
                        args = listOf("branch", "trusted-branch"),
                        gitDir = repository.resolve(".git"),
                        workTree = repository,
                    ),
                )

            result.shouldBeCompletedSuccessfully()
            marker.exists() shouldBe false
            rawGit(repository, "branch", "--list").shouldContain("trusted-branch")
        }

        "a rewritten .git pointer does not redirect an invocation with a pinned git directory" {
            val clone = newRepository()
            val worktreeA = Files.createTempDirectory("agentos-git-spec-a-").resolve("wt")
            val worktreeB = Files.createTempDirectory("agentos-git-spec-b-").resolve("wt")
            rawGit(clone, "worktree", "add", "--quiet", "-b", "branch-a", worktreeA.toString(), "main")
            rawGit(clone, "worktree", "add", "--quiet", "-b", "branch-b", worktreeB.toString(), "main")

            // The pointer file is an ordinary file inside the Case Exchange: an agent can rewrite it.
            val trustedGitDirOfB = clone.resolve(".git/worktrees/wt")
            val realGitDirOfB = worktreeB.resolve(".git").readText().substringAfter("gitdir: ").trim()
            worktreeB.resolve(".git").writeText("gitdir: ${clone.resolve(".git/worktrees").resolve("wt")}\n")

            // Adversarial control: discovery-based git follows the rewritten pointer.
            worktreeB.resolve(".git").writeText(worktreeA.resolve(".git").readText())
            rawGit(worktreeB, "rev-parse", "--abbrev-ref", "HEAD").trim() shouldBe "branch-a"

            // The trusted runner is told where the repository is and ignores the pointer entirely.
            val branch =
                runner.runOrThrow(
                    GitInvocation(
                        args = listOf("rev-parse", "--abbrev-ref", "HEAD"),
                        gitDir = Path.of(realGitDirOfB),
                        workTree = worktreeB,
                    ),
                )
            branch shouldBe "branch-b"
            trustedGitDirOfB shouldNotBe null
        }

        "the git environment is an allow-list and never inherits service secrets" {
            val environment = runner.buildEnvironment(GitInvocation(args = listOf("status")))

            environment.keys shouldContainExactlyInAnyOrder
                listOf("PATH", "HOME", "LANG", "LC_ALL", "GIT_CONFIG_GLOBAL", "GIT_CONFIG_SYSTEM", "GIT_TERMINAL_PROMPT", "GIT_FLUSH")
            environment["GIT_CONFIG_GLOBAL"] shouldBe "/dev/null"
            environment["GIT_CONFIG_SYSTEM"] shouldBe "/dev/null"
            environment["GIT_TERMINAL_PROMPT"] shouldBe "0"
        }

        "credentials travel through the environment, never through the command line" {
            val invocation =
                GitInvocation(
                    args = listOf("fetch", "origin"),
                    credentials = GitCredentials.UsernamePassword("x-access-token", "super-secret-token"),
                )

            val environment = runner.buildEnvironment(invocation)

            environment[GitCommandRunner.ASKPASS_SECRET_VAR] shouldBe "super-secret-token"
            environment[GitCommandRunner.ASKPASS_USERNAME_VAR] shouldBe "x-access-token"
            environment.containsKey("GIT_ASKPASS") shouldBe true
            invocation.args.none { it.contains("super-secret-token") } shouldBe true
            // A leaked secret in a log line is as bad as one in argv.
            GitCredentials.UsernamePassword("u", "super-secret-token").toString().contains("super-secret-token") shouldBe false
        }

        "a local configuration that can hijack a command is refused" {
            val repository = newRepository()
            rawGit(repository, "config", "url.https://attacker.example/.insteadOf", "https://forge.example/")

            val failure =
                runCatching { runner.assertNoHostileLocalConfig(repository.resolve(".git")) }.exceptionOrNull()

            failure shouldNotBe null
            failure!!.message!! shouldContain "url."
        }

        "configuration inspection refuses truncated output rather than missing trailing dangerous keys" {
            val repository = newRepository()
            rawGit(repository, "config", "agentos.padding", "x".repeat(1000))
            rawGit(repository, "config", "url.https://other.example/.insteadOf", "https://forge.example/")
            val limited = GitCommandRunner(properties.copy(maxOutputChars = 256))
            shouldThrow<GitCommandException> { limited.assertNoHostileLocalConfig(repository.resolve(".git")) }
        }

        "configuration inspection refuses an unreadable or malformed configuration" {
            val repository = newRepository()
            repository.resolve(".git/config").writeText("[unterminated section")
            shouldThrow<GitCommandException> { runner.assertNoHostileLocalConfig(repository.resolve(".git")) }
        }

        "a clean managed clone passes the hostile configuration check" {
            val repository = newRepository()

            runCatching { runner.assertNoHostileLocalConfig(repository.resolve(".git")) }.isSuccess shouldBe true
        }
    })

private fun GitCommandResult.shouldBeCompletedSuccessfully() {
    check(this is GitCommandResult.Completed) { "expected a completed git command, got $this" }
    check(successful) { "expected exit code 0, got $exitCode: $stderr" }
}
