package io.whozoss.agentos.prompt

import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ConflictException
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
 * Name uniqueness per scope is enforced by the `scopeKey` UNIQUE constraint in Neo4j.
 * An applicative pre-check gives a descriptive 409 message; the catch on
 * [DataIntegrityViolationException] handles concurrent inserts that race past the
 * pre-check (mirrors the IntegrationConfigServiceImpl pattern).
 */
@Service
class PromptServiceImpl(
    private val repository: PromptRepository,
) : PromptService {
    override fun create(entity: Prompt): Prompt {
        validate(entity)
        return saveOrConflict(entity)
    }

    override fun update(entity: Prompt): Prompt {
        validate(entity)
        return saveOrConflict(entity)
    }

    override fun findById(id: UUID, withRemoved: Boolean): Prompt? =
        repository.findByIds(listOf(id), withRemoved).firstOrNull()

    override fun findByIds(
        ids: Collection<UUID>,
        withRemoved: Boolean,
    ): List<Prompt> = repository.findByIds(ids, withRemoved)

    override fun findByParent(parentId: UUID): List<Prompt> = repository.findByParent(parentId)

    override fun findByNamespaceId(namespaceId: UUID): List<Prompt> = repository.findByNamespaceId(namespaceId)

    override fun findPlatform(): List<Prompt> = repository.findPlatform()

    override fun delete(id: UUID): Boolean = repository.delete(id)

    override fun deleteByParent(parentId: UUID): Int = repository.deleteByParent(parentId)

    /**
     * Validates business rules that cannot be expressed via Bean Validation:
     * - Each content element must be non-blank.
     * - Parameter names must be unique within the list.
     */
    private fun validate(prompt: Prompt) {
        val blankIndex = prompt.content.indexOfFirst { it.isBlank() }
        if (blankIndex >= 0) {
            throw BadRequestException("content[$blankIndex] must not be blank")
        }

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

    private fun saveOrConflict(entity: Prompt): Prompt =
        try {
            repository.save(entity)
        } catch (e: DataIntegrityViolationException) {
            if (!isScopeKeyConflict(e)) {
                throw e
            }
            logger.warn {
                "[PromptService] scopeKey unique-constraint violation on save " +
                    "(namespaceId=${entity.namespaceId}, name='${entity.name}')"
            }
            throw ConflictException(conflictMessage(entity), e)
        }

    private fun isScopeKeyConflict(e: DataIntegrityViolationException): Boolean {
        val haystack =
            generateSequence<Throwable>(e) { it.cause }
                .mapNotNull { it.message }
                .joinToString(separator = " | ")
        return SCOPE_KEY_CONSTRAINT_NAME in haystack || SCOPE_KEY_PROPERTY in haystack
    }

    private fun conflictMessage(entity: Prompt): String =
        "A prompt named '${entity.name}' already exists in this scope " +
            "(namespaceId=${entity.namespaceId ?: "platform"})"

    companion object : KLogging() {
        private const val SCOPE_KEY_CONSTRAINT_NAME = "prompt_scope_key_unique"
        private const val SCOPE_KEY_PROPERTY = "scopeKey"
    }
}
