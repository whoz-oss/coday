package io.whozoss.agentos.caseDefinition

import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.prompt.PromptService
import io.whozoss.agentos.sdk.api.caseDefinition.SchedulerEndType
import mu.KLogging
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Default implementation of [CaseDefinitionService].
 *
 * ### Validation on [create]
 *
 * - [recurrence.every][Recurrence.every] must be > 0.
 * - [name] must match `^[a-z][a-z0-9]*(-[a-z0-9]+)*$` (new creations only).
 * - [agentConfigId] must reference an existing, non-filesystem AgentConfig.
 * - [promptId] must reference an existing generic Prompt (agentConfigId = null).
 * - The AgentConfig's namespace must be compatible with the CaseDefinition's namespace.
 * - [planning.endDate][Planning.endDate] required when endType == ON_DATE, must be after startDate.
 * - [planning.occurrenceCount][Planning.occurrenceCount] required and > 0 when endType == OCCURRENCES.
 * - Name uniqueness per scope enforced by the `tripleKey` UNIQUE constraint in Neo4j.
 */
@Service
class CaseDefinitionServiceImpl(
    private val repository: CaseDefinitionRepository,
    private val agentConfigService: AgentConfigService,
    private val promptService: PromptService,
) : CaseDefinitionService {

    override fun create(entity: CaseDefinition): CaseDefinition {
        // 1. Validate recurrence.every > 0
        require(entity.recurrence.every > 0) {
            "CaseDefinition 'every' must be > 0. Got: ${entity.recurrence.every}"
        }

        // 2. Validate slug format (new creations only)
        require(entity.name.matches(SLUG_REGEX)) {
            "CaseDefinition name must be in slug format (e.g. 'daily-standup'). Got: '${entity.name}'"
        }

        // 3. Validate agentConfig exists and is not filesystem-only
        val agentConfig = agentConfigService.findById(entity.agentConfigId)
            ?: throw ResourceNotFoundException("AgentConfig not found: ${entity.agentConfigId}")
        if (agentConfig.metadata.version == null) {
            throw UnprocessableEntityException(
                "AgentConfig id=${entity.agentConfigId} is a filesystem-only agent and cannot be linked to a CaseDefinition",
            )
        }

        // 4. Validate agentConfig scope compatibility
        validateAgentConfigScope(entity, agentConfig)

        // 5. Validate prompt exists
        val prompt = promptService.findById(entity.promptId)
            ?: throw ResourceNotFoundException("Prompt not found: ${entity.promptId}")

        // 6. Validate prompt has no agentConfigId (only generic prompts allowed)
        if (prompt.agentConfigId != null) {
            throw BadRequestException(
                "Prompt ${entity.promptId} is linked to agent ${prompt.agentConfigId}. " +
                    "Only generic prompts (agentConfigId = null) may be associated with a CaseDefinition.",
            )
        }

        // 7. Validate end condition
        validateEndCondition(entity)

        // 8. Check name uniqueness within scope
        repository.findByTriple(entity.namespaceId, entity.userId, entity.name)?.let {
            throw ConflictException(conflictMessage(entity))
        }

        return saveOrConflict(entity)
    }

    override fun update(entity: CaseDefinition): CaseDefinition {
        promptService.findById(entity.promptId)
            ?: throw ResourceNotFoundException("Prompt not found: ${entity.promptId}")

        repository.findByTriple(entity.namespaceId, entity.userId, entity.name)
            ?.takeIf { it.id != entity.id }
            ?.let { throw ConflictException(conflictMessage(entity)) }

        return saveOrConflict(entity)
    }

    override fun findById(id: UUID, withRemoved: Boolean): CaseDefinition? =
        repository.findByIds(listOf(id), withRemoved).firstOrNull()

    override fun findByIds(ids: Collection<UUID>, withRemoved: Boolean): List<CaseDefinition> =
        repository.findByIds(ids, withRemoved)

    override fun findByParent(parentId: UUID): List<CaseDefinition> = repository.findByParent(parentId)

    override fun findPlatform(): List<CaseDefinition> = repository.findPlatform()

    override fun findEffective(namespaceId: UUID, callerId: UUID): List<CaseDefinition> =
        repository
            .findEffective(namespaceId, callerId)
            .sortedBy { layerPriority(it) }
            .groupBy { it.name }
            .map { (_, layers) -> layers.last() }
            .sortedBy { it.name }

    override fun findByScope(namespaceId: UUID?, userId: UUID?, agentConfigIds: List<UUID>?): List<CaseDefinition> =
        repository.findByScope(namespaceId, userId, agentConfigIds)

    override fun toggle(id: UUID): CaseDefinition {
        val existing = repository.findById(id)
            ?: throw ResourceNotFoundException("CaseDefinition not found: $id")
        return repository.save(existing.copy(enabled = !existing.enabled))
            .also { logger.info { "[CaseDefinition] Toggled enabled=${it.enabled} on $id" } }
    }

    override fun delete(id: UUID): Boolean =
        repository.delete(id)
            .also { if (it) logger.info { "[CaseDefinition] Soft-deleted $id" } }

    override fun deleteByParent(parentId: UUID): Int = repository.deleteByParent(parentId)

    private fun layerPriority(cd: CaseDefinition): Int =
        when {
            cd.namespaceId == null && cd.userId == null -> 0
            cd.namespaceId == null -> 1
            cd.userId == null -> 2
            else -> 3
        }

    private fun validateAgentConfigScope(entity: CaseDefinition, agentConfig: AgentConfig) {
        val validScope = agentConfig.namespaceId == null || agentConfig.namespaceId == entity.namespaceId
        if (!validScope) {
            throw BadRequestException(
                "AgentConfig id=${entity.agentConfigId} does not belong to the CaseDefinition's namespace",
            )
        }
    }

    private fun validateEndCondition(entity: CaseDefinition) {
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

    private fun saveOrConflict(entity: CaseDefinition): CaseDefinition =
        try {
            repository.save(entity)
        } catch (e: DataIntegrityViolationException) {
            if (!isTripleKeyConflict(e)) throw e
            logger.warn {
                "[CaseDefinitionService] tripleKey conflict (ns=${entity.namespaceId}, user=${entity.userId}, name='${entity.name}')"
            }
            throw ConflictException(conflictMessage(entity), e)
        }

    private fun isTripleKeyConflict(e: DataIntegrityViolationException): Boolean {
        val haystack = generateSequence<Throwable>(e) { it.cause }
            .mapNotNull { it.message }
            .joinToString(separator = " | ")
        return TRIPLE_KEY_CONSTRAINT_NAME in haystack || TRIPLE_KEY_PROPERTY in haystack
    }

    private fun conflictMessage(entity: CaseDefinition): String =
        "A CaseDefinition named '${entity.name}' already exists in this scope " +
            "(namespaceId=${entity.namespaceId ?: "platform"}, userId=${entity.userId})"

    companion object : KLogging() {
        private val SLUG_REGEX = Regex("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")
        private const val TRIPLE_KEY_CONSTRAINT_NAME = "case_definition_triple_key_unique"
        private const val TRIPLE_KEY_PROPERTY = "tripleKey"
    }
}
