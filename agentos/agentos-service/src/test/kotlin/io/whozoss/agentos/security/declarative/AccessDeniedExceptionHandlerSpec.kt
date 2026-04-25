package io.whozoss.agentos.security.declarative

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerMapping
import java.lang.reflect.Method

/**
 * Unit tests for [AccessDeniedExceptionHandler] (Story 5.1, AC6).
 *
 * Exercises the 404 / 403 mapping driven by [HideOnAccessDenied] presence on the
 * resolved [HandlerMethod], plus the robustness paths (already-committed response).
 */
class AccessDeniedExceptionHandlerSpec : StringSpec({

    val handler = AccessDeniedExceptionHandler(ObjectMapper())
    val ex = AccessDeniedException("denied")

    fun handlerMethodFor(name: String): HandlerMethod {
        val method: Method = SampleHandlers::class.java.getDeclaredMethod(name)
        return HandlerMethod(SampleHandlers(), method)
    }

    "method annotated @HideOnAccessDenied → HTTP 404 with hide-existence body" {
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()
        request.setAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE, handlerMethodFor("hidden"))

        handler.handle(request, response, ex)

        response.status shouldBe 404
        response.contentType shouldContain "application/json"
        response.contentAsString shouldContain "Resource not found"
        response.contentAsString shouldContain "\"status\":404"
    }

    "method NOT annotated @HideOnAccessDenied → HTTP 403 with access-denied body" {
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()
        request.setAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE, handlerMethodFor("plain"))

        handler.handle(request, response, ex)

        response.status shouldBe 403
        response.contentAsString shouldContain "Access denied"
        response.contentAsString shouldContain "\"status\":403"
    }

    "no HandlerMethod attribute (e.g. exception thrown before dispatch) → defaults to 403" {
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()
        // No BEST_MATCHING_HANDLER_ATTRIBUTE set

        handler.handle(request, response, ex)

        response.status shouldBe 403
    }

    "already-committed response: handler logs and bails without rewriting" {
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()
        request.setAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE, handlerMethodFor("hidden"))
        // Simulate a partially-written response by flushing the buffer
        response.writer.write("partial body")
        response.flushBuffer()

        handler.handle(request, response, ex)

        // Status must NOT be overwritten (still default 200 from the partial write)
        response.status shouldBe 200
        response.contentAsString shouldContain "partial body"
    }
})

/**
 * Test fixture. Reflection target for [HandlerMethod] construction.
 * Must be `open` so HandlerMethod can introspect with cglib/spring proxies.
 */
open class SampleHandlers {
    @HideOnAccessDenied
    open fun hidden() {
    }

    open fun plain() {
    }
}
