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
        assertConsistentApiTypeAcrossLayers(entity)
        return saveOrConflict(entity)
    }

    override fun update(entity: AiProvider): AiProvider {
        requireScope(entity)
        findByNamespaceAndUserAndName(entity.namespaceId, entity.userId, entity.name)
            ?.takeIf { it.id != entity.id }
            ?.let {
                throw ResponseStatusException(HttpStatus.CONFLICT, conflictMessage(entity))
            }
        assertConsistentApiTypeAcrossLayers(entity)
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

    override fun findFiltered(
        namespaceId: UUID?,
        namespaceIsNone: Boolean,
        callerId: UUID,
        userRequested: Boolean,
        canReadNamespace: (UUID) -> Boolean,
    ): List<AiProvider> = when {
        // NS-shared layer of a specific namespace (no userId param) : check READ permission
        namespaceId != null && !userRequested -> {
            if (!canReadNamespace(namespaceId)) emptyList()
            else findByNamespaceId(namespaceId).filter { it.userId == null }
        }

        // User-scoped (userId=me requested) : start from user's configs and narrow by namespace
        userRequested -> {
            val nsFilter: (UUID?) -> Boolean = when {
                namespaceIsNone -> { nsId -> nsId == null }
                namespaceId != null -> { nsId -> nsId == namespaceId }
                else -> { _ -> true }
            }
            findByUserId(callerId).filter { nsFilter(it.namespaceId) }
        }

        // No filter at all : surface the caller's own overlays
        else -> findByUserId(callerId)
    }

    private fun requireScope(entity: AiProvider) {
        if (entity.namespaceId == null && entity.userId == null) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "AiProvider must be scoped to at least a namespace or a user",
            )
        }
    }

    /**
     * Reject create/update when another layer that would merge with this entity at reconciliation
     * time already carries the same `name` but a different `apiType`. Mirrors the IG-3 guard in
     * [io.whozoss.agentos.integrationConfig.IntegrationConfigServiceImpl].
     *
     * The 3-tier reconciliation merges layers param-by-param assuming all layers share the same
     * `apiType`. If they diverge, the merged provider silently switches the chat client at
     * runtime — an Anthropic-typed entity ends up instantiating an OpenAI client with Anthropic
     * params, failing opaquely deep in the agent run.
     *
     * Cross-user comparison is intentionally not a conflict: each user's overlay is private and
     * never merges into another user's resolution.
     */
    private fun assertConsistentApiTypeAcrossLayers(entity: AiProvider) {
        val userId = entity.userId
        val namespaceId = entity.namespaceId
        val name = entity.name
        val type = entity.apiType
        val entityId = entity.metadata.id

        fun reject(other: AiProvider): Nothing =
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "An AiProvider named '$name' already exists in another layer with " +
                    "apiType='${other.apiType}' (this entity wants apiType='$type'). Overlay " +
                    "layers for the same name must share the same apiType to merge correctly at " +
                    "reconciliation time. Either use a different name or align the apiType.",
            )

        if (namespaceId != null) {
            repository.findByTriple(namespaceId, null, name)
                ?.takeIf { it.metadata.id != entityId && it.apiType != type }
                ?.let(::reject)
        }

        if (userId != null) {
            repository.findByTriple(null, userId, name)
                ?.takeIf { it.metadata.id != entityId && it.apiType != type }
                ?.let(::reject)

            if (namespaceId == null) {
                repository
                    .findByUserId(userId)
                    .firstOrNull {
                        it.metadata.id != entityId &&
                            it.namespaceId != null &&
                            it.name == name &&
                            it.apiType != type
                    }
                    ?.let(::reject)
            }
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
