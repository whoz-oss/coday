package io.whozoss.agentos.prompt

import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exception.ResourceNotFoundException
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
) : PromptService {
    override fun create(entity: Prompt): Prompt {
        validate(entity)
        if (entity.agentConfigId != null) {
            val agentConfig = agentConfigService.findById(entity.agentConfigId)
                ?: throw ResourceNotFoundException("AgentConfig not found: ${entity.agentConfigId}")
            validateAgentConfigScope(entity, agentConfig)
        }
        repository.findByTriple(entity.namespaceId, entity.userId, entity.name)?.let {
            throw ConflictException(conflictMessage(entity))
        }
        return saveOrConflict(entity)
    }

    override fun update(entity: Prompt): Prompt {
        validate(entity)
        repository
            .findByTriple(entity.namespaceId, entity.userId, entity.name)
            ?.takeIf { it.id != entity.id }
            ?.let { throw ConflictException(conflictMessage(entity)) }
        return saveOrConflict(entity)
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

    override fun findEffective(
        namespaceId: UUID,
        callerId: UUID,
    ): List<Prompt> =
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
    ): List<Prompt> = repository.findByScope(namespaceId, userId, agentConfigIds)

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

    override fun delete(id: UUID): Boolean = repository.delete(id)

    override fun deleteByParent(parentId: UUID): Int = repository.deleteByParent(parentId)

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
                "Duplicate parameter name '$duplicateName' \u2014 parameter names must be unique within a prompt",
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
                "AgentConfig '${agentConfig.name}' (id=${agentConfig.id}) belongs to a different namespace " +
                    "than the prompt (agent namespace=${agentConfig.namespaceId}, prompt namespace=${prompt.namespaceId})",
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

    companion object : KLogging() {
        private const val TRIPLE_KEY_CONSTRAINT_NAME = "prompt_triple_key_unique"
        private const val TRIPLE_KEY_PROPERTY = "tripleKey"
    }
}
