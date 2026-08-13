package io.whozoss.agentos.a2a

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus

/**
 * Raised when a new A2A task (case) could be persisted but not made usable —
 * currently only when the creator's `[:ADMIN]` grant on the fresh case fails.
 *
 * A task without that grant is invisible to its owner (`GET /api/cases/by-parentId/{ns}/mine`
 * resolves via a direct user↔case edge) and cannot be opened, starred or deleted from the UI,
 * so [A2AService.createCase] treats the grant as part of task creation and fails the whole
 * call rather than leaving an orphan behind.
 *
 * Deliberately **not** an [IllegalStateException]: [A2AJsonRpcHandler] maps that type to
 * `TASK_NOT_CANCELABLE` (-32002) for the `tasks/cancel` path. This one falls through to the
 * generic handler, which is the semantically correct `INTERNAL_ERROR` (-32603) / HTTP 500.
 */
@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
class A2ATaskProvisioningException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
