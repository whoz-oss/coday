package io.whozoss.agentos.sdk.api.workflow

import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import java.time.Instant
import java.util.UUID

/**
 * One step of a [WorkflowDto]: a tool, named as granted to the scheduler loop agent, bare
 * (`listAgentUsers`) or prefixed by its integration config (`USER_GROUP__listAgentUsers`).
 *
 * A step carries no parameters: they are supplied at run time by the scheduled prompt
 * (`ScheduledPromptDto.workflowInput`, agent, prompt).
 */
@Schema(name = "WorkflowStep")
data class WorkflowStepDto(
    @field:NotBlank(message = "step id must not be blank")
    val id: String,
    @field:NotBlank(message = "step tool must not be blank")
    val tool: String,
)

/**
 * HTTP DTO for a workflow: which tools the scheduler runs, in which order, for each occurrence of
 * a scheduled prompt.
 *
 * Exactly two steps: a target search (whose output carries `targets`), then `BatchCaseStarter`.
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
