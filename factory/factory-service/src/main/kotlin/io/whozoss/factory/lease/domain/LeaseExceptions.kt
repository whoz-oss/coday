package io.whozoss.factory.lease.domain

import io.whozoss.factory.error.FactoryException

/**
 * Base class of every lease-protocol exception.
 *
 * Carries a stable, machine-readable [errorCode] plus structured [details] so
 * callers (and tests) never depend on the message. Port of the `LeaseError`
 * vocabulary in `factory/src/domain/lease/lease.ts` adapted to the Factory HTTP
 * error envelope.
 */
open class LeaseException(
    statusCode: Int,
    errorCode: String,
    message: String,
    details: Any? = null,
    cause: Throwable? = null,
) : FactoryException(statusCode, errorCode, message, details, cause)

/** Exact, machine-readable lease-protocol error codes. */
object LeaseErrorCodes {
    /** A stale / mismatched fencing token was presented. */
    const val LEASE_FENCED = "LEASE_FENCED"

    /** The lease is past its deadline and can no longer be renewed. */
    const val LEASE_EXPIRED = "LEASE_EXPIRED"

    /** The eligible-work-unit scan found nothing to lease. */
    const val NO_ELIGIBLE_WORK_UNIT = "NO_ELIGIBLE_WORK_UNIT"

    /** The operation is not legal for the lease's current state. */
    const val INVALID_LEASE_STATE = "INVALID_LEASE_STATE"
}

/** 409 — a stale / mismatched fencing token was presented. */
class LeaseFencedException(
    message: String = "Lease fenced by a newer fencing token",
    details: Any? = null,
    cause: Throwable? = null,
) : LeaseException(409, LeaseErrorCodes.LEASE_FENCED, message, details, cause)

/** 409 — the lease is past its deadline and can no longer be renewed. */
class LeaseExpiredException(
    message: String = "Lease has expired",
    details: Any? = null,
    cause: Throwable? = null,
) : LeaseException(409, LeaseErrorCodes.LEASE_EXPIRED, message, details, cause)

/** 404 — the eligible-work-unit scan found nothing to lease. */
class NoEligibleWorkUnitException(
    message: String = "No eligible work unit to lease",
    details: Any? = null,
    cause: Throwable? = null,
) : LeaseException(404, LeaseErrorCodes.NO_ELIGIBLE_WORK_UNIT, message, details, cause)

/** 409 — the operation is not legal for the lease's current state. */
class InvalidLeaseStateException(
    message: String = "Invalid lease state for this operation",
    details: Any? = null,
    cause: Throwable? = null,
) : LeaseException(409, LeaseErrorCodes.INVALID_LEASE_STATE, message, details, cause)
