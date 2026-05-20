package io.whozoss.agentos.security

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.server.ResponseStatusException

class AuthSecurityServiceUnitSpec : StringSpec({
    timeout = 5000

    afterEach {
        RequestContextHolder.resetRequestAttributes()
    }

    fun setRequest(block: MockHttpServletRequest.() -> Unit = {}) {
        val req = MockHttpServletRequest().apply(block)
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(req))
    }

    val service = AuthSecurityService()

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    "resolveCurrentIdentity returns the X-External-User-Id header value" {
        setRequest { addHeader(AuthSecurityService.X_EXTERNAL_USER_ID_HEADER, "alice@example.com") }

        service.resolveCurrentIdentity() shouldBe "alice@example.com"
    }

    "resolveCurrentIdentity accepts any non-blank string as external id" {
        setRequest { addHeader(AuthSecurityService.X_EXTERNAL_USER_ID_HEADER, "user-123") }

        service.resolveCurrentIdentity() shouldBe "user-123"
    }

    // -------------------------------------------------------------------------
    // Error cases
    // -------------------------------------------------------------------------

    "resolveCurrentIdentity throws 401 when header is absent" {
        setRequest()

        val ex = runCatching { service.resolveCurrentIdentity() }
            .exceptionOrNull() as? ResponseStatusException
        ex?.statusCode?.value() shouldBe HttpStatus.UNAUTHORIZED.value()
    }

    "resolveCurrentIdentity throws 401 when header is blank" {
        setRequest { addHeader(AuthSecurityService.X_EXTERNAL_USER_ID_HEADER, "   ") }

        val ex = runCatching { service.resolveCurrentIdentity() }
            .exceptionOrNull() as? ResponseStatusException
        ex?.statusCode?.value() shouldBe HttpStatus.UNAUTHORIZED.value()
    }
})
