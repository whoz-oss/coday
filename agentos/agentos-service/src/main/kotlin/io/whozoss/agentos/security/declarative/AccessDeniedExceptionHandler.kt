package io.whozoss.agentos.security.declarative

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerMapping
import java.nio.charset.StandardCharsets

/**
 * Spring Security [AccessDeniedHandler] that translates AOP-thrown [AccessDeniedException]
 * into AgentOS-flavoured HTTP responses (Story 5.1).
 *
 * Spring Security's default behavior is to write HTTP 403 directly via [ExceptionTranslationFilter]
 * before any `@ControllerAdvice` can run, so we plug in here instead. The handler resolves the source
 * controller method via the standard `HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE` request attribute
 * and inspects whether [HideOnAccessDenied] is present.
 *
 * - Method annotated [HideOnAccessDenied] → HTTP 404 with body `{ "error": "Resource not found" }`
 *   (IG1 pattern: hide existence)
 * - Otherwise → HTTP 403 with body `{ "error": "Access denied" }`
 *
 * The body shape mirrors Spring Boot's default error JSON to avoid surprising existing consumers.
 */
@Component
class AccessDeniedExceptionHandler(
    private val objectMapper: ObjectMapper,
) : AccessDeniedHandler {

    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        if (response.isCommitted) {
            // Response already started writing — too late to rewrite status/body. Log and bail.
            logger.warn { "[AccessDeniedHandler] Response already committed for ${request.requestURI}; cannot send 403/404" }
            return
        }

        val handlerMethod = request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE) as? HandlerMethod
        val hideExistence = handlerMethod?.hasMethodAnnotation(HideOnAccessDenied::class.java) == true

        val (status, message) = if (hideExistence) {
            HttpStatus.NOT_FOUND to "Resource not found"
        } else {
            HttpStatus.FORBIDDEN to "Access denied"
        }

        response.status = status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = StandardCharsets.UTF_8.name()

        val body = try {
            objectMapper.writeValueAsString(mapOf("error" to message, "status" to status.value()))
        } catch (ex: JsonProcessingException) {
            logger.warn(ex) { "[AccessDeniedHandler] Failed to serialise error body, falling back to plain JSON" }
            """{"error":"$message","status":${status.value()}}"""
        }

        response.writer.apply {
            write(body)
            flush()
        }
    }

    companion object : KLogging()
}
