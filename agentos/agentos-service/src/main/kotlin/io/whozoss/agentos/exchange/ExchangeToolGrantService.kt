package io.whozoss.agentos.exchange

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.tool.ToolRegistryService
import io.whozoss.agentos.tool.ToolResolverService
import mu.KLogging
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path

/**
 * A resolved decision to expose one exchange scope to an agent.
 *
 * Only ever produced by [ExchangeToolGrantService]; the absence of an instance (a null
 * [ExchangeGrant]) *is* the "not granted" case, so the tools of a scope an agent opted out of cannot
 * be built by accident.
 *
 * [allowedTools] mirrors the value carried by the agent's `integrations` map: null exposes every
 * tool the file-plugin provides, a non-empty list restricts to those names (bare or
 * `<configName>__<tool>`). An empty list never reaches here — it is the opt-out and yields no grant.
 */
data class ExchangeGrant(
    val allowedTools: List<String>?,
)

/**
 * Owns the exchange → file-plugin grant: whether a scope is exposed to an agent, and with which
 * plugin configuration.
 *
 * Split out of [io.whozoss.agentos.agent.AgentServiceImpl] so that the Spring-bound tuning
 * ([ExchangeToolsConfigProperties]) and the JSON node it produces live next to the rest of the
 * exchange and can be exercised without standing up an agent. `AgentServiceImpl` keeps what only it
 * knows — the run's case id and the invoking user's Namespace WRITE right — and calls
 * [resolveCaseGrant] / [resolveNamespaceGrant], then [grantTools].
 *
 * Deciding and granting are two calls on purpose: resolving is free, whereas [grantTools] creates
 * the scope directory and its namespace caller needs a permission query to compute `readOnly`. A
 * scope nobody was granted must cost neither.
 */
@Service
class ExchangeToolGrantService(
    private val properties: ExchangeToolsConfigProperties,
    private val storageProperties: ExchangeStorageConfigProperties,
    private val toolRegistryService: ToolRegistryService,
    private val toolResolverService: ToolResolverService,
    private val objectMapper: ObjectMapper,
) {
    /**
     * The case exchange grant implied by an agent's [integrations] map, or null when the scope must
     * not be exposed. See [resolveGrant] for the cases.
     */
    fun resolveCaseGrant(integrations: Map<String, List<String>?>?): ExchangeGrant? =
        resolveGrant(ExchangeIntegrationTypes.CASE, properties.caseEnabledByDefault, integrations)

    /** The namespace exchange grant implied by an agent's [integrations] map, or null. */
    fun resolveNamespaceGrant(integrations: Map<String, List<String>?>?): ExchangeGrant? =
        resolveGrant(ExchangeIntegrationTypes.NAMESPACE, properties.namespaceEnabledByDefault, integrations)

    /**
     * Builds the file-plugin tools for one exchange scope rooted at [root], filtered through
     * [allowedTools].
     *
     * Materialises [root] before building the tools even for a read-only grant: the file-plugin's
     * BoundaryPathResolver canonicalises `rootPath` (toRealPath) at construction, which throws if the
     * directory does not exist. This is why a read-only resolution (e.g. the debug getDefinition
     * endpoint) still creates an empty scope dir — and why an opt-out has to be caught by
     * [resolveGrant], upstream of this call, rather than by the tool-name filter.
     *
     * Fail-closed and side-effect free when the file-plugin is not loaded: no tools, no directory.
     */
    fun grantTools(
        root: Path,
        readOnly: Boolean,
        configName: String,
        allowedTools: List<String>?,
        toolContext: ToolContext,
    ): List<StandardTool<*>> {
        val filePlugin = toolRegistryService.findPlugin(ExchangeIntegrationTypes.FILE_ACCESS)
        return when (filePlugin) {
            null -> emptyList()
            else -> {
                Files.createDirectories(root)
                logger.info {
                    "Granting $configName (FILE_ACCESS, readOnly=$readOnly) " +
                        "to agent '${toolContext.agentName}' at $root"
                }
                // Honour the per-tool allowlist via the same matcher every other integration uses
                // (accepts both bare and `configName__tool` forms); null = all tools.
                filePlugin
                    .provideTools(buildFileToolConfig(root, readOnly), configName, toolContext)
                    .filter { toolResolverService.isToolAllowed(it.name, configName, allowedTools) }
            }
        }
    }

    /**
     * Resolves the enablement cases for one built-in exchange integration key:
     * - key absent           → [enabledByDefault] decides; a default grant exposes every tool;
     * - key present, null    → the agent enables the scope with every tool;
     * - key present, `[]`    → the agent opts out: no grant, hence no scope directory;
     * - key present, filled  → the agent enables the scope, restricted to those tool names.
     *
     * The empty list is a genuine opt-out rather than an empty allow-list.
     * [ToolResolverService.isToolAllowed] would already reject every tool, but [grantTools] creates
     * the scope directory before that filter runs, so the decision has to short-circuit here to keep
     * the exchange free of phantom directories.
     *
     * `containsKey` and `get` are both needed: a key absent and a key mapped to null are
     * indistinguishable through `get` alone, yet they mean opposite things here.
     */
    private fun resolveGrant(
        integrationKey: String,
        enabledByDefault: Boolean,
        integrations: Map<String, List<String>?>?,
    ): ExchangeGrant? {
        val declared = integrations?.containsKey(integrationKey) == true
        val declaration = integrations?.get(integrationKey)
        return when {
            !declared && enabledByDefault -> ExchangeGrant(allowedTools = null)
            !declared -> null
            declaration == null -> ExchangeGrant(allowedTools = null)
            declaration.isEmpty() -> null
            else -> ExchangeGrant(allowedTools = declaration)
        }
    }

    /**
     * The configuration node handed to the file-plugin's `provideTools`.
     *
     * Every key the plugin knows is emitted, so its compiled fallbacks only ever apply to a jar older
     * than a key. [root] and [readOnly] come from the caller (computed per run); `readMaxSizeMb`
     * derives from the exchange read cap so the agent's read tools honour the same limit as the REST
     * read/download path rather than the plugin's smaller built-in default — the plugin key is
     * megabytes, floored at 1.
     */
    private fun buildFileToolConfig(
        root: Path,
        readOnly: Boolean,
    ): ObjectNode {
        val node =
            objectMapper
                .createObjectNode()
                .put("rootPath", root.toAbsolutePath().toString())
                .put("readOnly", readOnly)
                .put("readMaxSizeMb", (storageProperties.readMaxSizeBytes / (1024 * 1024)).coerceAtLeast(1))
                .put("imageMaxDimension", properties.imageMaxDimension)
                // Clamped: the JPEG writer rejects a quality outside [0, 1] with an
                // IllegalArgumentException raised deep inside a tool call, long after the bad value
                // was set. Nothing validates the bound properties themselves.
                .put("imageJpegQuality", properties.imageJpegQuality.coerceIn(0f, 1f))
                .put("imageMaxSourcePixels", properties.imageMaxSourcePixels)
                .put("imagePassThroughMaxBytes", properties.imagePassThroughMaxBytes)
                .put("documentMaxOutputChars", properties.documentMaxOutputChars)
                .put("documentMaxAttachedImages", properties.documentMaxAttachedImages)
                .put("documentMaxTableColumns", properties.documentMaxTableColumns)
                .put("documentMaxCellChars", properties.documentMaxCellChars)
        // The plugin reads this key with `takeIf { it.isArray }`, so it must be a real ArrayNode —
        // an empty one when nothing is configured, never a missing key or a scalar. putArray returns
        // the ArrayNode, hence the separate statement.
        val denyPatterns = node.putArray("extraDenyPatterns")
        properties.extraDenyPatterns.forEach { denyPatterns.add(it) }
        return node
    }

    companion object : KLogging()
}
