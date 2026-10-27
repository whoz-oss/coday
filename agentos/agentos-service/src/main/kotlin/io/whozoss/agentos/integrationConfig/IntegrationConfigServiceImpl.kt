package io.whozoss.agentos.integrationConfig

import io.whozoss.agentos.entity.validateAndResolveScope
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.Caller
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.ScopedAccessPolicy
import io.whozoss.agentos.sdk.entity.EntityMetadata
import mu.KLogging
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Default implementation of [IntegrationConfigService].
 * Delegates persistence to [IntegrationConfigRepository].
 *
 * Four scope modes are supported: platform `(null, null)`, namespace-shared `(ns, null)`,
 * user-global `(null, user)`, and user×namespace `(ns, user)`. Authorization is enforced
 * by this service's `*ForCaller` methods ([getByIdForCaller], [findByIdsForCaller],
 * [findFilteredForCaller], [createForCaller], [updateForCaller], [deleteForCaller]).
 * The inherited [EntityService] methods are unchecked internal composition and must not
 * be called directly from controllers or agent tools.
 *
 * Uniqueness on the (namespaceId, userId, name) triple is enforced on [create] (409 on
 * conflict) and on [update] when a rename would collide with another row in the same scope.
 */
@Service
class IntegrationConfigServiceImpl(
    override val repository: IntegrationConfigRepository,
    private val mergeStrategy: IntegrationConfigMergeStrategy,
    /**
     * Optional list of [IntegrationConfigPolicy] instances injected by Spring.
     * Defaults to an empty list so that test instances can be constructed without
     * a full application context.
     */
    private val policies: List<IntegrationConfigPolicy> = emptyList(),
    private val integrationsProperties: IntegrationsProperties = IntegrationsProperties(),
    private val namespaceService: NamespaceService,
    private val scopePolicy: IntegrationConfigScopePolicy,
    private val scopedAccessPolicy: ScopedAccessPolicy,
) : IntegrationConfigService {
    override fun create(entity: IntegrationConfig): IntegrationConfig = withSavePolicies(entity) {
        findByTriple(entity.namespaceId, entity.userId, entity.name)?.let {
            throw ConflictException(
                "IntegrationConfig with name '${entity.name}' already exists in this scope",
            )
        }
        repository.save(entity)
    }

    override fun update(entity: IntegrationConfig): IntegrationConfig = withSavePolicies(entity) {
        val existing =
            findById(entity.metadata.id)
                ?: throw ConflictException("IntegrationConfig not found: ${entity.metadata.id}")
        if (entity.name != existing.name) {
            findByTriple(entity.namespaceId, entity.userId, entity.name)?.let {
                throw ConflictException(
                    "IntegrationConfig with name '${entity.name}' already exists in this scope",
                )
            }
        }
        repository.save(entity)
    }

    override fun delete(id: UUID): Boolean = repository.delete(id)

    override fun findById(id: UUID, withRemoved: Boolean): IntegrationConfig? = repository.findById(id, withRemoved)

    override fun findByIds(ids: Set<UUID>, withRemoved: Boolean): List<IntegrationConfig> =
        repository.findByIds(ids, withRemoved)

    override fun findByTriple(
        namespaceId: UUID?,
        userId: UUID?,
        name: String,
    ): IntegrationConfig? = repository.findByTriple(namespaceId, userId, name)

    override fun findEffective(
        namespaceId: UUID,
        userId: UUID,
        integrationType: String,
    ): IntegrationConfig? = mergeStrategy.findEffective(namespaceId, userId, integrationType)

    override fun findPlatform(): List<IntegrationConfig> = repository.findPlatform()

    override fun findByUserId(userId: UUID): List<IntegrationConfig> = repository.findByUserId(userId)

    override fun findByNamespaceShared(namespaceId: UUID): List<IntegrationConfig> =
        repository.findByNamespaceShared(namespaceId)

    override fun findByNamespaceAndName(
        namespaceId: UUID,
        name: String,
    ): IntegrationConfig? = repository.findByNamespaceAndName(namespaceId, name)

    override fun deleteByParent(
        parentId: UUID,
        parentType: String,
    ): Int = repository.deleteByParent(parentId, parentType)

    override fun findByParent(
        parentId: UUID,
        parentType: String,
    ): List<IntegrationConfig> = repository.findByParent(parentId, parentType)

    override fun findActiveNamespaceSingleton(
        namespaceId: UUID,
        integrationType: String,
    ): IntegrationConfig? = repository.findActiveNamespaceSingleton(namespaceId, integrationType)

    // -------------------------------------------------------------------------
    // Unchecked composition surface
    // -------------------------------------------------------------------------

    override fun unsecuredCreate(entity: IntegrationConfig): IntegrationConfig = create(entity)

    override fun unsecuredUpdate(entity: IntegrationConfig): IntegrationConfig = update(entity)

    override fun unsecuredFindById(id: UUID, withRemoved: Boolean): IntegrationConfig? = findById(id, withRemoved)

    // --- Authorized surface ---

    override fun getByIdForCaller(caller: Caller, id: UUID, action: Action): IntegrationConfig {
        val config = findById(id, withRemoved = true)
            ?: throw ResourceNotFoundException("IntegrationConfig not found: $id")
        scopedAccessPolicy.requireAccess(caller, EntityType.INTEGRATION_CONFIG, config, action)
        return config
    }

    override fun findByIdsForCaller(caller: Caller, ids: List<UUID>, withRemoved: Boolean): List<IntegrationConfig> {
        if (ids.size > MAX_BATCH_SIZE) throw BadRequestException("Batch size ${ids.size} exceeds maximum of $MAX_BATCH_SIZE")
        if (ids.isEmpty()) return emptyList()

        val rows = findByIds(ids.toSet(), withRemoved)
        val permittedIds = scopedAccessPolicy.filterVisible(caller, EntityType.INTEGRATION_CONFIG, rows, Action.READ)
        val byId = rows.filter { it.id in permittedIds }.associateBy { it.metadata.id }
        return ids.mapNotNull { byId[it] }
    }

    override fun findFilteredForCaller(
        caller: Caller,
        namespaceId: UUID?,
        namespaceIsNone: Boolean,
        userRequested: Boolean,
    ): List<IntegrationConfig> =
        when {
            // Explicit platform scope, available to all authenticated users.
            namespaceIsNone && !userRequested -> findPlatform()

            // NS-shared layer of a specific namespace (no userId param) : check READ permission
            namespaceId != null && !userRequested -> {
                if (!scopedAccessPolicy.canReadNamespace(caller, namespaceId)) {
                    emptyList()
                } else {
                    findByNamespaceShared(namespaceId)
                }
            }

            // User-scoped rows (with optional namespace filter)
            userRequested -> {
                val nsFilter: (UUID?) -> Boolean =
                    when {
                        namespaceIsNone -> { nsId -> nsId == null }
                        namespaceId != null -> { nsId -> nsId == namespaceId }
                        else -> { _ -> true }
                    }
                findByUserId(caller.userId).filter { nsFilter(it.namespaceId) }
            }

            // No filter at all : surface the caller's own overlays
            else -> {
                findByUserId(caller.userId)
            }
        }

    override fun createForCaller(caller: Caller, entity: IntegrationConfig): IntegrationConfig {
        if (entity.namespaceId == null && entity.userId == null) {
            if (!caller.isAdmin) throw AccessDeniedException("Platform-level IntegrationConfig requires Super Admin")
            return create(entity.copy(metadata = EntityMetadata(id = UUID.randomUUID()), namespaceId = null, userId = null))
        }
        scopePolicy.requireScopeAllowed(userId = entity.userId, integrationType = entity.integrationType)
        val scope = validateAndResolveScope(
            entityLabel = "IntegrationConfig",
            caller = caller,
            requestedUserId = entity.userId,
            requestedNamespaceId = entity.namespaceId,
            namespaceService = namespaceService,
            scopedAccessPolicy = scopedAccessPolicy,
        )
        return create(entity.copy(metadata = EntityMetadata(id = UUID.randomUUID()), namespaceId = scope.namespaceId, userId = scope.userId))
    }

    override fun updateForCaller(caller: Caller, id: UUID, changes: IntegrationConfigUpdate): IntegrationConfig {
        val existing = findById(id) ?: throw ResourceNotFoundException("IntegrationConfig not found: $id")
        scopedAccessPolicy.requireAccess(caller, EntityType.INTEGRATION_CONFIG, existing, Action.WRITE)
        scopedAccessPolicy.requireAdminForPlatform(caller, "IntegrationConfig", existing)
        scopePolicy.requireScopeAllowed(userId = existing.userId, integrationType = existing.integrationType)
        // id, namespaceId, userId and integrationType are preserved from the persisted row —
        // this is the mass-assignment guard, now a business rule owned by the service.
        return update(
            existing.copy(
                name = changes.name,
                integrationType = existing.integrationType,
                description = changes.description,
                parameters = changes.parameters,
                authSettingName = changes.authSettingName,
                autoGrant = changes.autoGrant,
            )
        )
    }

    override fun deleteForCaller(caller: Caller, id: UUID) {
        val existing = findById(id) ?: throw ResourceNotFoundException("IntegrationConfig not found: $id")
        scopedAccessPolicy.requireAccess(caller, EntityType.INTEGRATION_CONFIG, existing, Action.DELETE)
        scopedAccessPolicy.requireAdminForPlatform(caller, "IntegrationConfig", existing)
        if (!delete(id)) throw ResourceNotFoundException("IntegrationConfig not found: $id")
    }

    /**
     * Reject create/update when another layer that would merge with this entity at reconciliation
     * time already carries the same `name` but a different `integrationType` (review IG-3).
     *
     * The merge strategy selects the most-specific layer for a given type, so having two layers
     * with the same name but different types would make it impossible to predict which type
     * is active at runtime.
     */
    private fun <T> withSavePolicies(entity: IntegrationConfig, block: () -> T): T {
        policies.forEach { it.beforeSave(entity) }
        return try {
            block().also { policies.forEach { policy -> policy.afterSave(entity) } }
        } catch (e: DataIntegrityViolationException) {
            val message = e.message ?: ""
            when {
                TRIPLE_KEY_CONSTRAINT_NAME in message ->
                    throw ConflictException(
                        "IntegrationConfig with name '${entity.name}' already exists in this scope",
                    )

                SINGLETON_KEY_CONSTRAINT_NAME in message ->
                    throw ConflictException(
                        singletonConflictMessage(entity.integrationType),
                    )

                else -> throw e
            }
        }
    }

    private fun singletonConflictMessage(integrationType: String) =
        "An active $integrationType configuration already exists for this namespace. " +
            "Only one is allowed per namespace."

    companion object : KLogging() {
        private const val MAX_BATCH_SIZE = 1000
        private const val TRIPLE_KEY_CONSTRAINT_NAME = "integration_config_triple_key_unique"
        private const val TRIPLE_KEY_PROPERTY = "tripleKey"
        private const val SINGLETON_KEY_CONSTRAINT_NAME = "integration_config_singleton_key_unique"
    }
}
