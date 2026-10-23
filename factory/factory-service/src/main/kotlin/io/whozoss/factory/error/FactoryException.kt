package io.whozoss.factory.error

/**
 * Base class of every transport-mappable Factory exception.
 *
 * Carries the HTTP status and a stable, machine-readable error code so the
 * `@RestControllerAdvice` can reproduce the Node error envelope exactly.
 */
abstract class FactoryException(
    val statusCode: Int,
    val errorCode: String,
    message: String,
    val details: Any? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** 400 — malformed or semantically invalid request. */
class BadRequestException(
    message: String = "Bad request",
    details: Any? = null,
    cause: Throwable? = null,
) : FactoryException(400, "BAD_REQUEST", message, details, cause)

/** 401 — the caller presented no valid credential. */
class UnauthenticatedException(
    message: String = "Authentication required",
    details: Any? = null,
    cause: Throwable? = null,
) : FactoryException(401, "UNAUTHENTICATED", message, details, cause)

/** 403 — the caller is not an administrator. */
class ForbiddenAdminRequiredException(
    message: String = "Admin authorization required",
    details: Any? = null,
    cause: Throwable? = null,
) : FactoryException(403, "FORBIDDEN_ADMIN_REQUIRED", message, details, cause)

/** 404 — the requested resource does not exist in the caller's scope. */
class ResourceNotFoundException(
    message: String = "Resource not found",
    details: Any? = null,
    cause: Throwable? = null,
) : FactoryException(404, "NOT_FOUND", message, details, cause)

/** 409 — the request conflicts with the current state of the resource. */
class ConflictException(
    message: String = "Resource conflict",
    details: Any? = null,
    cause: Throwable? = null,
) : FactoryException(409, "CONFLICT", message, details, cause)

/** 409 — optimistic-locking revision mismatch. */
class RevisionConflictException(
    message: String = "Revision conflict",
    details: Any? = null,
    cause: Throwable? = null,
) : FactoryException(409, "REVISION_CONFLICT", message, details, cause)

/** 422 — the request is well-formed but semantically unprocessable. */
class UnprocessableEntityException(
    message: String = "Unprocessable entity",
    details: Any? = null,
    cause: Throwable? = null,
) : FactoryException(422, "UNPROCESSABLE_ENTITY", message, details, cause)
