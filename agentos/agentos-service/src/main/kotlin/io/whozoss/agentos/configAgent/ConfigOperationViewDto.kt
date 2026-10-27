package io.whozoss.agentos.configAgent

import com.fasterxml.jackson.databind.JsonNode
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

data class ConfigOperationViewDto(
    val ref: String,
    val order: Int,
    val kind: ConfigKind,
    val action: ConfigAction,
    @field:Schema(description = "Name of the entity created or targeted.")
    val name: String,
    val targetId: UUID?,
    @field:Schema(description = "Scope of the entity: null namespaceId and userId mean platform.")
    val namespaceId: UUID?,
    val userId: UUID?,
    @field:Schema(description = "Name of the namespace the operation acts in when it is not the case's: one the plan creates or another one the user administers.")
    val namespaceName: String? = null,
    val affects: ConfigAffects,
    val deployTo: ConfigDeployTarget?,
    @field:Schema(description = "Name of the integration this CREATE copies; the server reads its fields at apply time, and value only overrides some of them.")
    val copyFrom: String? = null,
    @field:Schema(description = "CREATE only: the fields of the new entity, secrets excluded.")
    val value: JsonNode?,
    @field:Schema(description = "UPDATE only: each field with its current value (masked) and the proposed one.")
    val changes: List<ConfigFieldChangeDto>,
    val secretInputs: List<ConfigSecretInputDto>,
    val status: ConfigOperationStatus,
)
