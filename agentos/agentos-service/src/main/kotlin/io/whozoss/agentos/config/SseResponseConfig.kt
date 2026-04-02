package io.whozoss.agentos.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import mu.KLogging
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Sets the Servlet response buffer size to 0 for SSE endpoints.
 *
 * Tomcat (and other Servlet containers) buffer response bytes before sending
 * them to the network. For Server-Sent Events this causes all text chunks to
 * arrive at the browser at once when the buffer fills, rather than
 * progressively as each [org.springframework.web.servlet.mvc.method.annotation.SseEmitter.send]
 * is called.
 *
 * Calling [HttpServletResponse.setBufferSize] with 0 before the first write
 * requests the container to use the smallest possible buffer (in practice,
 * Tomcat honours 0 by using its minimum internal buffer). Combined with the
 * [Cache-Control: no-cache] and [X-Accel-Buffering: no] headers already set
 * on the SSE endpoint, this ensures each event frame is flushed immediately.
 *
 * Note: [setBufferSize] must be called before the first write (i.e. before the
 * response is committed). The interceptor runs before the controller method
 * writes the response, so the timing is correct.
 */
@Component
class SseResponseConfig : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry
            .addInterceptor(SseBufferInterceptor())
            .addPathPatterns("/api/cases/*/events")
    }
}

private class SseBufferInterceptor : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        // Request the smallest possible response buffer from the Servlet container.
        // Must be called before the first write (response not yet committed).
        try {
            response.setBufferSize(0)
        } catch (e: IllegalStateException) {
            logger.warn { "Could not set SSE buffer size to 0 (response already committed): ${e.message}" }
        }
        return true
    }

    companion object : KLogging()
}
