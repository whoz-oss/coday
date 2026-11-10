package io.whozoss.agentos.sdk.api.workflow

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import java.time.Instant
import java.util.UUID

/**
 * One step of a [WorkflowDto]: a tool call, executed in order.
 *
 * [params] is the tool input. A string value that is exactly `{{run.<name>}}` is replaced, for each
 * run, by a value of the scheduled run: `agentConfigId`, `agentName`, `promptId`, `title`,
 * `scheduledPromptId` or `runId`. [from] names an earlier step whose structured output is passed
 * to this one as `previousStepOutput`.
 */
@Schema(name = "WorkflowStep")
@JsonInclude(JsonInclude.Include.NON_NULL)
data class WorkflowStepDto(
    @field:NotBlank(message = "step id must not be blank")
    val id: String,
    @field:NotBlank(message = "step tool must not be blank")
    val tool: String,
    @field:Schema(description = "Tool input; string values may be run bindings such as {{run.agentConfigId}}.", type = "object", nullable = true)
    val params: JsonNode? = null,
    @field:Schema(description = "Id of an earlier step whose output feeds this one.", nullable = true)
    val from: String? = null,
)

/**
 * HTTP DTO for a workflow: a strictly linear sequence of steps run by the scheduler for each
 * occurrence of a scheduled prompt.
 *
 * Scope on POST: `namespaceId` null is a platform workflow (Super Admin only), otherwise a
 * namespace workflow (WRITE on the namespace). `namespaceId` is immutable on PUT.
 */
@Schema(name = "Workflow")
@JsonInclude(JsonInclude.Include.NON_NULL)
data class WorkflowDto(
    val id: UUID? = null,
    @field:Schema(types = ["string", "null"], format = "uuid")
    val namespaceId: UUID? = null,
    @field:NotBlank(message = "name must not be blank")
    val name: String,
    val description: String? = null,
    val version: Int = 1,
    @field:NotEmpty(message = "steps must not be empty")
    @field:Valid
    val steps: List<WorkflowStepDto>,
    val createdBy: String? = null,
    val createdOn: Instant? = null,
    val updatedBy: String? = null,
    val updatedOn: Instant? = null,
)
