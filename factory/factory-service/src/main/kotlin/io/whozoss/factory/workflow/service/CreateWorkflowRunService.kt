package io.whozoss.factory.workflow.service

import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.ControllerExecutionInput
import io.whozoss.factory.workflow.domain.ControllerRequestInput
import io.whozoss.factory.workflow.domain.WorkflowStartCommand
import io.whozoss.factory.workflow.domain.workflowException
import io.whozoss.factory.workflow.domain.WorkflowErrorCodes
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Single application use case used by every trusted create-run boundary. */
@Service
class CreateWorkflowRunService(
    private val workflowService: WorkflowService,
) {
    data class Command(
        val workflowType: String,
        val title: String?,
        val initialRequest: String?,
        val parameters: Map<String, Any?> = emptyMap(),
        val idempotencyKey: String,
    )

    fun create(
        scope: TenantScope,
        namespaceId: String,
        actorId: String,
        caseId: String?,
        command: Command,
        repoRoot: String?,
    ): WorkflowHttpResult {
        val workflowType = command.workflowType.trim()
        if (workflowType.isEmpty() || workflowType.length > 128 || command.idempotencyKey.isBlank() || command.idempotencyKey.length > 256) {
            throw workflowException(WorkflowErrorCodes.INVALID_START_REQUEST)
        }
        val unsupported = command.parameters.keys - SUPPORTED_PARAMETERS
        if (unsupported.isNotEmpty()) {
            throw workflowException(WorkflowErrorCodes.INVALID_START_REQUEST, "Unsupported workflow parameters: ${unsupported.sorted().joinToString()}.")
        }
        val workflowId = UUID.nameUUIDFromBytes(
            "create-workflow-run|${scope.organizationId}|${scope.workstreamId}|$namespaceId|${command.idempotencyKey}"
                .toByteArray(StandardCharsets.UTF_8),
        ).toString()
        val title = command.title?.trim()?.takeIf { it.isNotEmpty() }?.also {
            if (it.length > TITLE_MAX) throw workflowException(WorkflowErrorCodes.INVALID_START_REQUEST, "title must not exceed $TITLE_MAX characters.")
        } ?: readableFallback(workflowType)
        val ticket = (command.parameters["ticket"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val workstream = (command.parameters["workstream"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val initialRequest = command.initialRequest?.trim()?.takeIf { it.isNotEmpty() }?.also {
            if (it.length > INITIAL_REQUEST_MAX) {
                throw workflowException(
                    WorkflowErrorCodes.INVALID_START_REQUEST,
                    "initialRequest must not exceed $INITIAL_REQUEST_MAX characters.",
                )
            }
        }
        val result = workflowService.start(
            scope,
            namespaceId,
            WorkflowStartCommand(
                workflowId,
                workflowType,
                title,
                ticket = ticket,
                workstream = workstream,
                controllerRequest = initialRequest?.let {
                    ControllerRequestInput(
                        text = it,
                        namespaceId = namespaceId,
                        observedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString(),
                        actorId = actorId,
                        source = INITIAL_REQUEST_SOURCE,
                    )
                },
            ),
            ControllerExecutionInput(
                runtimeId = "factory-control-plane",
                kind = "agentos",
                agentId = "factory-runner",
                caseId = caseId,
                actorId = actorId,
                namespaceId = namespaceId,
            ),
            repoRoot,
        )
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any?>
        return WorkflowHttpResult(
            result.status,
            data + mapOf(
                "workflowId" to workflowId,
                "title" to title,
                "created" to (data["created"] ?: false),
                "queued" to (data["queued"] ?: false),
                "idempotent" to (data["idempotent"] ?: false),
                "submissionId" to data["submissionId"],
                "submissionStatus" to data["submissionStatus"],
                "status" to data["submissionStatus"],
                "revision" to (data["revision"] ?: 1),
            ),
        )
    }

    private fun readableFallback(workflowType: String): String =
        workflowType.replace('-', ' ').replace('_', ' ').trim()
            .ifBlank { "workflow run" }
            .replaceFirstChar { it.uppercase() }

    private companion object {
        const val TITLE_MAX = 200
        const val INITIAL_REQUEST_MAX = 4_000
        const val INITIAL_REQUEST_SOURCE = "factory-create-run"
        val SUPPORTED_PARAMETERS = setOf("ticket", "workstream")
    }
}
