package io.whozoss.agentos.prompt

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
 * Decorator over a delegate [PromptRepository] that augments read operations with [Prompt]
 * entries loaded from YAML files under `<namespace.configPath>/prompts/`.
 *
 * Filesystem prompts are treated as namespace-shared (`userId = null`) and participate in the
 * 4-tier merge via [findEffective] — user overlays can still be applied on top. This mirrors
 * [io.whozoss.agentos.integrationConfig.FilesystemIntegrationConfigRepository] and
 * [io.whozoss.agentos.agentConfig.FilesystemAgentConfigRepository].
 *
 * All write operations ([save], [delete], [deleteByParent], [softDeleteByAgentConfigId]) are
 * forwarded to the delegate unchanged — the filesystem is never written. [findPlatform] and
 * [findByUserId] are also forwarded unchanged: platform prompts have no namespace and therefore
 * no `configPath` to read from, and user-scoped prompts have no filesystem backing by design.
 *
 * Collision rule: when a persisted prompt carries the same name as a filesystem prompt within
 * the same namespace-shared scope, the persisted prompt wins and the filesystem entry is
 * silently dropped.
 *
 * Filesystem reads are cached per directory with a configurable [ttl] (default 5 minutes).
 */
class FilesystemPromptRepository(
    private val delegate: PromptRepository,
    private val namespaceRepository: NamespaceRepository,
    ttl: Duration = Duration.ofMinutes(5),
) : PromptRepository by delegate {

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
     * Collects all four overlay layers for the given (namespaceId, userId) pair, used by
     * [PromptServiceImpl.findEffective] to resolve slash-commands on [CaseServiceImpl.addMessage].
     * The filesystem namespace-shared layer must appear here so it participates in the
     * name-priority fold performed downstream.
     *
     * Collision with a persisted namespace-shared prompt of the same name: the persisted one is
     * already in the delegate result, so the filesystem entry is excluded by [excludeNames].
     */
    override fun findEffective(
        namespaceId: UUID,
        userId: UUID,
    ): List<Prompt> {
        val fromDelegate = delegate.findEffective(namespaceId, userId)
        val persistedNsSharedNames = fromDelegate
            .filter { it.namespaceId == namespaceId && it.userId == null }
            .mapTo(HashSet()) { it.name.lowercase() }
        val filesystem = filesystemPrompts(namespaceId, excludeNames = persistedNsSharedNames)
        logger.debug {
            "[FilesystemPromptRepository] namespace=$namespaceId: " +
                "${fromDelegate.size} delegate + ${filesystem.size} filesystem = ${fromDelegate.size + filesystem.size} total"
        }
        return fromDelegate + filesystem
    }

    /**
     * Returns namespace-shared prompts (persisted + filesystem), with persisted winning on
     * name collision.
     */
    override fun findByParent(parentId: UUID): List<Prompt> {
        val persisted = delegate.findByParent(parentId)
        val filesystem = filesystemPrompts(parentId, excludeNames = persisted.mapTo(HashSet()) { it.name.lowercase() })
        logger.debug {
            "[FilesystemPromptRepository] namespace=$parentId: " +
                "${persisted.size} persisted + ${filesystem.size} filesystem = ${persisted.size + filesystem.size} total"
        }
        return persisted + filesystem
    }

    /**
     * Point lookup by the (namespaceId, userId, name) triple.
     *
     * When the triple targets the namespace-shared scope (`userId == null`) and the delegate
     * finds nothing, falls back to the filesystem — so that [PromptServiceImpl] uniqueness
     * checks detect filesystem prompts and reject a persisted prompt with the same name+namespace
     * (409 Conflict) rather than silently masking one behind the other.
     */
    override fun findByTriple(
        namespaceId: UUID?,
        userId: UUID?,
        name: String,
    ): Prompt? {
        val fromDelegate = delegate.findByTriple(namespaceId, userId, name)
        if (fromDelegate != null) return fromDelegate
        // Only the namespace-shared scope can be backed by the filesystem.
        if (namespaceId == null || userId != null) return null
        return filesystemPrompts(namespaceId).firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    /**
     * Augments the delegate lookup with filesystem prompts so the UI can `GET` a prompt id
     * surfaced by [findEffective] without a 404.
     *
     * For IDs the delegate does not know about, scans every namespace that has a [configPath]
     * and checks whether the synthetic filesystem ID matches. The scan is cheap because
     * [FilesystemYamlCacheRegistry] caches per directory.
     */
    override fun findByIds(
        ids: Collection<UUID>,
        withRemoved: Boolean,
    ): List<Prompt> {
        val fromDelegate = delegate.findByIds(ids, withRemoved)
        val foundIds = fromDelegate.mapTo(HashSet()) { it.metadata.id }
        val missing = ids.filter { it !in foundIds }
        if (missing.isEmpty()) return fromDelegate

        val missingSet = missing.toHashSet()
        val fromFilesystem = namespaceRepository
            .findByParent(NamespaceRepository.NAMESPACE_PARENT_KEY)
            .filter { it.configPath != null }
            .flatMap { namespace ->
                filesystemPrompts(namespace.metadata.id)
                    .filter { it.metadata.id in missingSet }
            }

        return fromDelegate + fromFilesystem
    }

    /**
     * Find all non-removed prompts at an exact scope level. Filesystem prompts are included
     * only for the namespace-shared scope (`namespaceId != null, userId == null`) and only when
     * [agentConfigIds] is null or empty — filesystem prompts never carry an `agentConfigId`
     * (see [parseYamlFile] KDoc), so they can never match an agent filter.
     */
    override fun findByScope(
        namespaceId: UUID?,
        userId: UUID?,
        agentConfigIds: List<UUID>?,
    ): List<Prompt> {
        val fromDelegate = delegate.findByScope(namespaceId, userId, agentConfigIds)
        if (namespaceId == null || userId != null || !agentConfigIds.isNullOrEmpty()) return fromDelegate
        val filesystem = filesystemPrompts(namespaceId, excludeNames = fromDelegate.mapTo(HashSet()) { it.name.lowercase() })
        return fromDelegate + filesystem
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private fun filesystemPrompts(
        namespaceId: UUID,
        excludeNames: Set<String> = emptySet(),
    ): List<Prompt> {
        val configPath =
            namespaceRepository.findByIds(listOf(namespaceId)).firstOrNull()?.configPath
                ?: return emptyList()
        val directory = Path.of(configPath, PROMPTS_SUBDIR)
        return cacheRegistry
            .getAll(directory)
            .filter { it.name.lowercase() !in excludeNames }
            .map { it.copy(namespaceId = namespaceId) }
            .sortedBy { it.name }
    }

    private fun parseYamlFile(file: Path): Prompt? {
        val model = yamlMapper.readValue(file.toFile(), PromptYamlModel::class.java)
        if (model.name.isBlank()) {
            logger.warn { "[FilesystemPromptRepository] Skipping $file: 'name' is blank" }
            return null
        }
        if (model.content.isNullOrEmpty()) {
            logger.warn { "[FilesystemPromptRepository] Skipping $file: 'content' is null or empty" }
            return null
        }
        if (model.content.any { it.isBlank() }) {
            logger.warn { "[FilesystemPromptRepository] Skipping $file: 'content' contains a blank element" }
            return null
        }
        val parameters = model.parameters ?: emptyList()
        if (parameters.any { it.name.isBlank() }) {
            logger.warn { "[FilesystemPromptRepository] Skipping $file: a parameter has a blank 'name'" }
            return null
        }
        val duplicateParamName = parameters
            .groupBy { it.name }
            .entries
            .firstOrNull { it.value.size > 1 }
            ?.key
        if (duplicateParamName != null) {
            logger.warn { "[FilesystemPromptRepository] Skipping $file: duplicate parameter name '$duplicateParamName'" }
            return null
        }
        return Prompt(
            // Stable UUID derived from the name so identity survives restarts.
            // namespaceId is null here; it is overwritten by the caller.
            metadata = EntityMetadata(id = UUID.nameUUIDFromBytes("filesystem-prompt:${model.name}".toByteArray(Charsets.UTF_8))),
            namespaceId = null,
            userId = null,
            // Filesystem prompts never link to an AgentConfig — see PromptYamlModel KDoc.
            agentConfigId = null,
            name = model.name,
            description = model.description,
            content = model.content,
            parameters = parameters.map { PromptParameter(name = it.name, description = it.description, defaultValue = it.defaultValue) },
        )
    }

    companion object : KLogging() {
        private const val PROMPTS_SUBDIR = "prompts"
    }
}

/**
 * YAML model for prompt files read from the filesystem.
 *
 * [name] and [content] are required — a file missing either, or with a blank [content] element,
 * is skipped with a warning. [parameters], when present, must have unique, non-blank names;
 * a violation skips the whole file rather than silently dropping the offending parameter, so the
 * bad file is easy to spot in the logs.
 *
 * **No `agentConfigId` / `agentName` field is supported here (deliberate, YAGNI).** Resolving a
 * name to an [io.whozoss.agentos.agentConfig.AgentConfig] UUID at parse time would couple this
 * parser to [io.whozoss.agentos.agentConfig.AgentConfigRepository] and pollute the YAML cache
 * with a foreign lookup. The need is already covered without it: a prompt's [content] can start
 * with `@agentName ...`, which [io.whozoss.agentos.caseFlow.CaseServiceImpl] resolves natively
 * when the prompt is expanded. Filesystem prompts therefore always have `agentConfigId = null`.
 *
 * Example:
 * ```yaml
 * name: plan
 * description: Create an implementation plan
 * content:
 *   - "Analyse the following request: {{ARGUMENTS}}"
 *   - "Then produce a step-by-step plan for {{scope}}"
 * parameters:
 *   - name: scope
 *     description: what to plan
 *     defaultValue: ""
 * ```
 */
@JsonIgnoreProperties(ignoreUnknown = true)
private data class PromptYamlModel(
    val name: String = "",
    val description: String? = null,
    val content: List<String>? = null,
    val parameters: List<PromptParameterYamlModel>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class PromptParameterYamlModel(
    val name: String = "",
    val description: String? = null,
    val defaultValue: String = "",
)
