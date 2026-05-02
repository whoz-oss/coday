package io.whozoss.agentos.usergroup

import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

data class UserGroup(
    override val metadata: EntityMetadata = EntityMetadata(),
    val namespaceId: UUID,
    val name: String,
) : Entity
