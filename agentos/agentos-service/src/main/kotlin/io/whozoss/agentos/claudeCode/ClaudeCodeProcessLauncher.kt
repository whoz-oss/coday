package io.whozoss.agentos.claudeCode

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import mu.KLogging
import org.springframework.stereotype.Component
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * Starts the `claude` CLI for one turn, from a cleared environment, in the case's empty working directory,
 * with the user's own CLI configuration directory.
 *
 * At most [ClaudeCodeProperties.maxConcurrentProcesses] processes run at once on the instance; a turn
 * beyond that waits for a slot, and stops waiting if its coroutine is cancelled.
 */
@Component
class ClaudeCodeProcessLauncher(
    private val properties: ClaudeCodeProperties,
) {
    private val command = ClaudeCodeCommand(properties)
    private val slots = Semaphore(properties.maxConcurrentProcesses)

    /** The command the user runs in a terminal to sign in to Claude for AgentOS. */
    fun loginCommand(userId: String): String = command.loginCommand(userId)

    suspend fun launch(request: ClaudeCodeLaunchRequest): ClaudeCodeProcess {
        check(properties.enabled) { "The Claude Code runtime is disabled on this instance (agentos.claude-code.enabled)" }
        slots.acquire()
        var runDirectory: Path? = null
        try {
            return withContext(Dispatchers.IO) {
                createPrivateDirectories(command.configDirectory(request.userId))
                val workingDirectory = createPrivateDirectories(command.workingDirectory(request.userId, request.caseId))
                // Private to the service user: it holds the system prompt and the MCP bearer token.
                val directory = Files.createTempDirectory(RUN_DIRECTORY_PREFIX).also { runDirectory = it }
                val systemPromptFile = Files.writeString(directory.resolve("system-prompt.md"), request.systemPrompt)
                val mcpConfigFile =
                    request.mcpServer?.let { Files.writeString(directory.resolve("mcp.json"), command.mcpConfiguration(it)) }
                val builder =
                    ProcessBuilder(listOf(properties.executable) + command.arguments(request, systemPromptFile, mcpConfigFile))
                        .directory(workingDirectory.toFile())
                builder.environment().apply {
                    clear()
                    putAll(command.environment(request.userId, System.getenv()))
                }
                val process = builder.start()
                logger.info { "Started Claude Code (pid ${process.pid()}) for case ${request.caseId}, session ${request.session}" }
                ClaudeCodeProcess(process, directory, slots::release)
            }
        } catch (e: Throwable) {
            runDirectory?.toFile()?.deleteRecursively()
            slots.release()
            throw e
        }
    }

    companion object : KLogging() {
        private const val RUN_DIRECTORY_PREFIX = "agentos-claude-code-"

        /** Creates [directory] and its missing parents, readable by the service user only where the file system allows it. */
        internal fun createPrivateDirectories(directory: Path): Path {
            if (Files.isDirectory(directory)) return directory
            return if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
                Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            } else {
                Files.createDirectories(directory)
            }
        }
    }
}
