package io.whozoss.agentos.userGroup

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

@JsonIgnoreProperties(ignoreUnknown = true)
data class UserGroup(
    override val metadata: EntityMetadata = EntityMetadata(),
    val namespaceId: UUID,
    val name: String,
) : Entity
