package io.whozoss.factory.persistence

/**
 * The composite tenant identity every Factory aggregate is scoped by.
 *
 * Every read/write must be scoped by `(organizationId, workstreamId)` — a
 * repository that cannot prove its scope must not touch the database.
 */
data class TenantScope(
    val organizationId: String,
    val workstreamId: String,
)
