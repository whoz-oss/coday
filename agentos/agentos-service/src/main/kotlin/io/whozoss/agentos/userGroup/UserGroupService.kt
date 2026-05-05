package io.whozoss.agentos.userGroup

import io.whozoss.agentos.entity.EntityService
import java.util.UUID

interface UserGroupService : EntityService<UserGroup, UUID> {
    fun findByNamespaceExternalId(externalId: String): List<UserGroupSearchResult>
    fun createFromRequest(request: UserGroupCreateRequest): UserGroupSearchResult
}
