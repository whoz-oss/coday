package io.whozoss.factory.error

import mu.KotlinLogging
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

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

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgumentException(exception: IllegalArgumentException): ResponseEntity<ErrorResponse> =
        ResponseEntity
            .status(400)
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
