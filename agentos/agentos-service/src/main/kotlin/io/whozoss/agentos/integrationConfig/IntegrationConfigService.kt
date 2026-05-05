package io.whozoss.agentos.integrationConfig

import io.whozoss.agentos.entity.EntityService
import java.util.UUID

/**
 * Service for managing [IntegrationConfig] entities.
 *
 * Extends [EntityService] with integration-specific operations:
 * - [create] and [update] are kept separate (inherited from [EntityService]) because
 *   each will carry distinct business logic as the feature matures (e.g. validation
 *   against the integration type schema, credential encryption, audit events).
 * - [findByNamespaceAndUserAndName]: triple-mode point lookup by the natural key
 *   (namespaceId, userId, name) — NULL values match rows with NULL on the same column.
 * - [findByNamespaceAndName]: legacy two-key lookup that always assumes `userId = null`,
 *   preserved for Epic 4 callers and tests.
 */
interface IntegrationConfigService : EntityService<IntegrationConfig, UUID> {
    /**
     * Find a single [IntegrationConfig] matching the (namespaceId, userId, name) triple.
     *
     * @return The config if found and not removed, null otherwise.
     */
    fun findByNamespaceAndUserAndName(
        namespaceId: UUID?,
        userId: UUID?,
        name: String,
    ): IntegrationConfig?

    /**
     * Find a single namespace-scoped [IntegrationConfig] by its (namespaceId, name) pair.
     * Equivalent to `findByNamespaceAndUserAndName(namespaceId, null, name)`.
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
     * and user × namespace (`namespaceId = ?`). Used by the user-scoped CRUD listing
     * ([UserIntegrationConfigController.list]).
     */
    fun findByUserId(userId: UUID): List<IntegrationConfig>

    /**
     * Find all non-removed [IntegrationConfig] scoped to the given namespace AND with
     * [IntegrationConfig.userId] = null (namespace-shared layer of the 3-tier reconciliation).
     *
     * Used by [io.whozoss.agentos.tool.ToolRegistryService.resolveToolsForRun] (story 6.4)
     * to enumerate the names to reconcile. Kept as a separate method from [findByParent]
     * for clarity at the call site — semantically equivalent to [findByParent] after T9
     * changes [findByParent] to filter `userId IS NULL`.
     */
    fun findByNamespaceShared(namespaceId: UUID): List<IntegrationConfig>
}
