package io.whozoss.agentos.usergroup

import jakarta.validation.constraints.NotNull
import java.util.UUID

data class UserGroupListRequest(
    @field:NotNull(message = "namespaceId must not be null")
    val namespaceId: UUID,
)
