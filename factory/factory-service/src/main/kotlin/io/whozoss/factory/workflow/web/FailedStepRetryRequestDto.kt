package io.whozoss.factory.workflow.web

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Documented shape of the body of `POST /api/factory/workflows/{workflowId}/steps/{stepId}/failed-step-retries`.
 *
 * Documentation only: the controller reads the body as a `Map` so that an unknown field is refused with
 * `INVALID_REQUEST` (a typed body would silently drop it). Keep this class in sync with the key whitelist of
 * `WorkflowController.retryFailedStep`.
 */
@Schema(description = "Retry of a failed agent step. No other field is accepted.")
data class FailedStepRetryRequestDto(
    @field:Schema(description = "Namespace of the workflow.", requiredMode = Schema.RequiredMode.REQUIRED)
    val namespaceId: String,
    @field:Schema(
        description = "Instance revision the retry is fenced on, as advertised by the `retry` allowed action.",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val expectedRevision: Int,
    @field:Schema(
        description = "Optional information for the agent. Absent, null and blank all mean none. " +
            "At most 4000 characters after trimming.",
        maxLength = 4000,
        nullable = true,
        requiredMode = Schema.RequiredMode.NOT_REQUIRED,
    )
    val additionalContext: String? = null,
)
