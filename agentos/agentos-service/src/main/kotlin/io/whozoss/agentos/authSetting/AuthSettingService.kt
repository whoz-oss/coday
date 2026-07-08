package io.whozoss.agentos.authSetting

import io.whozoss.agentos.entity.EntityService
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.reconciliation.ConfigLookup
import io.whozoss.agentos.sdk.authSetting.AuthSetting
import io.whozoss.agentos.security.declarative.OwnershipAware
import java.util.UUID

/**
 * Service for managing [AuthSetting] entities.
 *
 * Extends [EntityService] with scope-aware listing, and implements [ConfigLookup] so the
 * generic [io.whozoss.agentos.reconciliation.ConfigMergeService] can resolve overlays
 * without an intermediate wrapper bean.
 */
interface AuthSettingService : EntityService<AuthSetting, UUID>, ConfigLookup<AuthSetting>, OwnershipAware {
    override val ownershipEntityType: EntityType get() = EntityType.AUTH_SETTING
    override fun resolveOwner(targetId: UUID): UUID? = findById(targetId)?.userId

    /**
     * Find all [AuthSetting] scoped to the given namespace (userId IS NULL).
     */
    fun findByNamespaceId(namespaceId: UUID): List<AuthSetting>

    /**
     * Find all [AuthSetting] scoped to the given user.
     */
    fun findByUserId(userId: UUID): List<AuthSetting>

    /**
     * Find all non-removed platform-level [AuthSetting] (namespaceId IS NULL AND userId IS NULL).
     * Readable by any authenticated user; writable only by super-admins.
     */
    fun findPlatformLevel(): List<AuthSetting>

    /**
     * Resolve the effective [AuthSetting] for a given (namespaceId, userId, name) triple by
     * fetching all applicable layers in a single query and folding them from lowest to highest
     * precedence via [AuthSettingMergeStrategy].
     *
     * Precedence (lowest → highest): platform → namespace-shared → user-global → user×namespace.
     *
     * Throws [io.whozoss.agentos.exception.ConfigNotFoundException] when no layer contains
     * a setting with the given name.
     */
    fun resolveAuthSetting(
        namespaceId: UUID,
        userId: UUID,
        name: String,
    ): AuthSetting

    /**
     * Scope-aware filtered listing used by the AuthSettingController.
     *
     * Dispatches the query based on the resolved namespace/user filter combination:
     * - Specific namespace + no user request -> namespace-shared (READ permission checked internally)
     * - User requested -> user-scoped rows, optionally filtered by namespace
     * - No filters -> caller's own overlays
     * - Platform level (namespaceId=none, no userId) -> platform-level rows (open to all authenticated)
     *
     * @param namespaceId resolved namespace UUID (null when absent or `none` sentinel)
     * @param namespaceIsNone true when the raw query parameter was the `none` sentinel
     * @param callerId the authenticated user's id (always provided)
     * @param userRequested true when the caller explicitly passed `userId=me`
     */
    fun findFiltered(
        namespaceId: UUID?,
        namespaceIsNone: Boolean,
        callerId: UUID,
        userRequested: Boolean,
    ): List<AuthSetting>
}
