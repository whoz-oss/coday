package io.whozoss.agentos.claudeCode

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.git.core.BoundedProcessOutput
import mu.KLogging
import org.springframework.stereotype.Component
import java.io.IOException
import java.util.concurrent.TimeoutException

/** Whether a user can run turns on Claude Code, as far as the CLI tells. Never carries a credential. */
sealed interface ClaudeCodeStatus {
    data object Disabled : ClaudeCodeStatus

    data class NotInstalled(
        val executable: String,
    ) : ClaudeCodeStatus

    data class UnsupportedVersion(
        val version: String,
        val minimumVersion: String,
    ) : ClaudeCodeStatus

    data class NotLoggedIn(
        val version: String,
        /** What the user runs in a terminal on the AgentOS host to sign in. */
        val loginCommand: String,
    ) : ClaudeCodeStatus

    data class LoggedIn(
        val version: String,
        /** `claude.ai` for a subscription login, `console` for an Anthropic Console (API billing) login. */
        val authMethod: String?,
        /** `pro`, `max`, `team`... for a subscription login. */
        val subscriptionType: String?,
        val email: String?,
    ) : ClaudeCodeStatus

    /** The CLI could not be queried: it timed out or printed something unexpected. */
    data class Unavailable(
        val reason: String,
    ) : ClaudeCodeStatus
}

/**
 * Asks the CLI, with the user's own configuration directory, for its version (`claude --version`) and
 * its login state (`claude auth status --json`). Each call starts two short processes: meant for a status
 * page, not for every turn.
 */
@Component
class ClaudeCodeStatusService(
    private val properties: ClaudeCodeProperties,
) {
    private val command = ClaudeCodeCommand(properties)

    fun status(userId: String): ClaudeCodeStatus {
        if (!properties.enabled) return ClaudeCodeStatus.Disabled
        return try {
            val versionOutput = run(userId, "--version")
            val version =
                ClaudeCodeVersion.parse(versionOutput.stdout)
                    ?: return ClaudeCodeStatus.Unavailable("Unexpected output of ${properties.executable} --version")
            if (!ClaudeCodeVersion.isAtLeast(version, properties.minimumVersion)) {
                return ClaudeCodeStatus.UnsupportedVersion(version, properties.minimumVersion)
            }
            loginState(version, userId)
        } catch (e: IOException) {
            logger.info { "Claude Code CLI not available as '${properties.executable}': ${e.message}" }
            ClaudeCodeStatus.NotInstalled(properties.executable)
        } catch (e: TimeoutException) {
            ClaudeCodeStatus.Unavailable("${properties.executable} did not answer within ${properties.statusTimeout}")
        }
    }

    private fun loginState(
        version: String,
        userId: String,
    ): ClaudeCodeStatus {
        // Exits with 1 when nobody is signed in, after printing the same JSON document.
        val output = run(userId, "auth", "status", "--json")
        val node =
            runCatching { MAPPER.readTree(output.stdout) }.getOrNull()?.takeIf { it.isObject }
                ?: return ClaudeCodeStatus.Unavailable("Unexpected output of ${properties.executable} auth status")
        if (!node.path("loggedIn").asBoolean(false)) {
            return ClaudeCodeStatus.NotLoggedIn(version, command.loginCommand(userId))
        }
        val authMethod = node.path("authMethod").textValue()
        return ClaudeCodeStatus.LoggedIn(
            version = version,
            authMethod = authMethod,
            subscriptionType = node.path("subscriptionType").textValue(),
            email = node.path("email").textValue().takeIf { authMethod == SUBSCRIPTION_AUTH_METHOD },
        )
    }

    private fun run(
        userId: String,
        vararg arguments: String,
    ): BoundedProcessOutput.Result {
        val configDirectory = ClaudeCodeProcessLauncher.createPrivateDirectories(command.configDirectory(userId))
        val builder =
            ProcessBuilder(listOf(properties.executable) + arguments)
                .directory(configDirectory.toFile())
        builder.environment().apply {
            clear()
            putAll(command.environment(userId, System.getenv()))
        }
        return BoundedProcessOutput.await(builder.start(), properties.statusTimeout, MAX_OUTPUT_CHARS)
    }

    companion object : KLogging() {
        const val SUBSCRIPTION_AUTH_METHOD = "claude.ai"
        private const val MAX_OUTPUT_CHARS = 64 * 1024
        private val MAPPER = jacksonObjectMapper()
    }
}

/** CLI versions such as `2.1.318`, as printed by `claude --version` ("2.1.318 (Claude Code)") and in the init event. */
object ClaudeCodeVersion {
    private val VERSION = Regex("""^\s*(\d{1,9}(?:\.\d{1,9})*)""")

    fun parse(text: String): String? = VERSION.find(text)?.groupValues?.get(1)

    fun isAtLeast(
        version: String,
        minimum: String,
    ): Boolean {
        val actual = parse(version)?.split('.')?.map { it.toInt() } ?: return false
        val required = parse(minimum)?.split('.')?.map { it.toInt() } ?: return true
        for (index in 0 until maxOf(actual.size, required.size)) {
            val difference = actual.getOrElse(index) { 0 } - required.getOrElse(index) { 0 }
            if (difference != 0) return difference > 0
        }
        return true
    }
}
