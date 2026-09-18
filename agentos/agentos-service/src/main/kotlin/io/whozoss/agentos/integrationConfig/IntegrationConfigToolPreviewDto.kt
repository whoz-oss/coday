package io.whozoss.agentos.integrationConfig

import io.swagger.v3.oas.annotations.media.Schema
import io.whozoss.agentos.sdk.tool.ConfirmationMode

/**
 * Response body of `POST /api/integration-configs/{id}/preview-tools`.
 *
 * UI-internal contract (not part of the SDK `IntegrationConfigApi`): the Angular client is
 * generated from the `@Schema` descriptions below. Tools expose their name, description and
 * input schema only; no credential or parameter value ever appears in the response.
 */
@Schema(
    name = "IntegrationConfigToolPreview",
    description =
        "Tools an IntegrationConfig yields for the calling user, resolved from the stored row as is " +
            "(the user-level overlay merge applied by an agent run is NOT applied) and without any " +
            "agent allowlist. Nothing is persisted.",
)
data class IntegrationConfigToolPreviewDto(
    @field:Schema(description = "Integration type of the previewed config (plugin key).", example = "MCP_HTTP")
    val integrationType: String,
    @field:Schema(description = "Name of the previewed config, also the prefix of every tool name.")
    val configName: String,
    @field:Schema(
        description =
            "The plugin's describeNamespace line for this config, or null when the plugin gives none, " +
                "fails or does not answer within the configured timeout " +
                "(agentos.integrations.preview-describe-namespace-timeout-ms, best effort).",
        nullable = true,
    )
    val namespaceDescription: String?,
    @field:Schema(description = "Tools the plugin built for this config; empty when `error` is set.")
    val tools: List<ToolPreviewDto>,
    @field:Schema(
        description =
            "Why the plugin could not build its tool set (`ExceptionClass: message`), or null when " +
                "`tools` is the result.",
        nullable = true,
    )
    val error: String?,
) {
    @Schema(name = "IntegrationConfigToolPreviewTool", description = "One tool as an agent would receive it.")
    data class ToolPreviewDto(
        @field:Schema(description = "Full tool name as exposed to the model, prefixed by the config name.")
        val name: String,
        @field:Schema(description = "Prompt-facing description of the tool.")
        val description: String,
        @field:Schema(description = "JSON Schema of the tool input, serialised as a string.")
        val inputSchema: String,
        @field:Schema(description = "Confirmation gate the tool reports for a call without arguments.")
        val confirmationMode: ConfirmationMode,
    )
}
