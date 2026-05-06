package io.whozoss.agentos.integrationConfig

import mu.KLogging
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Default implementation of [IntegrationConfigService].
 *
 * Delegates persistence to [IntegrationConfigRepository].
 *
 * Triple-mode invariant — `(namespaceId != null) OR (userId != null)` — is enforced on both
 * [create] and [update] (defence-in-depth, even if the controller already validates). A
 * violation surfaces as HTTP 400 to align with the [io.whozoss.agentos.aiProvider.AiProviderServiceImpl]
 * pattern referenced by story 6.1.
 *
 * Uniqueness on the (namespaceId, userId, name) triple is enforced on [create] (409 on
 * conflict) and on [update] when a rename would collide with another row in the same scope.
 *
 * The applicative pre-check ([findByNamespaceAndUserAndName]) is kept for the common case so the
 * caller gets a descriptive 409 message; the catch on [DataIntegrityViolationException] is the
 * defence against concurrent inserts that race past the pre-check (the DB-level unique constraint
 * on `tripleKey` catches the loser).
 */
@Service
class IntegrationConfigServiceImpl(
    private val repository: IntegrationConfigRepository,
) : IntegrationConfigService {
    override fun create(entity: IntegrationConfig): IntegrationConfig {
        requireScope(entity)
        findByNamespaceAndUserAndName(entity.namespaceId, entity.userId, entity.name)?.let {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                conflictMessage(entity),
            )
        }
        return saveOrConflict(entity)
    }

    override fun update(entity: IntegrationConfig): IntegrationConfig {
        requireScope(entity)
        findByNamespaceAndUserAndName(entity.namespaceId, entity.userId, entity.name)
            ?.takeIf { it.id != entity.id }
            ?.let {
                throw ResponseStatusException(
                    HttpStatus.CONFLICT,
                    conflictMessage(entity),
                )
            }
        return saveOrConflict(entity)
    }

    override fun findByIds(ids: Collection<UUID>): List<IntegrationConfig> = repository.findByIds(ids)

    override fun findByParent(parentId: UUID): List<IntegrationConfig> = repository.findByParent(parentId)

    override fun delete(id: UUID): Boolean = repository.delete(id)

    override fun deleteByParent(parentId: UUID): Int = repository.deleteByParent(parentId)

    override fun findByNamespaceAndUserAndName(
        namespaceId: UUID?,
        userId: UUID?,
        name: String,
    ): IntegrationConfig? = repository.findByTriple(namespaceId, userId, name)

    override fun findByNamespaceAndName(
        namespaceId: UUID,
        name: String,
    ): IntegrationConfig? = findByNamespaceAndUserAndName(namespaceId, null, name)

    override fun findByUserId(userId: UUID): List<IntegrationConfig> = repository.findByUserId(userId)

    override fun findByNamespaceShared(namespaceId: UUID): List<IntegrationConfig> = repository.findByParent(namespaceId)

    private fun requireScope(entity: IntegrationConfig) {
        if (entity.namespaceId == null && entity.userId == null) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "IntegrationConfig must be scoped to at least a namespace or a user",
            )
        }
    }

    private fun saveOrConflict(entity: IntegrationConfig): IntegrationConfig =
        try {
            repository.save(entity)
        } catch (e: DataIntegrityViolationException) {
            // Catches the race window: two concurrent creates pass the applicative pre-check,
            // both reach `save`, the DB unique constraint on `tripleKey` rejects one of them.
            // We only translate to 409 when the violation is identifiably the triple-uniqueness
            // constraint — any other integrity error (future constraints, NOT NULL breaches,
            // edge-mismatch, …) is rethrown so it surfaces as a 500 with an honest stack trace
            // rather than a misleading "name already exists" message.
            if (!isTripleKeyConflict(e)) {
                throw e
            }
            // Do NOT chain `e` to the logger: the Neo4j driver's `DataIntegrityViolationException`
            // message can echo property values (incl. the offending row's `parameters` JSON which
            // may contain credentials, cf. NFR-SEC-4). The exception is preserved as the cause of
            // the rethrown `ResponseStatusException` for stack-trace continuity at the framework
            // boundary.
            logger.warn {
                "[IntegrationConfigService] tripleKey unique-constraint violation on save " +
                    "(namespaceId=${entity.namespaceId}, userId=${entity.userId}, name='${entity.name}')"
            }
            throw ResponseStatusException(HttpStatus.CONFLICT, conflictMessage(entity), e)
        }

    /**
     * Inspect the exception chain (Spring DAO wraps the Neo4j driver error) for a marker that
     * unambiguously identifies the `integration_config_triple_key_unique` constraint. Both the
     * constraint name and the property name are checked because Neo4j 5.x error messages vary
     * across server versions and translation layers.
     */
    private fun isTripleKeyConflict(e: DataIntegrityViolationException): Boolean {
        val haystack =
            generateSequence<Throwable>(e) { it.cause }
                .mapNotNull { it.message }
                .joinToString(separator = " | ")
        return TRIPLE_KEY_CONSTRAINT_NAME in haystack || TRIPLE_KEY_PROPERTY in haystack
    }

    private fun conflictMessage(entity: IntegrationConfig): String =
        "An integration config named '${entity.name}' already exists for this scope " +
            "(namespaceId=${entity.namespaceId}, userId=${entity.userId})"

    companion object : KLogging() {
        private const val TRIPLE_KEY_CONSTRAINT_NAME = "integration_config_triple_key_unique"
        private const val TRIPLE_KEY_PROPERTY = "tripleKey"
    }
}
