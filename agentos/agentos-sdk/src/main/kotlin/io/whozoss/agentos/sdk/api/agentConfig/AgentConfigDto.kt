package io.whozoss.agentos.sdk.api.agentConfig

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import java.time.Instant
import java.util.UUID

/**
 * HTTP DTO for AgentConfig entities — used as both request body and response body on
 * the `/api/agent-configs` endpoints.
 *
 * [namespaceId] and [name] are required on create. All other fields are optional.
 *
 * [integrations] maps integration type names to an optional list of allowed tool names.
 * A null list means all tools from that integration are allowed.
 *
 * [externalMetadata] is an opaque map that AgentOS persists as-is without interpreting
 * its content. Used by external consumers (e.g. Copilot) to store application-specific
 * metadata alongside the agent configuration.
 *
 * [enabled] controls whether the agent is published and visible to end-users.
 * Null on input is treated as false (unpublished) by the service.
 */
@Schema(name = "AgentConfig")
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AgentConfigDto(
    val id: UUID? = null,
    /**
     * The namespace this agent belongs to. Null for platform-level agents.
     * Platform agents are visible across all namespaces and require super-admin to manage.
     */
    val namespaceId: UUID? = null,
    @field:NotBlank(message = "name must not be blank")
    val name: String,
    val description: String? = null,
    val instructions: String? = null,
    val modelName: String? = null,
    val integrations: Map<String, List<String>?>? = null,
    /**
     * Execution mode for this agent. Replaces the legacy [advancedExecution] boolean.
     * On input: when both are present, [executionMode] takes precedence; when null,
     * [advancedExecution] is used as a fallback for backward compatibility.
     * On output: always the resolved mode.
     */
    @field:Schema(
        description =
            "Execution mode: SIMPLE, ADVANCED or LOOP (LOOP is experimental and may change or be removed " +
                "without notice). Takes precedence over advancedExecution on input; always resolved on output.",
    )
    val executionMode: ExecutionMode? = null,
    /**
     * @deprecated Use [executionMode] instead. Kept for backward compatibility with existing configs.
     * On input, when [executionMode] is null: `true` → ADVANCED, `false`/null → SIMPLE.
     * On output, derived from the resolved [executionMode]: `true` when ADVANCED, omitted otherwise.
     */
    @Deprecated("Use executionMode instead")
    val advancedExecution: Boolean? = null,
    val externalMetadata: Map<String, Any?>? = null,
    val createdBy: String? = null,
    val createdOn: Instant? = null,
    val updatedBy: String? = null,
    val updatedOn: Instant? = null,
    val enabled: Boolean? = null,
    @ArraySchema(
        schema =
            Schema(
                minLength = 1,
                implementation = String::class,
                description =
                    "Glob patterns controlling which agents this agent may delegate to. " +
                        "When null or empty, no delegation capability is provided. " +
                        "'*' matches any sequence of characters (anchored, case-insensitive). " +
                        "Examples: ['*'] allows all agents, ['*Fixer'] matches BugFixer/StoryFixer, " +
                        "['Fixer*'] matches FixerHelper/FixerV2.",
            ),
    )
    val subAgents: List<String>? = null,
    @field:Positive
    @field:Schema(description = "Seconds allowed for each outgoing delegation, including nested work. Null or omitted inherits the server default; on PUT this clears an existing override.", minimum = "1", nullable = true)
    val delegationTimeoutSeconds: Int? = null,
    @ArraySchema(
        schema =
            Schema(
                minLength = 1,
                implementation = String::class,
                description =
                    "Selectors controlling which skills are advertised to this agent. " +
                        "Skills live in a flat layout: skills/{name}/SKILL.md. " +
                        "Null or empty means no skills. Use ['*'] for all skills, or list exact skill names " +
                        "(matched against the SKILL.md frontmatter 'name', case-insensitive). " +
                        "Folder/glob patterns (e.g. 'core/**') are NOT supported and will simply never match.",
            ),
    )
    val skillSelectors: List<String>? = null,
    /**
     * Default payload for LOOP-mode agents, stored as a JSON object.
     *
     * When provided, it is used as the default [io.whozoss.agentos.workflow.AgentLoopPayload]
     * when the triggering message carries no parseable payload. A payload present in the
     * first user message always takes precedence (override).
     *
     * Only meaningful when [executionMode] is LOOP; ignored for SIMPLE and ADVANCED agents.
     */
    @field:Schema(
        description =
            "Default AgentLoopPayload for LOOP-mode agents. Used when the triggering message carries no " +
                "parseable payload. A payload in the first user message always takes precedence. " +
                "Ignored for SIMPLE and ADVANCED agents.",
        type = "object",
        nullable = true,
    )
    val loopConfig: JsonNode? = null,
)
