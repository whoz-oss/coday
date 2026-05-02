package io.whozoss.agentos.usergroup

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.util.UUID

data class UserGroupUpdateRequest(
    @NotNull(message = "namespaceId must not be null")
    val namespaceId: UUID,
    @NotBlank(message = "name must not be blank")
    @Size(max = 254, message = "name must not exceed 254 characters")
    val name: String,
    val addUserIds: Set<@NotNull UUID> = emptySet(),
    val addUserExternalIds: Set<
        @NotBlank
        @Size(max = 254)
        String,
    > = emptySet(),
    val removeUserIds: Set<@NotNull UUID> = emptySet(),
    val removeUserExternalIds: Set<
        @NotBlank
        @Size(max = 254)
        String,
    > = emptySet(),
    val agentIds: Set<@NotNull UUID> = emptySet(),
)
