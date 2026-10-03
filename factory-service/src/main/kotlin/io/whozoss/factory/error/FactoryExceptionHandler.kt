package io.whozoss.factory.error

import mu.KotlinLogging
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * Translates exceptions into the canonical Factory error envelope.
 *
 * Every response this advice produces has the exact Node shape:
 * `{ "error": { "code": "...", "message": "...", "details": null } }`.
 */
@RestControllerAdvice
class FactoryExceptionHandler {

    private val logger = KotlinLogging.logger {}

    @ExceptionHandler(FactoryException::class)
    fun handleFactoryException(exception: FactoryException): ResponseEntity<ErrorResponse> {
        logger.debug { "Handled FactoryException ${exception.errorCode} -> ${exception.statusCode}" }
        return ResponseEntity
            .status(exception.statusCode)
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                ErrorResponse(
                    ErrorDetail(
                        code = exception.errorCode,
                        message = exception.message ?: exception.errorCode,
                        details = exception.details,
                    ),
                ),
            )
    }

    /**
     * An unmapped URL reaches the resource handler in Spring 6.1+, which raises
     * [NoResourceFoundException]. Without this explicit mapping the catch-all
     * [Exception] handler below would turn every unknown path into a `500`,
     * breaking the plugin contract: a core without the Forge plugin must leave
     * the `api/forge` and `api/jira` surfaces unmounted and answer `404`.
     */
    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResourceFound(exception: NoResourceFoundException): ResponseEntity<ErrorResponse> {
        logger.debug { "No handler for request: ${exception.resourcePath}" }
        return ResponseEntity
            .status(404)
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                ErrorResponse(
                    ErrorDetail(
                        code = "NOT_FOUND",
                        message = "Resource not found",
                        details = null,
                    ),
                ),
            )
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgumentException(exception: IllegalArgumentException): ResponseEntity<ErrorResponse> =
        ResponseEntity
            .status(400)
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                ErrorResponse(
                    ErrorDetail(
                        code = "BAD_REQUEST",
                        message = exception.message ?: "Invalid argument",
                        details = null,
                    ),
                ),
            )

    @ExceptionHandler(Exception::class)
    fun handleGenericException(exception: Exception): ResponseEntity<ErrorResponse> {
        logger.error(exception) { "Unhandled exception" }
        return ResponseEntity
            .status(500)
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                ErrorResponse(
                    ErrorDetail(
                        code = "INTERNAL_ERROR",
                        message = exception.message ?: "Internal server error",
                        details = null,
                    ),
                ),
            )
    }
}
