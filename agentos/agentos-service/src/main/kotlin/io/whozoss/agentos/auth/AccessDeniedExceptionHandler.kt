package io.whozoss.agentos.auth

import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Global exception handler for [AccessDeniedException] and [IllegalArgumentException].
 *
 * Returns structured error responses:
 * - [AccessDeniedException] → 403 Forbidden with error code, reason, and required role.
 * - [IllegalArgumentException] → 400 Bad Request with error code and reason.
 *
 * This ensures consistent error responses across all controllers that use [AuthorizationService].
 */
@RestControllerAdvice
class AccessDeniedExceptionHandler {

    @ExceptionHandler(AccessDeniedException::class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    fun handleAccessDenied(ex: AccessDeniedException): Map<String, String?> {
        logger.warn { "Access denied: ${ex.reason}" }
        return mapOf(
            "error" to "ACCESS_DENIED",
            "reason" to ex.reason,
            "requiredRole" to ex.requiredRole,
        )
    }

    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleIllegalArgument(ex: IllegalArgumentException): Map<String, String?> {
        logger.warn { "Bad request: ${ex.message}" }
        return mapOf(
            "error" to "BAD_REQUEST",
            "reason" to ex.message,
        )
    }

    companion object : KLogging()
}
