package io.whozoss.agentos.authSetting

import io.whozoss.agentos.entity.EntityRepository
import io.whozoss.agentos.sdk.authSetting.AuthSetting
import java.util.UUID

/**
 * Repository for [AuthSetting] persistence.
 *
 * Because [AuthSetting] can be scoped to a namespace, a user, or both, there is no
 * single "parent" key. [findByParent] from [EntityRepository] is therefore not the
 * primary listing mechanism here — use [findByNamespaceId] or [findByUserId] instead.
 *
 * The [ParentIdentifier] type is [UUID] to satisfy the interface; [findByParent] is
 * implemented as [findByNamespaceId] by convention (namespace is the primary scope).
 */
interface AuthSettingRepository : EntityRepository<AuthSetting, UUID> {
    /**
     * Find all non-removed settings scoped to the given namespace,
     * regardless of [AuthSetting.userId].
     */
    fun findByNamespaceId(namespaceId: UUID): List<AuthSetting>

    /**
     * Find all non-removed settings scoped to the given user,
     * regardless of [AuthSetting.namespaceId].
     */
    fun findByUserId(userId: UUID): List<AuthSetting>

    /**
     * Find a single non-removed setting matching the (namespaceId, userId, name) triple.
     * NULL parameters match rows where the corresponding column is NULL.
     */
    fun findByTriple(
        namespaceId: UUID?,
        userId: UUID?,
        name: String,
    ): AuthSetting?

    /**
     * Find all non-removed platform-level settings (namespaceId IS NULL AND userId IS NULL).
     */
    fun findPlatformLevel(): List<AuthSetting>

    /**
     * Fetch all non-removed settings visible for a given (namespaceId, userId) execution
     * context in a single query — all four layers: platform, namespace-shared, user-global,
     * user\u00d7namespace.
     */
    fun findAllForScope(
        namespaceId: UUID,
        userId: UUID,
    ): List<AuthSetting>
}
