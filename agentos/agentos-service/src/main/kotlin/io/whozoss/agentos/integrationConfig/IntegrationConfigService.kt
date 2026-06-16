package io.whozoss.agentos.integrationConfig

import io.whozoss.agentos.entity.EntityService
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.reconciliation.ConfigLookup
import io.whozoss.agentos.security.declarative.OwnershipAware
import java.util.UUID

/**
 * Service for managing [IntegrationConfig] entities.
 *
 * Extends [EntityService] with integration-specific operations:
 * - [create] and [update] are kept separate (inherited from [EntityService]) because
 *   each will carry distinct business logic as the feature matures (e.g. validation
 *   against the integration type schema, credential encryption, audit events).
 * - [findByTriple] (from [ConfigLookup]): point lookup by the natural key
 *   (namespaceId, userId, name) — NULL values match rows with NULL on the same column.
 * - [findByNamespaceAndName]: legacy two-key lookup that always assumes `userId = null`,
 *   preserved for Epic 4 callers and tests.
 */
interface IntegrationConfigService :
    EntityService<IntegrationConfig, UUID>,
    ConfigLookup<IntegrationConfig>,
    OwnershipAware {
    override val ownershipEntityType: EntityType get() = EntityType.INTEGRATION_CONFIG

    override fun resolveOwner(targetId: UUID): UUID? = findById(targetId)?.userId

    /**
     * Find a single namespace-scoped [IntegrationConfig] by its (namespaceId, name) pair.
     * Equivalent to `findByTriple(namespaceId, null, name)`.
     *
     * @return The config if found and not removed, null otherwise.
     */
    fun findByNamespaceAndName(
        namespaceId: UUID,
        name: String,
    ): IntegrationConfig?

    /**
     * Find all non-removed configs scoped to the given user, regardless of [IntegrationConfig.namespaceId].
     *
     * Returns rows where `userId == :userId` — both user-global (`namespaceId IS NULL`)
     * and user × namespace (`namespaceId = ?`). Used by the user-scoped listing modes
     * of [IntegrationConfigController.list].
     */
    fun findByUserId(userId: UUID): List<IntegrationConfig>

    /**
     * Find all non-removed [IntegrationConfig] scoped to the given namespace AND with
     * [IntegrationConfig.userId] = null (namespace-shared layer of the 3-tier reconciliation).
     *
     * Used by [io.whozoss.agentos.tool.ToolResolverService.resolveToolsForRun] (story 6.4)
     * to enumerate the names to reconcile. Kept as a separate method from [findByParent]
     * for clarity at the call site — semantically equivalent to [findByParent] after T9
     * changes [findByParent] to filter `userId IS NULL`.
     */
    fun findByNamespaceShared(namespaceId: UUID): List<IntegrationConfig>

    /**
     * Find all non-removed platform-level [IntegrationConfig] entries
     * (`namespaceId IS NULL AND userId IS NULL`).
     *
     * Used by [IntegrationConfigController.list] when called with no query params (Super Admin only).
     */
    fun findPlatform(): List<IntegrationConfig>

    /**
     * Enumerate the effective [IntegrationConfig] entries visible for a given execution context
     * `(namespaceId, userId)`.
     *
     * Composes the four precedence layers from lowest to highest, keeping only the highest-
     * precedence entry per [IntegrationConfig.name]:
     *
     * | Layer              | Condition                        |
     * |--------------------|----------------------------------|
     * | Platform           | always included                  |
     * | Namespace-shared   | when `namespaceId != null`       |
     * | User-global        | when `userId != null`            |
     * | User × namespace   | when both are non-null           |
     *
     * This **is** a parameter-level merge across all applicable layers, ordered from lowest to
     * highest precedence. Each layer's parameters are deep-merged on top of the previous via
     * [io.whozoss.agentos.integrationConfig.IntegrationConfigMergeStrategy]. The result carries
     * the identity (`id`, `namespaceId`, `userId`) of the platform layer (lowest), which is
     * the stable provenance anchor for caching and logging. The merged config is a derived,
     * runtime-only view and is never persisted.
     *
     * Intended for callers that need the effective parameter set at agent-run time
     * (tool instantiation, system prompt).
     */
    fun findEffective(
        namespaceId: UUID?,
        userId: UUID?,
    ): List<IntegrationConfig>

    /**
     * Scope-aware filtered listing used by [IntegrationConfigController.list].
     *
     * Dispatches the query based on the resolved namespace/user filter combination:
     * - Specific namespace + no user request → namespace-shared (guarded by [canReadNamespace])
     * - User requested → user-scoped rows, optionally filtered by namespace
     * - No filters → caller's own overlays
     *
     * @param namespaceId resolved namespace UUID (null when absent or `none` sentinel)
     * @param namespaceIsNone true when the raw query parameter was the `none` sentinel
     * @param callerId the authenticated user's id (always provided)
     * @param userRequested true when the caller explicitly passed `userId=me`
     * @param canReadNamespace callback to check caller READ permission on the namespace
     */
    fun findFiltered(
        namespaceId: UUID?,
        namespaceIsNone: Boolean,
        callerId: UUID,
        userRequested: Boolean,
        canReadNamespace: (UUID) -> Boolean,
    ): List<IntegrationConfig>
}
