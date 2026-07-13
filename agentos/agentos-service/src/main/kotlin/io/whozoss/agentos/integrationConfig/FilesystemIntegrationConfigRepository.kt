package io.whozoss.agentos.integrationConfig

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode
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
 * Decorator over a delegate [IntegrationConfigRepository] that augments read operations with
 * [IntegrationConfig] entries loaded from YAML files under `<namespace.configPath>/integrations/`.
 *
 * Filesystem configs are treated as namespace-shared (`userId = null`) and participate in the
 * 4-tier merge via [findAllForNamespaceIdAndUserId] — user overlays can still be applied on top.
 *
 * All write operations ([save], [delete], [deleteByParent]) are forwarded to the delegate
 * unchanged — the filesystem is never written.
 *
 * Collision rule: when a persisted config carries the same name as a filesystem config within
 * the same namespace, the persisted config wins and the filesystem entry is silently dropped.
 * This mirrors the behaviour of [io.whozoss.agentos.agentConfig.FilesystemAgentConfigRepository].
 *
 * Filesystem reads are cached per directory with a configurable [ttl] (default 5 minutes).
 */
class FilesystemIntegrationConfigRepository(
    private val delegate: IntegrationConfigRepository,
    private val namespaceRepository: NamespaceRepository,
    ttl: Duration = Duration.ofMinutes(5),
) : IntegrationConfigRepository by delegate {

    private val yamlMapper =
        ObjectMapper(YAMLFactory()).registerModule(KotlinModule.Builder().build())

    private val cacheRegistry =
        FilesystemYamlCacheRegistry(
            parser = ::parseYamlFile,
            ttl = ttl,
        )

    // -------------------------------------------------------------------------
    // Augmented read operations
    // -------------------------------------------------------------------------

    /**
     * Delegates to [findByNamespaceId] by convention (mirrors [Neo4jIntegrationConfigRepository]).
     */
    override fun findByParent(parentId: UUID): List<IntegrationConfig> = findByNamespaceId(parentId)

    /**
     * Returns namespace-shared configs (persisted + filesystem), with persisted winning on
     * name collision.
     */
    override fun findByNamespaceId(namespaceId: UUID): List<IntegrationConfig> {
        val persisted = delegate.findByNamespaceId(namespaceId)
        val filesystem = filesystemConfigs(namespaceId, excludeNames = persisted.mapTo(HashSet()) { it.name.lowercase() })
        logger.debug {
            "[FilesystemIntegrationConfigRepository] namespace=$namespaceId: " +
                "${persisted.size} persisted + ${filesystem.size} filesystem = ${persisted.size + filesystem.size} total"
        }
        return persisted + filesystem
    }

    /**
     * Used by [IntegrationConfigServiceImpl.findEffective] to collect all layers for a given
     * execution context. The filesystem namespace-shared layer must appear here so it participates
     * in the parameter-level merge.
     *
     * Collision with a persisted namespace-shared config of the same name: the persisted one is
     * already in the delegate result, so the filesystem entry is excluded by [excludeNames].
     */
    override fun findAllForNamespaceIdAndUserId(
        namespaceId: UUID?,
        userId: UUID?,
    ): List<IntegrationConfig> {
        val fromDelegate = delegate.findAllForNamespaceIdAndUserId(namespaceId, userId)
        if (namespaceId == null) {
            // No namespace context means no configPath to read from.
            return fromDelegate
        }
        val persistedNsSharedNames = fromDelegate
            .filter { it.namespaceId == namespaceId && it.userId == null }
            .mapTo(HashSet()) { it.name.lowercase() }
        val filesystem = filesystemConfigs(namespaceId, excludeNames = persistedNsSharedNames)
        return fromDelegate + filesystem
    }

    /**
     * Point lookup by the (namespaceId, userId, name) triple.
     *
     * When the triple targets the namespace-shared scope (`userId == null`) and the delegate
     * finds nothing, falls back to the filesystem — so that [IntegrationConfigServiceImpl]
     * uniqueness checks detect filesystem configs and reject a persisted config with the same
     * name+namespace rather than silently creating a duplicate.
     */
    override fun findByTriple(
        namespaceId: UUID?,
        userId: UUID?,
        name: String,
    ): IntegrationConfig? {
        val fromDelegate = delegate.findByTriple(namespaceId, userId, name)
        if (fromDelegate != null) return fromDelegate
        // Only the namespace-shared scope can be backed by the filesystem.
        if (namespaceId == null || userId != null) return null
        return filesystemConfigs(namespaceId).firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    /**
     * Cross-namespace lookup used by platform-level write validation.
     *
     * Augments the delegate result with filesystem configs that share [name] across all
     * namespaces whose directory is already held in the cache. This is best-effort: namespaces
     * whose `integrations/` directory has never been read are not yet cached and therefore not
     * checked. The delegate's DB-backed check remains the primary guard; this augmentation
     * prevents a platform admin from conflicting with a known filesystem-defined config.
     */
    override fun findNsSharedByName(name: String): List<IntegrationConfig> {
        val fromDelegate = delegate.findNsSharedByName(name)
        val fromFilesystem = cacheRegistry.getAllCached()
            .filter { it.name.equals(name, ignoreCase = true) }
            .filter { fs ->
                fromDelegate.none { p ->
                    p.namespaceId == fs.namespaceId && p.name.equals(fs.name, ignoreCase = true)
                }
            }
        return fromDelegate + fromFilesystem
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private fun filesystemConfigs(
        namespaceId: UUID,
        excludeNames: Set<String> = emptySet(),
    ): List<IntegrationConfig> {
        val configPath =
            namespaceRepository.findByIds(listOf(namespaceId)).firstOrNull()?.configPath
                ?: return emptyList()
        val directory = Path.of(configPath, INTEGRATIONS_SUBDIR)
        return cacheRegistry
            .getAll(directory)
            .filter { it.name.lowercase() !in excludeNames }
            .map { it.copy(namespaceId = namespaceId) }
            .sortedBy { it.name }
    }

    private fun parseYamlFile(file: Path): IntegrationConfig? {
        val model = yamlMapper.readValue(file.toFile(), IntegrationConfigYamlModel::class.java)
        if (model.name.isBlank()) {
            logger.warn { "[FilesystemIntegrationConfigRepository] Skipping $file: 'name' is blank" }
            return null
        }
        if (model.integrationType.isBlank()) {
            logger.warn { "[FilesystemIntegrationConfigRepository] Skipping $file: 'integrationType' is blank" }
            return null
        }
        return IntegrationConfig(
            // Stable UUID derived from the name so identity survives restarts.
            // namespaceId is null here; it is overwritten by the caller.
            metadata = EntityMetadata(id = UUID.nameUUIDFromBytes("filesystem-integration:${model.name}".toByteArray(Charsets.UTF_8))),
            namespaceId = null,
            userId = null,
            name = model.name,
            integrationType = model.integrationType,
            description = model.description,
            parameters = model.parameters,
        )
    }

    companion object : KLogging() {
        private const val INTEGRATIONS_SUBDIR = "integrations"
    }
}

/**
 * YAML model for integration config files read from the filesystem.
 *
 * Both [name] and [integrationType] are required — a file missing either is skipped with a warning.
 * [parameters] is an arbitrary YAML object serialised verbatim as a [JsonNode]; no variable
 * substitution is performed. For credentials that vary per user, create a user-level overlay
 * via the API on top of this namespace-shared base.
 *
 * Example:
 * ```yaml
 * name: JIRA
 * integrationType: JIRA
 * description: "Jira instance for the team"
 * parameters:
 *   baseUrl: "https://company.atlassian.net"
 *   project: "MYPROJ"
 * ```
 */
@JsonIgnoreProperties(ignoreUnknown = true)
private data class IntegrationConfigYamlModel(
    val name: String = "",
    val integrationType: String = "",
    val description: String? = null,
    val parameters: JsonNode? = null,
)
