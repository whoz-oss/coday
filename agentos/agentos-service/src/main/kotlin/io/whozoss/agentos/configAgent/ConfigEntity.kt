package io.whozoss.agentos.configAgent

import com.fasterxml.jackson.databind.JsonNode
import java.util.UUID

/**
 * A configuration entity as the config agent sees it: identity, scope, and its REST DTO as JSON.
 * [dto] is unmasked; callers mask it with [ConfigSecretMasker] before it leaves the server.
 */
data class ConfigEntity(
    val kind: ConfigKind,
    val id: UUID,
    val name: String,
    val namespaceId: UUID?,
    val userId: UUID?,
    val dto: JsonNode,
    val enabled: Boolean? = null,
    val integrationType: String? = null,
    val fromFilesystem: Boolean = false,
)
