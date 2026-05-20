package io.whozoss.agentos.integrationConfig

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * Persistent integration configuration.
 *
 * Scoped to a namespace, a user, or both — at least one of [namespaceId] / [userId]
 * must be non-null. This constraint is enforced by [IntegrationConfigServiceImpl.create]
 * (and re-checked on update for defence-in-depth).
 *
 * Triple-mode (namespaceId, userId):
 * - (X, null)  = namespace-only — shared by all members of namespace X (default Epic 4 behaviour)
 * - (null, Y)  = user-only — personal config of user Y, applies cross-namespace (user-global)
 * - (X, Y)     = both — personal config of user Y limited to namespace X (user × namespace)
 *
 * The [integrationType] determines which [io.whozoss.agentos.sdk.tool.ToolPlugin] will
 * be used to instantiate tools from this configuration. Multiple instances of the same
 * type can coexist within a scope (e.g. JIRA_PROD and JIRA_STAGING).
 *
 * Uniqueness constraint: (namespaceId, userId, name) must be unique — enforced by
 * [IntegrationConfigServiceImpl]. NULL values participate in the constraint, so
 * (ns=A, user=NULL, name="JIRA"), (ns=NULL, user=alice, name="JIRA") and
 * (ns=A, user=alice, name="JIRA") may coexist as three independent rows.
 *
 * TODO: [parameters] may contain sensitive credentials (API keys, tokens). Currently stored and
 *   returned in clear text. A future iteration should encrypt at-rest and mask in API responses.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class IntegrationConfig(
    override val metadata: EntityMetadata = EntityMetadata(),
    val namespaceId: UUID? = null,
    val userId: UUID? = null,
    val name: String,
    val integrationType: String,
    val description: String? = null,
    val parameters: JsonNode? = null,
) : Entity
