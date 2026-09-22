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
 * Four scope modes are supported: platform `(null, null)`, namespace-shared `(ns, null)`,
 * user-global `(null, user)`, and user×namespace `(ns, user)`. The service accepts all four;
 * authorization (only Super Admins may write platform-level configs) is enforced in the
 * controller where the security context is available.
 *
 * Uniqueness on the (namespaceId, userId, name) triple is enforced on [create] (409 on
 * conflict) and on [update] when a rename would collide with another row in the same scope.
 *
 * The applicative pre-check ([findByTriple]) is kept for the common case so the
 * caller gets a descriptive 409 message; the catch on [DataIntegrityViolationException] is the
 * defence against concurrent inserts that race past the pre-check (the DB-level unique constraint
 * on `tripleKey` catches the loser).
 */
@Service
class IntegrationConfigServiceImpl(
    private val repository: IntegrationConfigRepository,
    private val mergeStrategy: IntegrationConfigMergeStrategy,
    /**
     * Type-specific policies, collected by Spring. Empty by default so a context without any
     * — and every unit test building this service directly — behaves as before.
     */
    private val policies: List<IntegrationConfigPolicy> = emptyList(),
) : IntegrationConfigService {
    override fun create(entity: IntegrationConfig): IntegrationConfig {
        findByTriple(entity.namespaceId, entity.userId, entity.name)?.let {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                conflictMessage(entity),
            )
        }
        assertNamespaceSingletonRules(entity)
        assertTypeSpecificRules(entity)
        assertConsistentIntegrationTypeAcrossLayers(entity)
        return saveOrConflict(entity).also { saved ->
            policies.filter { it.supports(saved.integrationType) }.forEach { it.afterSave(saved) }
        }
    }

    override fun update(entity: IntegrationConfig): IntegrationConfig {
        findByTriple(entity.namespaceId, entity.userId, entity.name)
            ?.takeIf { it.id != entity.id }
            ?.let {
                throw ResponseStatusException(
                    HttpStatus.CONFLICT,
                    conflictMessage(entity),
                )
            }
        assertNamespaceSingletonRules(entity)
        assertTypeSpecificRules(entity)
        assertConsistentIntegrationTypeAcrossLayers(entity)
        return saveOrConflict(entity).also { saved ->
            policies.filter { it.supports(saved.integrationType) }.forEach { it.afterSave(saved) }
        }
    }

    override fun findByIds(
        ids: Collection<UUID>,
        withRemoved: Boolean,
    ): List<IntegrationConfig> = repository.findByIds(ids, withRemoved)

    override fun findByParent(parentId: UUID): List<IntegrationConfig> = repository.findByParent(parentId)

    override fun delete(id: UUID): Boolean = repository.delete(id)

    override fun deleteByParent(parentId: UUID): Int = repository.deleteByParent(parentId)

    override fun findByTriple(
        namespaceId: UUID?,
        userId: UUID?,
        name: String,
    ): IntegrationConfig? = repository.findByTriple(namespaceId, userId, name)

    override fun findByNamespaceAndName(
        namespaceId: UUID,
        name: String,
    ): IntegrationConfig? = findByTriple(namespaceId, null, name)

    override fun findByUserId(userId: UUID): List<IntegrationConfig> = repository.findByUserId(userId)

    override fun findPlatform(): List<IntegrationConfig> = repository.findPlatform()

    override fun findByNamespaceShared(namespaceId: UUID): List<IntegrationConfig> = repository.findByParent(namespaceId)

    override fun findActiveNamespaceSingleton(
        namespaceId: UUID,
        integrationType: String,
    ): IntegrationConfig? = repository.findActiveNamespaceSingleton(namespaceId, integrationType)

    override fun findEffective(
        namespaceId: UUID?,
        userId: UUID?,
    ): List<IntegrationConfig> =
        repository
            .findAllForNamespaceIdAndUserId(
                namespaceId = namespaceId,
                userId = userId,
            ).groupBy { it.name }
            .mapNotNull { (_, configs) ->
                if (configs.size == 1) {
                    configs.first()
                } else {
                    val sorted = configs.sortedWith(LAYER_COMPARATOR)
                    sorted.drop(1).fold(
                        initial = sorted.first(),
                    ) { base, override ->
                        if (LAYER_COMPARATOR.compare(base, override) == 0) {
                            logger.warn {
                                "[IntegrationConfigService] Inconsistency detected as same level between configs ${base.id} and ${override.id}"
                            }
                        }
                        mergeStrategy.merge(base, override)
                    }
                }
            }

    override fun findFiltered(
        namespaceId: UUID?,
        namespaceIsNone: Boolean,
        callerId: UUID,
        userRequested: Boolean,
        canReadNamespace: (UUID) -> Boolean,
    ): List<IntegrationConfig> =
        when {
            // Explicit platform scope, available to all authenticated users.
            namespaceIsNone && !userRequested -> findPlatform()

            // NS-shared layer of a specific namespace (no userId param) : check READ permission
            namespaceId != null && !userRequested -> {
                if (!canReadNamespace(namespaceId)) {
                    emptyList()
                } else {
                    findByNamespaceShared(namespaceId)
                }
            }

            // User-scoped (userId=me requested) : start from user's configs and narrow by namespace
            userRequested -> {
                val nsFilter: (UUID?) -> Boolean =
                    when {
                        namespaceIsNone -> { nsId -> nsId == null }
                        namespaceId != null -> { nsId -> nsId == namespaceId }
                        else -> { _ -> true }
                    }
                findByUserId(callerId).filter { nsFilter(it.namespaceId) }
            }

            // No filter at all : surface the caller's own overlays
            else -> {
                findByUserId(callerId)
            }
        }

    /**
     * Reject create/update when another layer that would merge with this entity at reconciliation
     * time already carries the same `name` but a different `integrationType` (review IG-3).
     *
     * The 3-tier reconciliation merges layers param-by-param **assuming all layers share the same
     * `integrationType`**. If they diverge, the merged config silently switches the plugin at
     * runtime — a JIRA-typed entity ends up instantiating a FILE_ACCESS plugin with JIRA params,
     * which fails opaquely deep in the agent run.
     *
     * Layers checked depending on the saved entity's scope:
     * - `(ns, user)` → NS shared `(ns, null, name)` AND user-global of the same user `(null, user, name)`
     * - `(ns, null)` (NS-shared write) → no conflict possible against user-only layers (each user's
     *   own override is private; cross-user comparison is by-design legitimate)
     * - `(null, user)` (user-global write) → user×ns of the same user `(*, user, name)` for any
     *   namespace; NS-cross checks are skipped (would require an admin-side check across all
     *   namespaces the user can read — out of MVP scope, and the conflict would surface again
     *   when the user creates an explicit user×ns override on the conflicting namespace).
     */
    private fun assertConsistentIntegrationTypeAcrossLayers(entity: IntegrationConfig) {
        val userId = entity.userId
        val namespaceId = entity.namespaceId
        val name = entity.name
        val type = entity.integrationType
        val entityId = entity.id

        fun reject(other: IntegrationConfig): Nothing =
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "An IntegrationConfig named '$name' already exists in another layer with " +
                    "integrationType='${other.integrationType}' (this entity wants " +
                    "integrationType='$type'). Overlay layers for the same name must share the " +
                    "same integrationType to merge correctly at reconciliation time. Either use " +
                    "a different name or align the integrationType.",
            )

        // Platform layer: always checked — any entity with the same name at platform scope
        // participates in the reconciliation chain for every namespace and user.
        repository
            .findByTriple(null, null, name)
            ?.takeIf { it.id != entityId && it.integrationType != type }
            ?.let(::reject)

        // NS layer (always checked for entities that target a namespace, even pure NS-shared ones —
        // catches the case of an admin renaming a NS config to a name already used by a user-overlay
        // with a different type, which would also break the merge for that user).
        if (namespaceId != null) {
            repository
                .findByTriple(namespaceId, null, name)
                ?.takeIf { it.id != entityId && it.integrationType != type }
                ?.let(::reject)
        }

        if (userId != null) {
            // user-global of the same user
            repository
                .findByTriple(null, userId, name)
                ?.takeIf { it.id != entityId && it.integrationType != type }
                ?.let(::reject)

            if (namespaceId == null) {
                // Entity is user-global → also check user×ns of the same user across all
                // namespaces. Volume bounded by `findByUserId` — a single user's overlay set
                // is small in practice (single-digit hits typical, capped by app-level pagination
                // on the user-scope endpoints).
                repository
                    .findByUserId(userId)
                    .firstOrNull {
                        it.id != entityId &&
                            it.namespaceId != null &&
                            it.name == name &&
                            it.integrationType != type
                    }?.let(::reject)
            }
        }
    }

    /**
     * Enforce the two rules that apply to a type describing a namespace capability rather than a
     * tool (see [IntegrationTypeConstraints]): namespace-shared scope only, and one active row.
     *
     * The uniqueness check here exists for the message, not for the guarantee — two concurrent
     * creates both pass it. The guarantee is the `integration_config_singleton_key_unique`
     * constraint, whose violation [saveOrConflict] translates.
     */
    private fun assertNamespaceSingletonRules(entity: IntegrationConfig) {
        if (!IntegrationTypeConstraints.isNamespaceSingleton(entity.integrationType)) return

        val namespaceId = entity.namespaceId
        if (namespaceId == null || entity.userId != null) {
            throw ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "An integration of type '${entity.integrationType}' configures the namespace itself, so it must " +
                    "be namespace-shared (namespaceId set, userId null). It was requested with " +
                    "namespaceId=${entity.namespaceId}, userId=${entity.userId}. A personal or platform-level " +
                    "row of this type would be merged by name into a member's effective configuration and could " +
                    "redirect the namespace's provisioning.",
            )
        }

        repository
            .findActiveNamespaceSingleton(namespaceId, entity.integrationType)
            ?.takeIf { it.id != entity.id }
            ?.let { existing ->
                throw ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Namespace $namespaceId already has an active '${entity.integrationType}' configuration " +
                        "('${existing.name}'). Update that one, or remove it first.",
                )
            }
    }

    /**
     * Apply the validation owned by this configuration's type.
     *
     * Without this, the generic CRUD accepts any JSON in `parameters` and a malformed association
     * is stored with a 201, failing only when something later tries to use it. Validating here
     * keeps the failure where the mistake was made.
     */
    private fun assertTypeSpecificRules(entity: IntegrationConfig) {
        policies
            .filter { it.supports(entity.integrationType) }
            .forEach { it.validate(entity) }
    }

    private fun saveOrConflict(entity: IntegrationConfig): IntegrationConfig =
        try {
            repository.save(entity)
        } catch (e: DataIntegrityViolationException) {
            // Catches the race window: two concurrent creates pass the applicative pre-check,
            // both reach `save`, and a DB unique constraint rejects one of them.
            // We only translate to 409 when the violation is identifiably one of our uniqueness
            // constraints — any other integrity error (future constraints, NOT NULL breaches,
            // edge-mismatch, …) is rethrown so it surfaces as a 500 with an honest stack trace
            // rather than a misleading "already exists" message.
            //
            // Do NOT chain `e` to the logger: the Neo4j driver's `DataIntegrityViolationException`
            // message can echo property values (incl. the offending row's `parameters` JSON which
            // may contain credentials, cf. NFR-SEC-4). The exception is preserved as the cause of
            // the rethrown `ResponseStatusException` for stack-trace continuity at the framework
            // boundary.
            when {
                isSingletonKeyConflict(e) -> {
                    logger.warn {
                        "[IntegrationConfigService] singletonKey unique-constraint violation on save " +
                            "(namespaceId=${entity.namespaceId}, integrationType='${entity.integrationType}')"
                    }
                    throw ResponseStatusException(HttpStatus.CONFLICT, singletonConflictMessage(entity), e)
                }

                isTripleKeyConflict(e) -> {
                    logger.warn {
                        "[IntegrationConfigService] tripleKey unique-constraint violation on save " +
                            "(namespaceId=${entity.namespaceId}, userId=${entity.userId}, name='${entity.name}')"
                    }
                    throw ResponseStatusException(HttpStatus.CONFLICT, conflictMessage(entity), e)
                }

                else -> throw e
            }
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

    /**
     * Same inspection as [isTripleKeyConflict], for the one-per-namespace constraint. Checked
     * first because a row can, in principle, violate both.
     */
    private fun isSingletonKeyConflict(e: DataIntegrityViolationException): Boolean {
        val haystack =
            generateSequence<Throwable>(e) { it.cause }
                .mapNotNull { it.message }
                .joinToString(separator = " | ")
        return SINGLETON_KEY_CONSTRAINT_NAME in haystack || SINGLETON_KEY_PROPERTY in haystack
    }

    private fun conflictMessage(entity: IntegrationConfig): String =
        "An integration config named '${entity.name}' already exists for this scope " +
            "(namespaceId=${entity.namespaceId}, userId=${entity.userId})"

    private fun singletonConflictMessage(entity: IntegrationConfig): String =
        "Namespace ${entity.namespaceId} already has an active '${entity.integrationType}' configuration. " +
            "Only one is allowed per namespace."

    companion object : KLogging() {
        private const val TRIPLE_KEY_CONSTRAINT_NAME = "integration_config_triple_key_unique"
        private const val TRIPLE_KEY_PROPERTY = "tripleKey"
        private const val SINGLETON_KEY_CONSTRAINT_NAME = "integration_config_singleton_key_unique"
        private const val SINGLETON_KEY_PROPERTY = "singletonKey"

        /**
         * Comparator defining the 4-tier overlay precedence (lowest → highest priority).
         *
         * | Scope            | namespaceId | userId   | rank |
         * |------------------|-------------|----------|------|
         * | Platform         | null        | null     | 0    |
         * | User-global      | null        | non-null | 1    |
         * | Namespace-shared | non-null    | null     | 2    |
         * | User × namespace | non-null    | non-null | 3    |
         *
         * Namespace-shared (rank 2) intentionally overrides user-global (rank 1):
         * namespace admins can enforce a config that supersedes user preferences.
         * User × namespace (rank 3) lets the user restore a personal override
         * scoped to that specific namespace.
         */
        val LAYER_COMPARATOR: Comparator<IntegrationConfig> =
            Comparator { a, b ->
                val rankA = layerRank(a)
                val rankB = layerRank(b)
                rankA.compareTo(rankB)
            }

        private fun layerRank(config: IntegrationConfig): Int {
            val nsRank = if (config.namespaceId == null) 0 else 2
            val userRank = if (config.userId == null) 0 else 1
            return nsRank + userRank
        }
    }
}
