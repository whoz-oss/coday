package io.whozoss.agentos.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.server.ResponseStatusException
import java.util.Base64

class AuthSecurityServiceTest : StringSpec({
    timeout = 5000

    val objectMapper = ObjectMapper().registerKotlinModule()

    afterEach {
        RequestContextHolder.resetRequestAttributes()
    }

    fun buildJwt(claims: Map<String, Any>): String {
        val header = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"alg\":\"RS256\"}".toByteArray())
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(objectMapper.writeValueAsBytes(claims))
        return "$header.$payload.fakesig"
    }

    fun setRequest(block: MockHttpServletRequest.() -> Unit = {}) {
        val req = MockHttpServletRequest().apply(block)
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(req))
    }

    // -------------------------------------------------------------------------
    // JWT email extraction (pure function — no request context needed)
    // -------------------------------------------------------------------------

    "extractEmailFromJwt returns email claim from valid CF JWT" {
        val jwt = buildJwt(mapOf("email" to "alice@example.com", "sub" to "user123"))
        val service = AuthSecurityService(objectMapper)

        service.extractEmailFromJwt(jwt) shouldBe "alice@example.com"
    }

    "extractEmailFromJwt returns null when email claim is absent" {
        val jwt = buildJwt(mapOf("sub" to "user123"))
        val service = AuthSecurityService(objectMapper)

        service.extractEmailFromJwt(jwt) shouldBe null
    }

    "extractEmailFromJwt returns null for malformed token" {
        val service = AuthSecurityService(objectMapper)

        service.extractEmailFromJwt("not.a.jwt") shouldBe null
        service.extractEmailFromJwt("onlyone") shouldBe null
    }

    // -------------------------------------------------------------------------
    // resolveCurrentIdentity — CF_Authorization header
    // -------------------------------------------------------------------------

    "resolveCurrentIdentity returns email from CF JWT header" {
        val email = "bob@example.com"
        val jwt = buildJwt(mapOf("email" to email))

        setRequest { addHeader(AuthSecurityService.CF_AUTHORIZATION_HEADER, jwt) }

        val service = AuthSecurityService(objectMapper)
        service.resolveCurrentIdentity() shouldBe email
    }

    // -------------------------------------------------------------------------
    // resolveCurrentIdentity — x-forwarded-email fallback
    // -------------------------------------------------------------------------

    "resolveCurrentIdentity falls back to x-forwarded-email when CF header is absent" {
        val email = "carol@example.com"

        setRequest { addHeader(AuthSecurityService.X_FORWARDED_EMAIL_HEADER, email) }

        val service = AuthSecurityService(objectMapper)
        service.resolveCurrentIdentity() shouldBe email
    }

    // -------------------------------------------------------------------------
    // resolveCurrentIdentity — error cases
    // -------------------------------------------------------------------------

    "resolveCurrentIdentity throws 401 when no identity header is present" {
        setRequest()

        val service = AuthSecurityService(objectMapper)
        val ex = runCatching { service.resolveCurrentIdentity() }
            .exceptionOrNull() as? ResponseStatusException
        ex?.statusCode?.value() shouldBe HttpStatus.UNAUTHORIZED.value()
    }
})
