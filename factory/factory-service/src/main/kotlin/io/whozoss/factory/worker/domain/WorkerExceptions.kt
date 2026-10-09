package io.whozoss.factory.worker.domain

import io.whozoss.factory.error.FactoryException

/**
 * Machine codes raised by worker persistence/validation.
 *
 * Mirrors `WORKER_ERROR_CODES` in `factory/src/domain/worker.ts`.
 */
object WorkerErrorCodes {
    const val INVALID_WORKER = "INVALID_WORKER"
    const val INVALID_STATE = "INVALID_STATE"
    const val INVALID_TRANSITION = "INVALID_TRANSITION"
    const val WORKER_ALREADY_EXISTS = "WORKER_ALREADY_EXISTS"
    const val NOT_FOUND = "NOT_FOUND"
}

/** 404 — the addressed worker does not exist in the caller's scope. */
class WorkerNotFoundException(
    message: String = "Worker not found",
    details: Any? = null,
    cause: Throwable? = null,
) : FactoryException(404, WorkerErrorCodes.NOT_FOUND, message, details, cause)

/** 409 — the `from -> to` lifecycle transition is not allowed by the state machine. */
class InvalidWorkerTransitionException(
    message: String = "Invalid worker transition",
    details: Any? = null,
    cause: Throwable? = null,
) : FactoryException(409, WorkerErrorCodes.INVALID_TRANSITION, message, details, cause)

/** 400 — the worker input is structurally invalid. */
class InvalidWorkerException(
    message: String = "Invalid worker",
    details: Any? = null,
    cause: Throwable? = null,
) : FactoryException(400, WorkerErrorCodes.INVALID_WORKER, message, details, cause)

/** 409 — a worker with this identity already exists. */
class WorkerAlreadyExistsException(
    message: String = "Worker already exists",
    details: Any? = null,
    cause: Throwable? = null,
) : FactoryException(409, WorkerErrorCodes.WORKER_ALREADY_EXISTS, message, details, cause)
