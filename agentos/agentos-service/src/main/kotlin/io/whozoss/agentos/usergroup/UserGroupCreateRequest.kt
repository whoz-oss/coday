package io.whozoss.agentos.usergroup

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.util.UUID

data class UserGroupCreateRequest(
    @NotNull(message = "namespaceId must not be null")
    val namespaceId: UUID,
    @NotBlank(message = "name must not be blank")
    @Size(max = 254, message = "name must not exceed 254 characters")
    val name: String,
    val userIds: Set<@NotNull UUID> = emptySet(),
    val userExternalIds: Set<
        @NotBlank
        @Size(max = 254)
        String,
    > = emptySet(),
    val agentIds: Set<@NotNull UUID> = emptySet(),
)
