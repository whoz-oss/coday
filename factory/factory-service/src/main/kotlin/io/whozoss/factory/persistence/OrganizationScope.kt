package io.whozoss.factory.persistence

/** Authenticated organization boundary, without an invented workstream. */
data class OrganizationScope(val organizationId: String) {
    init {
        require(organizationId.isNotBlank()) { "organizationId must not be blank" }
    }
}
