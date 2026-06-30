package io.whozoss.agentos.prompt

import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Default implementation of [PromptService].
 *
 * Delegates persistence to [PromptRepository].
 *
 * Custom validation on [create] and [update]:
 * - Every element of [Prompt.content] must be non-blank.
 * - The names of [Prompt.parameters] must be unique within the list.
 */
@Service
class PromptServiceImpl(
    private val repository: PromptRepository,
) : PromptService {
    override fun create(entity: Prompt): Prompt {
        validate(entity)
        return repository.save(entity)
    }

    override fun update(entity: Prompt): Prompt {
        validate(entity)
        return repository.save(entity)
    }

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
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "content[$blankIndex] must not be blank",
            )
        }

        val duplicateName =
            prompt.parameters
                .groupBy { it.name }
                .entries
                .firstOrNull { it.value.size > 1 }
                ?.key
        if (duplicateName != null) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Duplicate parameter name '$duplicateName' — parameter names must be unique within a prompt",
            )
        }
    }

    companion object : KLogging()
}
