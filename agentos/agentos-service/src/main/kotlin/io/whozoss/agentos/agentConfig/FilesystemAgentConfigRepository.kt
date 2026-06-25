package io.whozoss.agentos.agentConfig

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.whozoss.agentos.namespace.NamespaceRepository
import io.whozoss.agentos.plugin.filesystem.FilesystemYamlCacheRegistry
import io.whozoss.agentos.sdk.entity.EntityMetadata
import mu.KLogging
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

/**
 * Decorator over a delegate [AgentConfigRepository] that augments [findByParent]
 * and [findAvailableByNamespaceIdAndUserId] with [AgentConfig] entries loaded from
 * YAML files on the filesystem.
 *
 * All write operations ([save], [delete], [deleteByParent]) are forwarded to the
 * delegate unchanged — the filesystem is never written.
 *
 * Filesystem reads are cached per directory with a configurable [ttl] (default
 * 5 minutes) to avoid repeated I/O on hot paths such as agent-name autocomplete.
 */
class FilesystemAgentConfigRepository(
    private val delegate: AgentConfigRepository,
    private val namespaceRepository: NamespaceRepository,
    ttl: Duration = Duration.ofMinutes(5),
) : AgentConfigRepository by delegate {
    private val yamlMapper =
        ObjectMapper(YAMLFactory()).registerModule(KotlinModule.Builder().build())

    private val cacheRegistry =
        FilesystemYamlCacheRegistry(
            parser = ::parseYamlFile,
            ttl = ttl,
        )

    override fun findAvailableByNamespaceIdAndUserId(
        namespaceId: UUID,
        userId: UUID?,
        agentName: String?,
        withDisabled: Boolean,
    ): List<AgentConfig> {
        val fromDelegate =
            delegate.findAvailableByNamespaceIdAndUserId(
                namespaceId = namespaceId,
                userId = userId,
                agentName = agentName,
                withDisabled = withDisabled,
            )

        val fromFilesystem = retrieveFilesystemAgentConfigs(namespaceId)

        // Filesystem agents are implicitly deployed on the namespace — include them
        // in the available set, applying the same merge rules as findByParent:
        // persisted (delegate) results always win on name collision.
        val delegateNames = fromDelegate.mapTo(HashSet()) { it.name.lowercase() }
        val filesystemAdditions =
            fromFilesystem
                .filter { it.name.lowercase() !in delegateNames }
                .filter { agentName == null || it.name.equals(agentName, ignoreCase = true) }
                .map { it.copy(namespaceId = namespaceId) }
                .sortedBy { it.name }

        return fromDelegate + filesystemAdditions
    }

    private fun retrieveFilesystemAgentConfigs(namespaceId: UUID): List<AgentConfig> {
        val configPath = namespaceRepository.findByIds(listOf(namespaceId)).firstOrNull()?.configPath
        return if (configPath == null) {
            emptyList()
        } else {
            val directory = Path.of(configPath, AGENTS_SUBDIR)
            cacheRegistry.getAll(directory)
        }
    }

    /**
     * Returns agents for [parentId], optionally filtered to published ones.
     *
     * When [parentId] is null (platform-level agents), delegates directly without
     * filesystem augmentation — platform agents have no namespace configPath.
     *
     * When [parentId] is a namespace UUID, delegates to the underlying repository
     * with [withDisabled], then merges filesystem agents (always published by definition).
     */
    override fun findByParent(
        parentId: UUID?,
        withDisabled: Boolean,
    ): List<AgentConfig> {
        val persisted = delegate.findByParent(parentId, withDisabled)
        val fromFilesystem =
            parentId?.let { filesystemAgents(parentId, excludeNames = persisted.mapTo(HashSet()) { it.name.lowercase() }) } ?: emptyList()
        val merged = persisted + fromFilesystem
        logger.debug {
            "[FilesystemAgentConfigRepository] namespace=$parentId: ${persisted.size} persisted + ${fromFilesystem.size} filesystem = ${merged.size} total"
        }
        return merged
    }

    override fun findByParent(parentId: UUID?): List<AgentConfig> = findByParent(parentId, withDisabled = true)

    /**
     * Loads and returns agent configs from the filesystem for [parentId].
     *
     * When [excludeNames] is provided, any filesystem agent whose lowercased name
     * is in that set is dropped (persisted configs always win over filesystem ones).
     */
    private fun filesystemAgents(
        parentId: UUID,
        excludeNames: Set<String> = emptySet(),
    ): List<AgentConfig> {
        val configPath =
            namespaceRepository.findByIds(listOf(parentId)).firstOrNull()?.configPath
                ?: return emptyList()
        val directory = Path.of(configPath, AGENTS_SUBDIR)
        return cacheRegistry
            .getAll(directory)
            .filter { it.name.lowercase() !in excludeNames }
            .map { it.copy(namespaceId = parentId) }
            .sortedBy { it.name }
    }

    private fun parseYamlFile(file: Path): AgentConfig? {
        val model = yamlMapper.readValue(file.toFile(), AgentConfigYamlModel::class.java)
        if (model.name.isBlank()) {
            logger.warn { "[FilesystemAgentConfigRepository] Skipping $file: 'name' is blank" }
            return null
        }
        return AgentConfig(
            // Stable UUID derived from the name so identity survives restarts.
            // namespaceId is null here; it is overwritten in findByParent.
            metadata = EntityMetadata(id = UUID.nameUUIDFromBytes("filesystem-agent:${model.name}".toByteArray())),
            namespaceId = null,
            name = model.name,
            description = model.description,
            instructions = model.instructions,
            modelName = model.modelName,
            integrations = model.integrations,
            // Filesystem agents have no lifecycle — they are always published.
            enabled = true,
        )
    }

    companion object : KLogging() {
        private const val AGENTS_SUBDIR = "agents"
    }
}

/**
 * YAML model for agent definition files read from the filesystem.
 *
 * Maps the fields of a Coday-style agent YAML to [AgentConfig].
 * Extra fields not listed here (e.g. aiProvider, mandatoryDocs) are silently
 * ignored via [JsonIgnoreProperties].
 *
 * [integrations] mirrors the Coday YAML structure:
 * ```yaml
 * integrations:
 *   FILES:           # null list → all tools from this integration
 *   JIRA:
 *     - GetIssue     # non-null list → only these tool names allowed
 * ```
 */
@JsonIgnoreProperties(ignoreUnknown = true)
private data class AgentConfigYamlModel(
    val name: String = "",
    val description: String? = null,
    val instructions: String? = null,
    val modelName: String? = null,
    val integrations: Map<String, List<String>?>? = null,
)
