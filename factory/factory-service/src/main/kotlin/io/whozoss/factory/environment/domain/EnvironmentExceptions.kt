package io.whozoss.factory.environment.domain

import io.whozoss.factory.error.FactoryException

/** Machine-readable error codes of the work-environment control plane. */
object EnvironmentErrorCodes {
    const val ENVIRONMENT_NOT_FOUND = "ENVIRONMENT_NOT_FOUND"
    const val INVALID_ENVIRONMENT_STATE = "INVALID_ENVIRONMENT_STATE"
    const val INVALID_ENVIRONMENT_REQUEST = "INVALID_ENVIRONMENT_REQUEST"
    const val ENVIRONMENT_IDENTITY_CONFLICT = "ENVIRONMENT_IDENTITY_CONFLICT"
    const val ENVIRONMENT_PROVISIONING_FAILED = "ENVIRONMENT_PROVISIONING_FAILED"
    const val ENVIRONMENT_POLICY_UNAVAILABLE = "ENVIRONMENT_POLICY_UNAVAILABLE"
}

/** 404 — the requested environment does not exist in the caller's scope. */
class EnvironmentNotFoundException(
    message: String = "Environment not found",
    details: Any? = null,
    cause: Throwable? = null,
) : FactoryException(404, EnvironmentErrorCodes.ENVIRONMENT_NOT_FOUND, message, details, cause)

/** 409 — the operation is not legal for the environment's current state. */
class InvalidEnvironmentStateException(
    message: String = "Invalid environment state for this operation",
    details: Any? = null,
    cause: Throwable? = null,
) : FactoryException(409, EnvironmentErrorCodes.INVALID_ENVIRONMENT_STATE, message, details, cause)

/** 400 — the environment request is structurally invalid. */
class InvalidEnvironmentRequestException(
    message: String = "Invalid environment request",
    details: Any? = null,
    cause: Throwable? = null,
) : FactoryException(400, EnvironmentErrorCodes.INVALID_ENVIRONMENT_REQUEST, message, details, cause)

/** 409 — an existing environment has a different immutable identity. */
class EnvironmentIdentityConflictException(
    message: String = "Environment identity conflict",
    details: Any? = null,
    cause: Throwable? = null,
) : FactoryException(409, EnvironmentErrorCodes.ENVIRONMENT_IDENTITY_CONFLICT, message, details, cause)
