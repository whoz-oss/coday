package io.whozoss.agentos.scheduledPrompt

import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.prompt.PromptService
import io.whozoss.agentos.sdk.api.scheduledPrompt.SchedulerEndType
import mu.KLogging
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.UUID

/**
 * Default implementation of [ScheduledPromptService].
 *
 * ### Validation on [create]
 *
 * - [recurrence.every][Recurrence.every] must be > 0.
 * - [agentConfigId] must reference an existing, non-filesystem AgentConfig.
 * - [promptId] must reference an existing generic Prompt (agentConfigId = null).
 * - The AgentConfig's namespace must be compatible with the ScheduledPrompt's namespace.
 * - [planning.endDate][Planning.endDate] required when endType == ON_DATE, must be after startDate.
 * - [planning.occurrenceCount][Planning.occurrenceCount] required and > 0 when endType == OCCURRENCES.
 * - Name uniqueness per scope enforced by the `scheduled_prompt_triple_key_unique` UNIQUE constraint.
 */
@Service
class ScheduledPromptServiceImpl(
    private val repository: ScheduledPromptRepository,
    private val agentConfigService: AgentConfigService,
    private val promptService: PromptService,
    private val clock: Clock = Clock.systemUTC(),
) : ScheduledPromptService {

    override fun create(entity: ScheduledPrompt): ScheduledPrompt {
        require(entity.recurrence.every > 0) {
            "ScheduledPrompt 'every' must be > 0. Got: ${entity.recurrence.every}"
        }

        val agentConfig = agentConfigService.findById(entity.agentConfigId)
            ?: throw ResourceNotFoundException("AgentConfig not found: ${entity.agentConfigId}")
        if (agentConfig.metadata.version == null) {
            throw UnprocessableEntityException(
                "AgentConfig id=${entity.agentConfigId} is a filesystem-only agent and cannot be linked to a ScheduledPrompt",
            )
        }
        validateAgentConfigScope(entity, agentConfig)

        val prompt = promptService.findById(entity.promptTemplateId)
            ?: throw ResourceNotFoundException("Prompt not found: ${entity.promptTemplateId}")
        if (prompt.agentConfigId != null) {
            throw BadRequestException(
                "Prompt ${entity.promptTemplateId} is linked to agent ${prompt.agentConfigId}. " +
                    "Only generic prompts (agentConfigId = null) may be associated with a ScheduledPrompt.",
            )
        }

        validateEndCondition(entity)

        repository.findByTriple(entity.namespaceId, entity.userId, entity.name)?.let {
            throw ConflictException(conflictMessage(entity))
        }

        val withNextRun = entity.copy(nextRunAt = NextRunCalculator.compute(entity, clock))
        return saveOrConflict(withNextRun)
    }

    override fun update(entity: ScheduledPrompt): ScheduledPrompt {
        promptService.findById(entity.promptTemplateId)
            ?: throw ResourceNotFoundException("Prompt not found: ${entity.promptTemplateId}")

        repository.findByTriple(entity.namespaceId, entity.userId, entity.name)
            ?.takeIf { it.id != entity.id }
            ?.let { throw ConflictException(conflictMessage(entity)) }

        // Recalculate nextRunAt whenever recurrence or planning may have changed.
        val withNextRun = entity.copy(nextRunAt = NextRunCalculator.compute(entity, clock))
        return saveOrConflict(withNextRun)
    }

    override fun findById(id: UUID, withRemoved: Boolean): ScheduledPrompt? =
        repository.findByIds(listOf(id), withRemoved).firstOrNull()

    override fun findByIds(ids: Collection<UUID>, withRemoved: Boolean): List<ScheduledPrompt> =
        repository.findByIds(ids, withRemoved)

    override fun findByParent(parentId: UUID): List<ScheduledPrompt> = repository.findByParent(parentId)

    override fun findPlatform(): List<ScheduledPrompt> = repository.findPlatform()

    override fun findEffective(namespaceId: UUID, callerId: UUID): List<ScheduledPrompt> =
        repository
            .findEffective(namespaceId, callerId)
            .sortedBy { layerPriority(it) }
            .groupBy { it.name }
            .map { (_, layers) -> layers.last() }
            .sortedBy { it.name }

    override fun findByScope(namespaceId: UUID?, userId: UUID?, agentConfigIds: List<UUID>?): List<ScheduledPrompt> =
        repository.findByScope(namespaceId, userId, agentConfigIds)

    override fun toggle(id: UUID): ScheduledPrompt {
        val existing = repository.findById(id)
            ?: throw ResourceNotFoundException("ScheduledPrompt not found: $id")
        val toggled = existing.copy(enabled = !existing.enabled)
        // Recalculate nextRunAt when re-enabling so the scheduler doesn't fire a stale instant.
        val withNextRun = if (toggled.enabled) {
            toggled.copy(nextRunAt = NextRunCalculator.compute(toggled, clock))
        } else {
            toggled
        }
        return repository.save(withNextRun)
            .also { logger.info { "[ScheduledPrompt] Toggled enabled=${it.enabled} on $id" } }
    }

    override fun delete(id: UUID): Boolean =
        repository.delete(id)
            .also { if (it) logger.info { "[ScheduledPrompt] Soft-deleted $id" } }

    override fun deleteByParent(parentId: UUID): Int = repository.deleteByParent(parentId)

    private fun layerPriority(sp: ScheduledPrompt): Int =
        when {
            sp.namespaceId == null && sp.userId == null -> 0
            sp.namespaceId == null -> 1
            sp.userId == null -> 2
            else -> 3
        }

    private fun validateAgentConfigScope(entity: ScheduledPrompt, agentConfig: AgentConfig) {
        val validScope = agentConfig.namespaceId == null || agentConfig.namespaceId == entity.namespaceId
        if (!validScope) {
            throw BadRequestException(
                "AgentConfig id=${entity.agentConfigId} does not belong to the ScheduledPrompt's namespace",
            )
        }
    }

    private fun validateEndCondition(entity: ScheduledPrompt) {
        when (entity.planning.endType) {
            SchedulerEndType.ON_DATE -> {
                val endDate = entity.planning.endDate
                    ?: throw BadRequestException("endDate is required when endType is ON_DATE")
                if (!endDate.isAfter(entity.planning.startDate)) {
                    throw BadRequestException("endDate must be after startDate")
                }
            }
            SchedulerEndType.OCCURRENCES -> {
                val count = entity.planning.occurrenceCount
                    ?: throw BadRequestException("occurrenceCount is required when endType is OCCURRENCES")
                if (count <= 0) throw BadRequestException("occurrenceCount must be > 0")
            }
            SchedulerEndType.NEVER -> Unit
        }
    }

    private fun saveOrConflict(entity: ScheduledPrompt): ScheduledPrompt =
        try {
            repository.save(entity)
        } catch (e: DataIntegrityViolationException) {
            if (!isTripleKeyConflict(e)) throw e
            logger.warn {
                "[ScheduledPromptService] tripleKey conflict (ns=${entity.namespaceId}, user=${entity.userId}, name='${entity.name}')"
            }
            throw ConflictException(conflictMessage(entity), e)
        }

    private fun isTripleKeyConflict(e: DataIntegrityViolationException): Boolean {
        val haystack = generateSequence<Throwable>(e) { it.cause }
            .mapNotNull { it.message }
            .joinToString(separator = " | ")
        return TRIPLE_KEY_CONSTRAINT_NAME in haystack || TRIPLE_KEY_PROPERTY in haystack
    }

    private fun conflictMessage(entity: ScheduledPrompt): String =
        "A ScheduledPrompt named '${entity.name}' already exists in this scope " +
            "(namespaceId=${entity.namespaceId ?: "platform"}, userId=${entity.userId})"

    companion object : KLogging() {
        private const val TRIPLE_KEY_CONSTRAINT_NAME = "scheduled_prompt_triple_key_unique"
        private const val TRIPLE_KEY_PROPERTY = "tripleKey"
    }
}
