package io.whozoss.agentos.prompt

import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.exception.UnprocessableEntityException
import mu.KLogging
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Default implementation of [PromptService].
 *
 * Delegates persistence to [PromptRepository].
 *
 * Custom validation on [create] and [update]:
 * - Every element of [Prompt.content] must be non-blank.
 * - The names of [Prompt.parameters] must be unique within the list.
 *
 * Name uniqueness per scope is enforced by the `tripleKey` UNIQUE constraint in Neo4j.
 * An applicative pre-check gives a descriptive 409 message; the catch on
 * [DataIntegrityViolationException] handles concurrent inserts that race past the
 * pre-check (mirrors the IntegrationConfigServiceImpl pattern).
 */
@Service
class PromptServiceImpl(
    private val repository: PromptRepository,
    private val agentConfigService: AgentConfigService,
    private val translationService: PromptTranslationService,
) : PromptService {
    override fun create(entity: Prompt): Prompt {
        validate(entity)
        if (entity.agentConfigId != null) {
            val agentConfig = agentConfigService.findById(entity.agentConfigId)
                ?: throw ResourceNotFoundException("AgentConfig not found: ${entity.agentConfigId}")
            if (agentConfig.isFilesystemOnly) {
                throw UnprocessableEntityException(
                    "AgentConfig id=${entity.agentConfigId} is a filesystem-only agent and cannot be linked to a prompt",
                )
            }
            validateAgentConfigScope(entity, agentConfig)
        }
        repository.findByTriple(entity.namespaceId, entity.userId, entity.name)?.let {
            throw ConflictException(conflictMessage(entity))
        }
        return saveOrConflict(entity)
    }

    override fun update(entity: Prompt): Prompt {
        validate(entity)
        rejectIfFilesystemBacked(entity.id, "updated")
        repository
            .findByTriple(entity.namespaceId, entity.userId, entity.name)
            ?.takeIf { it.id != entity.id }
            ?.let { throw ConflictException(conflictMessage(entity)) }
        val existing = repository.findByIds(listOf(entity.id)).firstOrNull()
        return saveOrConflict(clearTranslationsIfStale(entity, existing))
    }

    override fun findById(
        id: UUID,
        withRemoved: Boolean,
    ): Prompt? = repository.findByIds(listOf(id), withRemoved).firstOrNull()

    override fun findByIds(
        ids: Collection<UUID>,
        withRemoved: Boolean,
    ): List<Prompt> = repository.findByIds(ids, withRemoved)

    override fun findByParent(parentId: UUID): List<Prompt> = repository.findByParent(parentId)

    override fun findPlatform(): List<Prompt> = repository.findPlatform()

    override fun findByUserId(userId: UUID): List<Prompt> = repository.findByUserId(userId)

    // The agentConfigId filter is intentionally applied here, in memory, and not pushed into
    // the repository query. findEffective fetches raw candidates across all four overlay
    // layers (platform / user-global / namespace-shared / user×namespace) for a given prompt
    // name; which layer "wins" is only known after the groupBy+priority fold below. A layer
    // that does NOT match agentConfigId can still be the eventual winner for its name, while a
    // lower-priority layer that DOES match loses — filtering in the Cypher query (i.e. on the
    // raw, pre-merge rows) would silently drop the winning row whenever its non-matching layer
    // outranks a matching one, or spuriously keep a name whose only matching layer isn't the
    // effective one. The filter must therefore run after the merge, on the already-resolved
    // per-name winners — never before.
    override fun findEffective(
        namespaceId: UUID,
        callerId: UUID,
        agentConfigId: UUID?,
    ): List<Prompt> =
        repository
            .findEffective(namespaceId, callerId)
            .sortedBy { layerPriority(it) }
            .groupBy { it.name }
            .map { (_, layers) -> layers.last() }
            .filter { agentConfigId == null || it.agentConfigId == agentConfigId }
            .sortedBy { it.name }

    override fun findByScope(
        namespaceId: UUID?,
        userId: UUID?,
        agentConfigIds: List<UUID>?,
    ): List<Prompt> = repository.findByScope(namespaceId, userId, agentConfigIds)

    override fun translate(
        id: UUID,
        targetLanguage: String,
        namespaceId: UUID?,
        namespaceExternalId: String?,
    ): PromptTranslation {
        val prompt = repository.findByIds(listOf(id)).firstOrNull()
            ?: throw NoSuchElementException("Prompt $id not found")

        return when {
            // Short-circuit: requested language is the source — return originals as-is
            targetLanguage == prompt.sourceLanguage ->
                PromptTranslation(title = prompt.title, content = prompt.content)

            else -> translateToForeignLanguage(prompt, targetLanguage, namespaceId, namespaceExternalId)
        }
    }

    private fun translateToForeignLanguage(
        prompt: Prompt,
        targetLanguage: String,
        namespaceId: UUID?,
        namespaceExternalId: String?,
    ): PromptTranslation {
        val cachedTitle = prompt.title?.let { prompt.translatedTitles?.get(targetLanguage) }
        val cachedContent = prompt.translatedContent?.get(targetLanguage)

        return when {
            // Full cache hit — no LLM call needed
            cachedContent != null && (prompt.title == null || cachedTitle != null) ->
                PromptTranslation(title = cachedTitle, content = cachedContent)

            else -> translateAndPersist(prompt, targetLanguage, namespaceId, namespaceExternalId, cachedTitle, cachedContent)
        }
    }

    private fun translateAndPersist(
        prompt: Prompt,
        targetLanguage: String,
        namespaceId: UUID?,
        namespaceExternalId: String?,
        cachedTitle: String?,
        cachedContent: List<String>?,
    ): PromptTranslation {
        // Translate only what is missing
        val resolvedTitle: String? = when {
            prompt.title == null -> null
            cachedTitle != null -> cachedTitle
            else -> translationService.translateTitle(
                title = prompt.title,
                sourceLanguage = prompt.sourceLanguage,
                targetLanguage = targetLanguage,
                namespaceId = namespaceId,
                namespaceExternalId = namespaceExternalId,
            )
        }

        val resolvedContent: List<String> = cachedContent ?: translationService.translateContent(
            content = prompt.content,
            sourceLanguage = prompt.sourceLanguage,
            targetLanguage = targetLanguage,
            namespaceId = namespaceId,
            namespaceExternalId = namespaceExternalId,
        )

        // Persist the newly generated translations
        val updatedTitles = if (resolvedTitle != null) {
            (prompt.translatedTitles ?: emptyMap()) + (targetLanguage to resolvedTitle)
        } else {
            prompt.translatedTitles
        }
        val updatedContent = (prompt.translatedContent ?: emptyMap()) + (targetLanguage to resolvedContent)

        repository.save(
            prompt.copy(
                translatedTitles = updatedTitles,
                translatedContent = updatedContent,
            ),
        )

        return PromptTranslation(title = resolvedTitle, content = resolvedContent)
    }

    private fun layerPriority(p: Prompt): Int =
        when {
            p.namespaceId == null && p.userId == null -> 0

            // platform
            p.namespaceId == null -> 1

            // user-global
            p.userId == null -> 2

            // namespace-shared
            else -> 3 // user×namespace
        }

    override fun delete(id: UUID): Boolean {
        rejectIfFilesystemBacked(id, "deleted")
        return repository.delete(id)
    }

    override fun deleteByParent(parentId: UUID): Int = repository.deleteByParent(parentId)

    /**
     * Clears [Prompt.translatedTitles] and/or [Prompt.translatedContent] when their
     * respective source fields have changed relative to [existing].
     *
     * - [Prompt.translatedTitles] is cleared when [Prompt.title] or [Prompt.sourceLanguage] changes.
     * - [Prompt.translatedContent] is cleared when [Prompt.content] or [Prompt.sourceLanguage] changes.
     *
     * Each map is evaluated independently — a content change does not clear title translations
     * and vice versa, unless sourceLanguage also changed (which invalidates both).
     * When [existing] is null the entity is returned unchanged (nothing to compare against).
     */
    private fun clearTranslationsIfStale(entity: Prompt, existing: Prompt?): Prompt {
        if (existing == null) return entity
        val sourceLanguageChanged = entity.sourceLanguage != existing.sourceLanguage
        val titleStale = sourceLanguageChanged || entity.title != existing.title
        val contentStale = sourceLanguageChanged || entity.content != existing.content
        return entity.copy(
            translatedTitles = if (titleStale) null else entity.translatedTitles,
            translatedContent = if (contentStale) null else entity.translatedContent,
        )
    }

    /**
     * Validates business rules that apply after Bean Validation:
     * - No element of [Prompt.content] may be blank (type-use @NotBlank on generic
     *   type arguments is unreliable when the DTO lives in a separate module with
     *   compileOnly validation dependencies, so the check lives here instead).
     * - Parameter names must be unique within the list.
     */
    private fun validate(prompt: Prompt) {
        val duplicateName =
            prompt.parameters
                .groupBy { it.name }
                .entries
                .firstOrNull { it.value.size > 1 }
                ?.key
        if (duplicateName != null) {
            throw BadRequestException(
                "Duplicate parameter name '$duplicateName' — parameter names must be unique within a prompt",
            )
        }
    }

    private fun validateAgentConfigScope(prompt: Prompt, agentConfig: AgentConfig) {
        val validScope = when {
            // Platform agent is always valid (accessible from any scope)
            agentConfig.namespaceId == null -> true
            // Same namespace
            agentConfig.namespaceId == prompt.namespaceId -> true
            // Everything else is cross-scope
            else -> false
        }
        if (!validScope) {
            throw BadRequestException(
                "AgentConfig id=${prompt.agentConfigId} does not belong to the prompt's namespace",
            )
        }
    }

    private fun saveOrConflict(entity: Prompt): Prompt =
        try {
            repository.save(entity)
        } catch (e: DataIntegrityViolationException) {
            if (!isTripleKeyConflict(e)) {
                throw e
            }
            logger.warn {
                "[PromptService] tripleKey unique-constraint violation on save " +
                    "(namespaceId=${entity.namespaceId}, userId=${entity.userId}, name='${entity.name}')"
            }
            throw ConflictException(conflictMessage(entity), e)
        }

    private fun isTripleKeyConflict(e: DataIntegrityViolationException): Boolean {
        val haystack =
            generateSequence<Throwable>(e) { it.cause }
                .mapNotNull { it.message }
                .joinToString(separator = " | ")
        return TRIPLE_KEY_CONSTRAINT_NAME in haystack || TRIPLE_KEY_PROPERTY in haystack
    }

    private fun conflictMessage(entity: Prompt): String =
        "A prompt named '${entity.name}' already exists in this scope " +
            "(namespaceId=${entity.namespaceId ?: "platform"}, userId=${entity.userId})"

    /**
     * Rejects [update] / [delete] when [id] resolves to a filesystem-backed prompt.
     *
     * A filesystem prompt (loaded by [FilesystemPromptRepository] from YAML, never saved
     * through SDN) carries `metadata.version == null` — the same idiom used in [create] to
     * detect filesystem-only AgentConfigs. Since [FilesystemPromptRepository.findByIds] now
     * resolves the synthetic filesystem id, a naive PUT/DELETE on that id would otherwise
     * create (resp. attempt to soft-delete) a phantom Neo4j node sharing the id — the
     * persisted copy would then silently shadow the file-backed prompt it was meant to edit,
     * defeating the collision rule documented on [FilesystemPromptRepository].
     */
    private fun rejectIfFilesystemBacked(id: UUID, action: String) {
        repository.findByIds(listOf(id)).firstOrNull()
            ?.takeIf { it.metadata.version == null }
            ?.let {
                throw UnprocessableEntityException(
                    "Prompt id=$id is backed by a filesystem YAML file and cannot be $action via the API",
                )
            }
    }

    companion object : KLogging() {
        private const val TRIPLE_KEY_CONSTRAINT_NAME = "prompt_triple_key_unique"
        private const val TRIPLE_KEY_PROPERTY = "tripleKey"
    }
}
