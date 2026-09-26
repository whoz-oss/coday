package io.whozoss.factory.artifact.web

import io.whozoss.factory.error.ErrorDetail
import io.whozoss.factory.error.ErrorResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Maps a non-POST call to an admin artifact route onto the canonical Factory
 * error envelope, mirroring the Node contract:
 *
 * ```json
 * { "error": { "code": "METHOD_NOT_ALLOWED", "message": "Admin artifact commands require POST", "details": null } }
 * ```
 *
 * Spring raises [HttpRequestMethodNotSupportedException] during handler mapping,
 * *before* any controller method or admin guard runs, so it is handled here
 * rather than inside the controller.
 */
@RestControllerAdvice
class ArtifactAdminMethodNotAllowedAdvice {

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethodNotSupported(exception: HttpRequestMethodNotSupportedException): ResponseEntity<ErrorResponse> =
        ResponseEntity
            .status(405)
            .body(
                ErrorResponse(
                    ErrorDetail(
                        code = "METHOD_NOT_ALLOWED",
                        message = "Admin artifact commands require POST",
                        details = null,
                    ),
                ),
            )
}
