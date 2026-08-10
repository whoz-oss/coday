package io.whozoss.agentos.scheduledPrompt

import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.prompt.Prompt
import io.whozoss.agentos.prompt.PromptService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.util.toSlug
import mu.KLogging
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Default implementation of [ScheduledPromptService].
 *
 * ### Validation on [create] / [createWithPrompt]
 *
 * - [agentConfigId] must reference an existing, non-filesystem AgentConfig.
 * - [promptId] must reference an existing generic Prompt (agentConfigId = null).
 * - The AgentConfig's namespace must be compatible with the ScheduledPrompt's namespace.
 * - Name uniqueness per scope enforced by the `scheduled_prompt_triple_key_unique` UNIQUE constraint.
 *
 * Cross-field consistency of [Planning] (endDate/maxOccurrenceCount vs endType) is validated
 * as Bean Validation on [io.whozoss.agentos.sdk.api.scheduledPrompt.PlanningDto] and enforced
 * by `@Valid` cascading from `ScheduledPromptDto.planning` on the controller's create/update
 * endpoints — the sole write entry points into this service.
 *
 * ### Prompt lifecycle
 *
 * [createWithPrompt] creates a linked generic Prompt (agentConfigId = null) with name
 * `scheduled--{nameSlug}` (max 100 chars), then delegates to [create].
 * [updateWithPrompt] updates the linked Prompt then delegates to [update].
 * [deleteWithPrompt] calls [delete] then removes the linked Prompt.
 */
@Service
class ScheduledPromptServiceImpl(
    private val repository: ScheduledPromptRepository,
    private val agentConfigService: AgentConfigService,
    private val promptService: PromptService,
    private val namespaceService: NamespaceService,
    private val nextRunCalculatorService: NextRunCalculatorService,
) : ScheduledPromptService {

    // -------------------------------------------------------------------------
    // Core CRUD
    // -------------------------------------------------------------------------

    override fun create(entity: ScheduledPrompt): ScheduledPrompt {
        val agentConfig = agentConfigService.findById(entity.agentConfigId)
            ?: throw ResourceNotFoundException("AgentConfig not found: ${entity.agentConfigId}")
        if (agentConfig.isFilesystemOnly) {
            throw UnprocessableEntityException(
                "AgentConfig id=${entity.agentConfigId} is a filesystem-only agent and cannot be linked to a ScheduledPrompt",
            )
        }
        validateAgentConfigScope(entity, agentConfig)

        val prompt = promptService.findById(entity.promptTemplateId)
            ?: throw ResourceNotFoundException("Prompt not found: ${entity.promptTemplateId}")
        if (prompt.agentConfigId != null) {
            throw UnprocessableEntityException(
                "Prompt ${entity.promptTemplateId} is linked to agent ${prompt.agentConfigId}. " +
                    "Only generic prompts (agentConfigId = null) may be associated with a ScheduledPrompt.",
            )
        }

        repository.findByTriple(entity.namespaceId, entity.userId, entity.name)?.let {
            throw ConflictException(conflictMessage(entity))
        }

        val withNextRun = entity.copy(nextRunAt = nextRunCalculatorService.compute(entity))
        return saveOrConflict(withNextRun)
    }

    override fun update(entity: ScheduledPrompt): ScheduledPrompt {
        promptService.findById(entity.promptTemplateId)
            ?: throw ResourceNotFoundException("Prompt not found: ${entity.promptTemplateId}")

        repository.findByTriple(entity.namespaceId, entity.userId, entity.name)
            ?.takeIf { it.id != entity.id }
            ?.let { throw ConflictException(conflictMessage(entity)) }

        // Recalculate nextRunAt whenever recurrence or planning may have changed.
        val withNextRun = entity.copy(nextRunAt = nextRunCalculatorService.compute(entity))
        return saveOrConflict(withNextRun)
    }

    override fun findById(id: UUID, withRemoved: Boolean): ScheduledPrompt? =
        repository.findByIds(listOf(id), withRemoved).firstOrNull()

    override fun findByIds(ids: Collection<UUID>, withRemoved: Boolean): List<ScheduledPrompt> =
        repository.findByIds(ids, withRemoved)

    override fun findByParent(parentId: UUID): List<ScheduledPrompt> = repository.findByParent(parentId)

    override fun findPlatform(): List<ScheduledPrompt> = repository.findPlatform()

    // Same rationale as PromptServiceImpl.findEffective: agentConfigId is filtered here, in
    // memory, after the merge — not in the repository query. The repository returns raw
    // candidates from all four overlay layers per name; the winning layer for a given name is
    // only determined by the groupBy+priority fold below. A non-matching lower-priority layer
    // could otherwise mask a matching higher-priority one (or vice versa) if the filter were
    // pushed into Cypher and applied to the pre-merge rows. Filtering must happen strictly
    // after the per-name winner is resolved.
    override fun findEffective(namespaceId: UUID, callerId: UUID, agentConfigId: UUID?): List<ScheduledPrompt> =
        repository
            .findEffective(namespaceId, callerId)
            .sortedBy { layerPriority(it) }
            .groupBy { it.name }
            .map { (_, layers) -> layers.last() }
            .filter { agentConfigId == null || it.agentConfigId == agentConfigId }
            .sortedBy { it.name }

    override fun findByScope(namespaceId: UUID?, userId: UUID?, agentConfigIds: List<UUID>?): List<ScheduledPrompt> =
        repository.findByScope(namespaceId, userId, agentConfigIds)

    override fun enable(id: UUID): ScheduledPrompt {
        val existing = repository.findById(id)
            ?: throw ResourceNotFoundException("ScheduledPrompt not found: $id")
        if (existing.enabled) return existing
        val enabled = existing.copy(enabled = true)
        val withNextRun = enabled.copy(nextRunAt = nextRunCalculatorService.compute(enabled))
        return repository.save(withNextRun)
            .also { logger.info { "[ScheduledPrompt] Enabled $id, nextRunAt=${it.nextRunAt}" } }
    }

    override fun disable(id: UUID): ScheduledPrompt {
        val existing = repository.findById(id)
            ?: throw ResourceNotFoundException("ScheduledPrompt not found: $id")
        if (!existing.enabled) return existing
        return repository.save(existing.copy(enabled = false))
            .also { logger.info { "[ScheduledPrompt] Disabled $id" } }
    }

    override fun delete(id: UUID): Boolean =
        repository.delete(id)
            .also { if (it) logger.info { "[ScheduledPrompt] Soft-deleted $id" } }

    override fun deleteByParent(parentId: UUID): Int = repository.deleteByParent(parentId)

    // -------------------------------------------------------------------------
    // Prompt lifecycle
    // -------------------------------------------------------------------------

    override fun createWithPrompt(entity: ScheduledPrompt, promptContent: String): Pair<ScheduledPrompt, String> {
        if (entity.namespaceId != null && namespaceService.findById(entity.namespaceId) == null) {
            throw ResourceNotFoundException("Namespace not found: ${entity.namespaceId}")
        }

        val prompt = promptService.create(
            Prompt(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                namespaceId = entity.namespaceId,
                userId = entity.userId,
                agentConfigId = null,
                name = promptName(entity.name),
                content = listOf(promptContent),
            ),
        )
        val saved = create(entity.copy(promptTemplateId = prompt.id))
        return Pair(saved, promptContent)
    }

    override fun updateWithPrompt(entity: ScheduledPrompt, promptContent: String): Pair<ScheduledPrompt, String> {
        val existingPrompt = promptService.findById(entity.promptTemplateId)
            ?: throw ResourceNotFoundException("Prompt not found: ${entity.promptTemplateId}")
        promptService.update(
            existingPrompt.copy(
                name = promptName(entity.name),
                content = listOf(promptContent),
            ),
        )
        val saved = update(entity)
        return Pair(saved, promptContent)
    }

    override fun deleteWithPrompt(id: UUID): Boolean {
        val existing = findById(id) ?: return false
        val deleted = delete(id)
        if (deleted) promptService.delete(existing.promptTemplateId)
        return deleted
    }

    override fun findByIdWithContent(id: UUID, withRemoved: Boolean): Pair<ScheduledPrompt, String>? {
        val sp = findById(id, withRemoved) ?: return null
        val content = promptService.findById(sp.promptTemplateId)?.content?.firstOrNull() ?: ""
        return Pair(sp, content)
    }

    override fun withContent(sps: List<ScheduledPrompt>): List<Pair<ScheduledPrompt, String>> {
        if (sps.isEmpty()) return emptyList()
        val promptsById = promptService
            .findByIds(sps.map { it.promptTemplateId })
            .associateBy { it.metadata.id }
        return sps.map { sp ->
            Pair(sp, promptsById[sp.promptTemplateId]?.content?.firstOrNull() ?: "")
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Prompt name pattern: scheduled--{nameSlug}, truncated to 100 chars. */
    private fun promptName(name: String): String = "scheduled--${name.toSlug()}".take(100)

    private fun layerPriority(scheduledPrompt: ScheduledPrompt): Int =
        when {
            scheduledPrompt.namespaceId == null && scheduledPrompt.userId == null -> 0
            scheduledPrompt.namespaceId == null -> 1
            scheduledPrompt.userId == null -> 2
            else -> 3
        }

    private fun validateAgentConfigScope(entity: ScheduledPrompt, agentConfig: AgentConfig) {
        val validScope = agentConfig.namespaceId == null || agentConfig.namespaceId == entity.namespaceId
        if (!validScope) {
            throw UnprocessableEntityException(
                "AgentConfig id=${entity.agentConfigId} does not belong to the ScheduledPrompt's namespace",
            )
        }
    }

    private fun saveOrConflict(entity: ScheduledPrompt): ScheduledPrompt =
        try {
            repository.save(entity)
        } catch (e: DataIntegrityViolationException) {
            // The only UNIQUE constraint on ScheduledPrompt is tripleKey (namespaceId, userId, name).
            // Any DataIntegrityViolationException from save is a name conflict in this scope.
            logger.warn {
                "[ScheduledPromptService] tripleKey conflict (ns=${entity.namespaceId}, user=${entity.userId}, name='${entity.name}')"
            }
            throw ConflictException(conflictMessage(entity), e)
        }

    private fun conflictMessage(entity: ScheduledPrompt): String =
        "A ScheduledPrompt named '${entity.name}' already exists in this scope " +
            "(namespaceId=${entity.namespaceId ?: "platform"}, userId=${entity.userId})"

    companion object : KLogging()
}
