package io.whozoss.factory.persistence

/**
 * Skeleton abstraction for tenant-scoped repositories.
 *
 * Implementations must constrain every statement to the supplied [TenantScope];
 * an operation without a scope must fail closed rather than fall back to an
 * unscoped query. No concrete adapter is provided by the socle — domain
 * repositories are introduced by later workstreams.
 */
interface ScopedRepository<T, ID> {

    fun findById(scope: TenantScope, id: ID): T?

    fun deleteById(scope: TenantScope, id: ID): Boolean
}
