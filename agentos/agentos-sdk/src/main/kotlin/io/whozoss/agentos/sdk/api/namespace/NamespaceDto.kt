package io.whozoss.agentos.sdk.api.namespace

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import java.util.UUID

/**
 * HTTP DTO for Namespace entities — used as both request body and response body on
 * the `/api/namespaces` CRUD endpoints.
 *
 * @property id Server-assigned UUID. Null on create requests; always present in responses.
 * @property name Human-readable namespace name. Required on create.
 * @property description Optional description.
 * @property configPath Optional filesystem path to a directory containing base configuration
 *   for this namespace (agents, tools, etc.).
 * @property externalId Optional external identifier, e.g. a federation id from an external system.
 * @property defaultAgentName Logical name of the default agent for this namespace. Resolved at
 *   runtime against AgentConfig entries (case-insensitive). When null, messages without an
 *   @mention will produce an explicit error.
 */
@Schema(name = "Namespace")
@JsonIgnoreProperties(ignoreUnknown = true)
data class NamespaceDto(
    val id: UUID? = null,
    @field:NotBlank(message = "name must not be blank")
    val name: String,
    val description: String? = null,
    val configPath: String? = null,
    val externalId: String? = null,
    val defaultAgentName: String? = null,
)
