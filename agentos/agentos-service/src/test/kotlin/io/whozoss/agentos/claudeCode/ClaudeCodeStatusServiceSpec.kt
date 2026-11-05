package io.whozoss.agentos.claudeCode

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.whozoss.agentos.git.PosixOnly
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

@EnabledIf(PosixOnly::class)
class ClaudeCodeStatusServiceSpec :
    StringSpec({
        timeout = 30_000
        val directories = mutableListOf<Path>()
        fun directory(): Path = Files.createTempDirectory("agentos-claude-code-status-").also { directories.add(it) }
        afterTest {
            directories.forEach { it.toFile().deleteRecursively() }
            directories.clear()
        }

        fun service(
            executable: String,
            enabled: Boolean = true,
            configRoot: Path = directory(),
        ) = ClaudeCodeStatusService(
            ClaudeCodeProperties(
                enabled = enabled,
                executable = executable,
                configRoot = configRoot,
                minimumVersion = "2.1.317",
                statusTimeout = Duration.ofSeconds(2),
            ),
        )

        /** A CLI answering `--version` with [version] and `auth status --json` with [authStatus] and [authExit]. */
        fun cli(
            version: String = "2.1.318 (Claude Code)",
            authStatus: String,
            authExit: Int = 0,
        ) = FakeClaudeCli(
            directory(),
            """
            if [ "${'$'}1" = "--version" ]; then echo '$version'; exit 0; fi
            cat <<'JSON'
            $authStatus
            JSON
            exit $authExit
            """.trimIndent(),
        )

        val loggedIn =
            """{"loggedIn":true,"authMethod":"claude.ai","apiProvider":"firstParty","email":"user@example.com","subscriptionType":"max"}"""

        "a subscription login reports its plan and the account email" {
            service(cli(authStatus = loggedIn).executable.toString()).status("user-1") shouldBe
                ClaudeCodeStatus.LoggedIn(version = "2.1.318", authMethod = "claude.ai", subscriptionType = "max", email = "user@example.com")
        }

        "a Console login is reported without an email" {
            val console = """{"loggedIn":true,"authMethod":"console","email":"user@example.com"}"""

            service(cli(authStatus = console).executable.toString()).status("user-1") shouldBe
                ClaudeCodeStatus.LoggedIn(version = "2.1.318", authMethod = "console", subscriptionType = null, email = null)
        }

        "a CLI nobody signed in to gives the login command for the user's directory" {
            val root = directory()
            val notLoggedIn = """{"loggedIn":false,"authMethod":"none","apiProvider":"firstParty"}"""

            service(cli(authStatus = notLoggedIn, authExit = 1).executable.toString(), configRoot = root).status("user-1") shouldBe
                ClaudeCodeStatus.NotLoggedIn(version = "2.1.318", loginCommand = "CLAUDE_CONFIG_DIR='$root/user-1' claude auth login")
        }

        "the CLI is asked with the user's configuration directory" {
            val root = directory()
            val cli = cli(authStatus = loggedIn)

            service(cli.executable.toString(), configRoot = root).status("user-1")

            cli.environment()["CLAUDE_CONFIG_DIR"] shouldBe root.resolve("user-1").toString()
            cli.arguments() shouldBe listOf("auth", "status", "--json")
        }

        "a CLI older than the minimum version is refused before its login is read" {
            service(cli(version = "2.1.300 (Claude Code)", authStatus = loggedIn).executable.toString()).status("user-1") shouldBe
                ClaudeCodeStatus.UnsupportedVersion(version = "2.1.300", minimumVersion = "2.1.317")
        }

        "a missing executable is reported as not installed" {
            service("/nonexistent/claude").status("user-1") shouldBe ClaudeCodeStatus.NotInstalled("/nonexistent/claude")
        }

        "a CLI that does not answer in time is reported as unavailable" {
            val slow = FakeClaudeCli(directory(), "sleep 10")

            service(slow.executable.toString()).status("user-1").shouldBeInstanceOf<ClaudeCodeStatus.Unavailable>()
        }

        "an unreadable login state is reported as unavailable" {
            service(cli(authStatus = "not json").executable.toString()).status("user-1")
                .shouldBeInstanceOf<ClaudeCodeStatus.Unavailable>()
        }

        "nothing runs while the runtime is disabled" {
            val cli = cli(authStatus = loggedIn)

            service(cli.executable.toString(), enabled = false).status("user-1") shouldBe ClaudeCodeStatus.Disabled
            Files.exists(cli.directory.resolve("argv")) shouldBe false
        }

        "versions compare numerically, part by part" {
            ClaudeCodeVersion.parse("2.1.318 (Claude Code)") shouldBe "2.1.318"
            ClaudeCodeVersion.parse("Claude Code") shouldBe null
            ClaudeCodeVersion.isAtLeast("2.1.318", "2.1.317") shouldBe true
            ClaudeCodeVersion.isAtLeast("2.1.317", "2.1.317") shouldBe true
            ClaudeCodeVersion.isAtLeast("2.1.40", "2.1.317") shouldBe false
            ClaudeCodeVersion.isAtLeast("3.0", "2.1.317") shouldBe true
            ClaudeCodeVersion.isAtLeast("2.1", "2.1.0") shouldBe true
            ClaudeCodeVersion.isAtLeast("unknown", "2.1.317") shouldBe false
        }
    })
