package io.whozoss.agentos.aiProvider

import io.whozoss.agentos.sdk.aiProvider.AiProvider
import mu.KLogging
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Default implementation of [AiProviderService].
 *
 * Triple-mode invariant — `(namespaceId != null) OR (userId != null)` — is enforced on both
 * [create] and [update] (defence-in-depth, even if the controller already validates). A
 * violation surfaces as HTTP 400.
 *
 * Uniqueness on the (namespaceId, userId, name) triple is enforced on [create] (409 on
 * conflict) and on [update] when a rename would collide with another row in the same scope.
 *
 * The applicative pre-check ([findByNamespaceAndUserAndName]) is kept for the common case
 * so the caller gets a descriptive 409 message; the catch on [DataIntegrityViolationException]
 * is the defence against concurrent inserts that race past the pre-check (the DB-level
 * unique constraint on `tripleKey` catches the loser). Mirrors the
 * [io.whozoss.agentos.integrationConfig.IntegrationConfigServiceImpl] pattern (story 6.1
 * IG-7 homogenisation).
 */
@Service
class AiProviderServiceImpl(
    private val repository: AiProviderRepository,
) : AiProviderService {
    override fun create(entity: AiProvider): AiProvider {
        requireScope(entity)
        findByNamespaceAndUserAndName(entity.namespaceId, entity.userId, entity.name)?.let {
            throw ResponseStatusException(HttpStatus.CONFLICT, conflictMessage(entity))
        }
        return saveOrConflict(entity)
    }

    override fun update(entity: AiProvider): AiProvider {
        requireScope(entity)
        findByNamespaceAndUserAndName(entity.namespaceId, entity.userId, entity.name)
            ?.takeIf { it.id != entity.id }
            ?.let {
                throw ResponseStatusException(HttpStatus.CONFLICT, conflictMessage(entity))
            }
        return saveOrConflict(entity)
    }

    override fun findByIds(ids: Collection<UUID>): List<AiProvider> = repository.findByIds(ids)

    override fun findByParent(parentId: UUID): List<AiProvider> = repository.findByParent(parentId)

    override fun delete(id: UUID): Boolean = repository.delete(id)

    override fun deleteByParent(parentId: UUID): Int = repository.deleteByParent(parentId)

    override fun findByNamespaceId(namespaceId: UUID): List<AiProvider> = repository.findByNamespaceId(namespaceId)

    override fun findByUserId(userId: UUID): List<AiProvider> = repository.findByUserId(userId)

    override fun findByNamespaceAndUserAndName(
        namespaceId: UUID?,
        userId: UUID?,
        name: String,
    ): AiProvider? = repository.findByTriple(namespaceId, userId, name)

    private fun requireScope(entity: AiProvider) {
        if (entity.namespaceId == null && entity.userId == null) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "AiProvider must be scoped to at least a namespace or a user",
            )
        }
    }

    private fun saveOrConflict(entity: AiProvider): AiProvider =
        try {
            repository.save(entity)
        } catch (e: DataIntegrityViolationException) {
            // Catches the race window: two concurrent creates pass the applicative pre-check,
            // both reach `save`, the DB unique constraint on `tripleKey` rejects one of them.
            // Translate to 409 only when the violation is identifiably the triple-uniqueness
            // constraint — any other integrity error is rethrown so it surfaces as 500 with
            // an honest stack trace rather than a misleading "name already exists" message.
            if (!isTripleKeyConflict(e)) {
                throw e
            }
            // Do NOT chain `e` to the logger: the Neo4j driver's exception message can echo
            // property values (incl. `apiKey` for AiProvider, NFR-SEC-4). The exception is
            // preserved as the cause of the rethrown ResponseStatusException for stack-trace
            // continuity at the framework boundary.
            logger.warn {
                "[AiProviderService] tripleKey unique-constraint violation on save " +
                    "(namespaceId=${entity.namespaceId}, userId=${entity.userId}, name='${entity.name}')"
            }
            throw ResponseStatusException(HttpStatus.CONFLICT, conflictMessage(entity), e)
        }

    private fun isTripleKeyConflict(e: DataIntegrityViolationException): Boolean {
        val haystack =
            generateSequence<Throwable>(e) { it.cause }
                .mapNotNull { it.message }
                .joinToString(separator = " | ")
        return TRIPLE_KEY_CONSTRAINT_NAME in haystack || TRIPLE_KEY_PROPERTY in haystack
    }

    private fun conflictMessage(entity: AiProvider): String =
        "An AI provider named '${entity.name}' already exists for this scope " +
            "(namespaceId=${entity.namespaceId}, userId=${entity.userId})"

    companion object : KLogging() {
        private const val TRIPLE_KEY_CONSTRAINT_NAME = "ai_provider_triple_key_unique"
        private const val TRIPLE_KEY_PROPERTY = "tripleKey"
    }
}
