package io.whozoss.factory.artifact.error

import io.whozoss.factory.error.FactoryException

/**
 * Transport-mappable exception raised by the artifact admin use cases.
 *
 * Carries the stable, machine-readable error code and HTTP status of the Node
 * admin governance contract (`ARTIFACT_NOT_FOUND`, `INVALID_LEGAL_HOLD`,
 * `RETENTION_ACTIVE`, `LEGAL_HOLD_ACTIVE`, …) so the shared
 * `FactoryExceptionHandler` reproduces the exact error envelope.
 */
class ArtifactAdminException(
    errorCode: String,
    statusCode: Int,
    message: String,
    details: Any? = null,
) : FactoryException(statusCode, errorCode, message, details)
