package io.whozoss.agentos.claudeCode

import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.tool.StandardTool
import mu.KLogging
import org.springframework.boot.web.context.WebServerInitializedEvent
import org.springframework.context.ApplicationListener
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Serves the AgentOS tools of the running Claude Code turns to their CLI, through a loopback MCP server
 * ([ClaudeCodeMcpController]).
 *
 * Each agent run opens a [ClaudeCodeToolRun]: its own endpoint `/internal/claude-code/mcp/<runId>` and a
 * random bearer token, both known only to the CLI it launches (in a private file) and dropped when the run
 * closes. A request whose token does not match is answered as an unknown run.
 */
@Component
class ClaudeCodeToolBridge(
    private val properties: ClaudeCodeProperties,
) : ApplicationListener<WebServerInitializedEvent> {
    private val runs = ConcurrentHashMap<UUID, ClaudeCodeToolRun>()

    @Volatile private var serverPort: Int? = null

    override fun onApplicationEvent(event: WebServerInitializedEvent) {
        // The management server, when it runs on its own port, has a namespace; the API server has none.
        if (event.applicationContext.serverNamespace == null) serverPort = event.webServer.port
    }

    /**
     * Opens a run serving [tools]; [handler] runs a call of one of them, on the thread of the MCP request.
     * Close the run when the agent run ends.
     */
    fun open(
        tools: Collection<StandardTool<*>>,
        handler: ClaudeCodeToolHandler,
    ): ClaudeCodeToolRun {
        val baseUrl =
            properties.bridgeUrl?.trimEnd('/')
                ?: serverPort?.let { "http://127.0.0.1:$it" }
                ?: throw IllegalStateException("The AgentOS port is not known yet: set agentos.claude-code.bridge-url")
        val id = UUID.randomUUID()
        val token = ByteArray(TOKEN_BYTES).also(RANDOM::nextBytes).let(Base64.getUrlEncoder().withoutPadding()::encodeToString)
        val server = ClaudeCodeMcpServer(url = "$baseUrl$PATH/$id", bearerToken = token, toolTimeout = properties.toolTimeout)
        return ClaudeCodeToolRun(id, server, tools, handler) { runs.remove(id) }.also { runs[id] = it }
    }

    /** The open run [runId] when [authorization] carries its token, else null. */
    fun find(
        runId: UUID,
        authorization: String?,
    ): ClaudeCodeToolRun? = runs[runId]?.takeIf { it.authorizes(authorization) }

    companion object : KLogging() {
        const val PATH = "/internal/claude-code/mcp"
        private const val TOKEN_BYTES = 32
        private val RANDOM = SecureRandom()
    }
}

/** Runs one tool call of a [ClaudeCodeToolRun]: the tool, its JSON arguments, and the CLI's `tool_use` id when given. */
fun interface ClaudeCodeToolHandler {
    fun call(
        tool: StandardTool<*>,
        arguments: String,
        toolUseId: String?,
    ): ClaudeCodeToolResult
}

/** What the CLI gets back from a tool call. */
data class ClaudeCodeToolResult(
    val text: String,
    val images: List<MessageContent.Image> = emptyList(),
    val isError: Boolean = false,
)

/** The tools of one agent run, as its CLI sees them. */
class ClaudeCodeToolRun internal constructor(
    val id: UUID,
    /** What the CLI is launched with to reach this run. */
    val mcpServer: ClaudeCodeMcpServer,
    tools: Collection<StandardTool<*>>,
    private val handler: ClaudeCodeToolHandler,
    private val onClose: () -> Unit,
) : AutoCloseable {
    /** The tools by the name the CLI knows them under ([exposedName]). */
    val tools: Map<String, StandardTool<*>> = tools.associateBy { exposedName(it.name) }

    private val inFlight = AtomicInteger()

    /** Whether a tool call is running: the CLI waits silently meanwhile. */
    val busy: Boolean
        get() = inFlight.get() > 0

    internal fun authorizes(authorization: String?): Boolean =
        authorization != null &&
            MessageDigest.isEqual(authorization.toByteArray(), "Bearer ${mcpServer.bearerToken}".toByteArray())

    /** Runs the tool the CLI calls [name], or returns null when there is none. */
    fun call(
        name: String,
        arguments: String,
        toolUseId: String?,
    ): ClaudeCodeToolResult? {
        val tool = tools[name] ?: return null
        inFlight.incrementAndGet()
        try {
            return handler.call(tool, arguments, toolUseId)
        } finally {
            inFlight.decrementAndGet()
        }
    }

    override fun close() = onClose()

    override fun toString(): String = "ClaudeCodeToolRun(id=$id, tools=${tools.size})"

    companion object {
        /** The CLI names a tool `mcp__agentos__<name>`, and the model API caps a tool name at 64 characters. */
        const val MAX_NAME_LENGTH = 64 - ClaudeCodeCommand.MCP_TOOL_PREFIX.length

        private val VALID_NAME = Regex("[A-Za-z0-9_-]{1,$MAX_NAME_LENGTH}")
        private val INVALID_CHARACTER = Regex("[^A-Za-z0-9_-]")
        private const val HASH_HEX_LENGTH = 8

        /**
         * [name] when the model API accepts it behind the MCP prefix; otherwise a readable prefix of it and a
         * hash of the whole name, which keeps two long names with the same beginning apart.
         */
        fun exposedName(name: String): String {
            if (VALID_NAME.matches(name)) return name
            val hash =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(name.toByteArray())).take(HASH_HEX_LENGTH)
            return name.replace(INVALID_CHARACTER, "_").take(MAX_NAME_LENGTH - HASH_HEX_LENGTH - 1) + "_" + hash
        }
    }
}
