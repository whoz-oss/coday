package io.whozoss.factory.workunit.domain

import io.whozoss.factory.error.FactoryException

/**
 * Machine codes raised by work-unit persistence/validation.
 *
 * Mirrors `WORK_UNIT_ERROR_CODES` in `factory/src/domain/work-unit.ts`, with the
 * scheduling code `WORK_UNIT_NOT_FOUND` shared by the lease protocol.
 */
object WorkUnitErrorCodes {
    const val INVALID_WORK_UNIT = "INVALID_WORK_UNIT"
    const val INVALID_STATE = "INVALID_STATE"
    const val INVALID_TRANSITION = "INVALID_TRANSITION"
    const val WORK_UNIT_NOT_FOUND = "WORK_UNIT_NOT_FOUND"
}

/** 404 — the addressed work unit does not exist in the caller's scope. */
class WorkUnitNotFoundException(
    message: String = "Work unit not found",
    details: Any? = null,
    cause: Throwable? = null,
) : FactoryException(404, WorkUnitErrorCodes.WORK_UNIT_NOT_FOUND, message, details, cause)

/** 409 — the `from -> to` lifecycle transition is not allowed by the state machine. */
class InvalidWorkUnitTransitionException(
    message: String = "Invalid work unit transition",
    details: Any? = null,
    cause: Throwable? = null,
) : FactoryException(409, WorkUnitErrorCodes.INVALID_TRANSITION, message, details, cause)

/** 400 — the work-unit input is structurally invalid. */
class InvalidWorkUnitException(
    message: String = "Invalid work unit",
    details: Any? = null,
    cause: Throwable? = null,
) : FactoryException(400, WorkUnitErrorCodes.INVALID_WORK_UNIT, message, details, cause)
