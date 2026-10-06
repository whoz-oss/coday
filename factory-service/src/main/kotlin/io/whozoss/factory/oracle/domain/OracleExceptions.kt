package io.whozoss.factory.oracle.domain

import io.whozoss.factory.error.FactoryException

/**
 * Transport-mappable exceptions owned by the ORACLES aggregate.
 *
 * They extend the shared [FactoryException] so [io.whozoss.factory.error.FactoryExceptionHandler]
 * renders the canonical Node error envelope `{ "error": { "code", "message", "details" } }`
 * with the machine codes the Node oracle routes already emit.
 */

/** 400 — the oracle definition is malformed or violates the executable contract. */
class InvalidOracleDefinitionException(
    message: String = "Invalid oracle definition",
    details: Any? = null,
    cause: Throwable? = null,
) : FactoryException(400, "INVALID_ORACLE_DEFINITION", message, details, cause)

/**
 * 400 — a definition file name does not encode the definition's `<id>@<version>`
 * identity (mirrors the Node `ORACLE_PATH_IDENTITY_MISMATCH`).
 */
class OraclePathIdentityMismatchException(
    message: String = "Oracle definition file name does not match its identity",
    details: Any? = null,
    cause: Throwable? = null,
) : FactoryException(400, "ORACLE_PATH_IDENTITY_MISMATCH", message, details, cause)

/** 409 — two definition files declare the same oracle id (mirrors `DUPLICATE_ORACLE_ID`). */
class DuplicateOracleIdException(
    message: String = "Duplicate oracle id",
    details: Any? = null,
    cause: Throwable? = null,
) : FactoryException(409, "DUPLICATE_ORACLE_ID", message, details, cause)

/** 404 — the requested oracle id is absent from the registry (mirrors `ORACLE_NOT_FOUND`). */
class OracleNotFoundException(
    message: String = "Oracle not found",
    details: Any? = null,
    cause: Throwable? = null,
) : FactoryException(404, "ORACLE_NOT_FOUND", message, details, cause)

/** 400 — the oracle run request body is malformed (mirrors `INVALID_ORACLE_RUN_REQUEST`). */
class InvalidOracleRunRequestException(
    message: String = "Invalid oracle run request",
    details: Any? = null,
    cause: Throwable? = null,
) : FactoryException(400, "INVALID_ORACLE_RUN_REQUEST", message, details, cause)
