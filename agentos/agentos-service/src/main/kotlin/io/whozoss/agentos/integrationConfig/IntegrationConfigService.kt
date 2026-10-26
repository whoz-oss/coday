package io.whozoss.agentos.integrationConfig

import com.fasterxml.jackson.databind.JsonNode
import io.whozoss.agentos.entity.EntityService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.reconciliation.ConfigLookup
import io.whozoss.agentos.security.declarative.OwnershipAware
import java.util.UUID

/**
 * The mutable fields of an [IntegrationConfig]. [IntegrationConfig.metadata],
 * [IntegrationConfig.namespaceId], [IntegrationConfig.userId] and
 * [IntegrationConfig.integrationType] are server-owned and never taken from a client payload.
 */
data class IntegrationConfigUpdate(
    val name: String,
    val description: String?,
    val parameters: JsonNode?,
    val authSettingName: String?,
    val autoGrant: Boolean,
)

/**
 * Service for managing [IntegrationConfig] entities.
 *
 * ## Two surfaces
 *
 * ### `*ForCaller` methods (authorized surface)
 * [getByIdForCaller], [findByIdsForCaller], [findFilteredForCaller], [createForCaller],
 * [updateForCaller], and [deleteForCaller] enforce authorization and are the **only** surface
 * a controller, an agent tool, or any other user-facing entry point may call.
 *
 * ### Inherited [EntityService] methods (internal composition surface)
 * [create], [update], [delete], [findByIds], [findByTriple], [findEffective], [findPlatform],
 * [findByUserId], [findByNamespaceShared], [findActiveNamespaceSingleton],
 * [findByNamespaceAndName], [deleteByParent], [findByParent] are **unchecked** internal
 * composition, for service-to-service and startup-seeding use only. They must not be called
 * directly from controllers or agent tools.
 *
 * ## Scope modes
 * Four scope modes are supported: platform `(null, null)`, namespace-shared `(ns, null)`,
 * user-global `(null, user)`, and user×namespace `(ns, user)`.
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
     * Explicit override of [ConfigLookup.findByTriple] with a precise return type.
     *
     * Redundant to the reader, load-bearing to the JVM. [ConfigLookup]'s type parameter erases to
     * its upper bound ([io.whozoss.agentos.sdk.entity.Entity]), so without this redeclaration the
     * only signature this interface carries in bytecode returns `Entity`, and every Kotlin call
     * site compiles to that call followed by a `CHECKCAST` to [IntegrationConfig].
     *
     * That is invisible until something answers the erased signature on its own terms. A relaxed
     * MockK mock does exactly that: it derives its auto-stub from the declared return type, hands
     * back a child mock of `Entity`, and the caller's `CHECKCAST` fails with a
     * `ClassCastException` naming a synthetic `Entity$SubclassNN` — a message that points at
     * neither the mock nor the erasure.
     *
     * It surfaced through [io.whozoss.agentos.queryUser.QueryUserConfigSeeder], an
     * [org.springframework.boot.ApplicationRunner] that calls this method during startup of every
     * Spring test context: any spec mocking this service failed at context load, far from the
     * spec that mocked it. Declaring the override here is the fix, not a workaround — the precise
     * type is what the contract always meant.
     */
    override fun findByTriple(
        namespaceId: UUID?,
        userId: UUID?,
        name: String,
    ): IntegrationConfig?

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
     * Used by [IntegrationConfigController.list] with `namespaceId=none` and no `userId` (authenticated users).
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
     * The active namespace-shared row of a singleton [integrationType], or null. Unlike [findEffective]
     * it merges no layer and reads no YAML: see [IntegrationTypeConstraints].
     */
    fun findActiveNamespaceSingleton(
        namespaceId: UUID,
        integrationType: String,
    ): IntegrationConfig?

    // -------------------------------------------------------------------------
    // Authorized surface — *ForCaller methods
    // -------------------------------------------------------------------------

    /**
     * Read one config, enforcing authorization.
     *
     * Loads the entity including soft-deleted rows (same as the old `EntityCrudDelegate.getById`
     * which used `withRemoved = true`). Throws [io.whozoss.agentos.exception.ResourceNotFoundException]
     * when not found, and [org.springframework.security.access.AccessDeniedException] when the
     * caller lacks the required [action].
     *
     * @param callerId the authenticated user's UUID
     * @param id the entity to load
     * @param action permission required — use [Action.READ] for a normal read, [Action.WRITE] for export
     */
    fun getByIdForCaller(callerId: UUID, id: UUID, action: Action = Action.READ): IntegrationConfig

    /**
     * Batch read, permission-filtered. Never throws on denial — inaccessible ids are silently
     * omitted. Input order and duplicates are preserved. Throws
     * [io.whozoss.agentos.exception.BadRequestException] when `ids.size > 1000`.
     *
     * @param callerId the authenticated user's UUID
     * @param ids the batch of entity ids to resolve
     * @param withRemoved when true, includes soft-deleted entities
     */
    fun findByIdsForCaller(callerId: UUID, ids: List<UUID>, withRemoved: Boolean): List<IntegrationConfig>

    /**
     * Scope-aware listing, permission-enforced.
     *
     * Replaces the old `findFiltered(namespaceId, namespaceIsNone, callerId, userRequested,
     * canReadNamespace)` — the callback is gone; the service checks the namespace READ permission
     * itself using [io.whozoss.agentos.permissions.PermissionService].
     *
     * Returns an empty list (never throws) when the caller lacks READ on the requested namespace.
     *
     * @param callerId the authenticated user's UUID
     * @param namespaceId resolved namespace UUID (null when absent or the `none` sentinel)
     * @param namespaceIsNone true when the raw query parameter was the `none` sentinel
     * @param userRequested true when the caller explicitly passed `userId=me`
     */
    fun findFilteredForCaller(
        callerId: UUID,
        namespaceId: UUID?,
        namespaceIsNone: Boolean,
        userRequested: Boolean,
    ): List<IntegrationConfig>

    /**
     * Create an [IntegrationConfig], enforcing authorization and scope rules.
     *
     * Enforces (in this order):
     * 1. Platform-scope guard (both null) — Super Admin only.
     * 2. User-scope type denial via [IntegrationConfigScopePolicy].
     * 3. Scope validation and resolution via [io.whozoss.agentos.entity.validateAndResolveScope].
     * 4. Persistence via the unchecked [create].
     *
     * @param callerId the authenticated user's UUID
     * @param entity the entity to create (id will be replaced with a freshly generated UUID)
     */
    fun createForCaller(callerId: UUID, entity: IntegrationConfig): IntegrationConfig

    /**
     * Update an [IntegrationConfig], enforcing authorization.
     *
     * Enforces:
     * 1. Existence check — [io.whozoss.agentos.exception.ResourceNotFoundException] when not found.
     * 2. Access check — [org.springframework.security.access.AccessDeniedException] when the caller
     *    lacks WRITE (membership or ownership).
     * 3. Platform-scope guard — Super Admin only for platform rows.
     * 4. User-scope type denial via [IntegrationConfigScopePolicy] (on the persisted type).
     * 5. Applies [changes] while preserving server-owned fields (id, namespaceId, userId, integrationType).
     *
     * @param callerId the authenticated user's UUID
     * @param id the entity to update
     * @param changes the mutable fields to apply
     */
    fun updateForCaller(callerId: UUID, id: UUID, changes: IntegrationConfigUpdate): IntegrationConfig

    /**
     * Delete an [IntegrationConfig], enforcing authorization.
     *
     * Enforces:
     * 1. Existence check — [io.whozoss.agentos.exception.ResourceNotFoundException] when not found.
     * 2. Access check — [org.springframework.security.access.AccessDeniedException] when the caller
     *    lacks DELETE (membership or ownership).
     * 3. Platform-scope guard — Super Admin only for platform rows.
     *
     * @param callerId the authenticated user's UUID
     * @param id the entity to delete
     */
    fun deleteForCaller(callerId: UUID, id: UUID)
}
