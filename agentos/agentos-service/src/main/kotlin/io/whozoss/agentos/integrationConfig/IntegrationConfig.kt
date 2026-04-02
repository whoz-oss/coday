package io.whozoss.agentos.integrationConfig

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * Model representing an integration configuration scoped to a namespace.
 *
 * An IntegrationConfig binds a named integration instance (e.g. "JIRA_PROD", "MY_CALENDAR")
 * to a set of typed parameters. The [integrationType] determines which ToolFactory will be used
 * to instantiate tools from this configuration.
 *
 * Multiple instances of the same type can coexist within a namespace:
 *   - JIRA_PROD (type=JIRA, parameters={apiUrl: ..., apiKey: ...})
 *   - JIRA_STAGING (type=JIRA, parameters={apiUrl: ..., apiKey: ...})
 *
 * Uniqueness constraint: (namespaceId, name) must be unique within the repository.
 *
 * Implements Entity for standard CRUD operations.
 * Parent: Namespace (via [namespaceId]).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class IntegrationConfig(
    override val metadata: EntityMetadata = EntityMetadata(),
    val namespaceId: UUID,
    val name: String,
    val integrationType: String,
    val parameters: JsonNode? = null,
) : Entity
