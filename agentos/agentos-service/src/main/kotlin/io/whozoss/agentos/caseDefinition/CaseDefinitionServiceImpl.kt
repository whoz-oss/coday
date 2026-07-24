package io.whozoss.agentos.caseDefinition

import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.prompt.PromptService
import mu.KLogging
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Default implementation of [CaseDefinitionService].
 *
 * Delegates persistence to [CaseDefinitionRepository].
 *
 * ### Validation on [create]
 *
 * - [name] must match the slug pattern `^[a-z][a-z0-9]*(-[a-z0-9]+)*$` (new creations only).
 * - [agentConfigId] must reference an existing, non-filesystem AgentConfig.
 * - [promptId] must reference an existing Prompt.
 * - The Prompt MUST NOT have an `agentConfigId` (only generic prompts are allowed).
 * - The AgentConfig's namespace must be compatible with the CaseDefinition's namespace.
 * - Name uniqueness per scope is enforced by the `tripleKey` UNIQUE constraint in Neo4j.
 *
 * ### TODO: Cascade delete
 *
 * TODO: Cascade delete — When an AgentConfig is soft-deleted, its linked CaseDefinitions
 * should also be soft-deleted. This is deferred because the cascade strategy needs to be
 * designed holistically when other scheduler types (with convergent structure) are introduced.
 * Pattern reference: PromptServiceImpl has softDeleteByAgentConfigId() which could serve as a pattern.
 */
@Service
class CaseDefinitionServiceImpl(
    private val repository: CaseDefinitionRepository,
    private val agentConfigService: AgentConfigService,
    private val promptService: PromptService,
) : CaseDefinitionService {

    override fun create(entity: CaseDefinition): CaseDefinition {
        // 1. Validate slug format on name (new creations only)
        require(entity.name.matches(SLUG_REGEX)) {
            "CaseDefinition name must be in slug format: lowercase alphanumeric with hyphens " +
                "(e.g. 'daily-standup'). Got: '${entity.name}'"
        }

        // 2. Validate agentConfig exists and is not filesystem-only
        val agentConfig = agentConfigService.findById(entity.agentConfigId)
            ?: throw ResourceNotFoundException("AgentConfig not found: ${entity.agentConfigId}")
        if (agentConfig.metadata.version == null) {
            throw UnprocessableEntityException(
                "AgentConfig id=${entity.agentConfigId} is a filesystem-only agent and cannot be linked to a CaseDefinition",
            )
        }

        // 3. Validate agentConfig scope compatibility
        validateAgentConfigScope(entity, agentConfig)

        // 4. Validate prompt exists
        val prompt = promptService.findById(entity.promptId)
            ?: throw ResourceNotFoundException("Prompt not found: ${entity.promptId}")

        // 5. Validate prompt has no agentConfigId — only generic prompts are allowed
        if (prompt.agentConfigId != null) {
            throw BadRequestException(
                "Prompt ${entity.promptId} is linked to agent ${prompt.agentConfigId}. " +
                    "Only generic prompts (agentConfigId = null) may be associated with a CaseDefinition.",
            )
        }

        // 6. Check for name uniqueness within scope (applicative pre-check for better 409 message)
        repository.findByTriple(entity.namespaceId, entity.userId, entity.name)?.let {
            throw ConflictException(conflictMessage(entity))
        }

        return saveOrConflict(entity)
    }

    override fun update(entity: CaseDefinition): CaseDefinition {
        // On update, slug validation is NOT applied (not retroactive).
        // Validate prompt exists
        promptService.findById(entity.promptId)
            ?: throw ResourceNotFoundException("Prompt not found: ${entity.promptId}")

        // Check for name uniqueness (excluding self)
        repository.findByTriple(entity.namespaceId, entity.userId, entity.name)
            ?.takeIf { it.id != entity.id }
            ?.let { throw ConflictException(conflictMessage(entity)) }

        return saveOrConflict(entity)
    }

    override fun findById(
        id: UUID,
        withRemoved: Boolean,
    ): CaseDefinition? = repository.findByIds(listOf(id), withRemoved).firstOrNull()

    override fun findByIds(
        ids: Collection<UUID>,
        withRemoved: Boolean,
    ): List<CaseDefinition> = repository.findByIds(ids, withRemoved)

    override fun findByParent(parentId: UUID): List<CaseDefinition> = repository.findByParent(parentId)

    override fun findPlatform(): List<CaseDefinition> = repository.findPlatform()

    override fun findEffective(
        namespaceId: UUID,
        callerId: UUID,
    ): List<CaseDefinition> =
        repository
            .findEffective(namespaceId, callerId)
            .sortedBy { layerPriority(it) }
            .groupBy { it.name }
            .map { (_, layers) -> layers.last() }
            .sortedBy { it.name }

    override fun findByScope(
        namespaceId: UUID?,
        userId: UUID?,
        agentConfigIds: List<UUID>?,
    ): List<CaseDefinition> = repository.findByScope(namespaceId, userId, agentConfigIds)

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
            cd.namespaceId == null && cd.userId == null -> 0 // platform
            cd.namespaceId == null -> 1 // user-global
            cd.userId == null -> 2 // namespace-shared
            else -> 3 // user×namespace
        }

    private fun validateAgentConfigScope(entity: CaseDefinition, agentConfig: AgentConfig) {
        val validScope = when {
            // Platform agent is always valid (accessible from any scope)
            agentConfig.namespaceId == null -> true
            // Same namespace
            agentConfig.namespaceId == entity.namespaceId -> true
            // Everything else is cross-scope
            else -> false
        }
        if (!validScope) {
            throw BadRequestException(
                "AgentConfig id=${entity.agentConfigId} does not belong to the CaseDefinition's namespace",
            )
        }
    }

    private fun saveOrConflict(entity: CaseDefinition): CaseDefinition =
        try {
            repository.save(entity)
        } catch (e: DataIntegrityViolationException) {
            if (!isTripleKeyConflict(e)) throw e
            logger.warn {
                "[CaseDefinitionService] tripleKey unique-constraint violation on save " +
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

    private fun conflictMessage(entity: CaseDefinition): String =
        "A CaseDefinition named '${entity.name}' already exists in this scope " +
            "(namespaceId=${entity.namespaceId ?: "platform"}, userId=${entity.userId})"

    companion object : KLogging() {
        private val SLUG_REGEX = Regex("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")
        private const val TRIPLE_KEY_CONSTRAINT_NAME = "case_definition_triple_key_unique"
        private const val TRIPLE_KEY_PROPERTY = "tripleKey"
    }
}
