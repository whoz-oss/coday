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

class AuthSecurityServiceUnitSpec : StringSpec({
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
    // resolveCurrentIdentity — Authorization header (Bearer JWT)
    // -------------------------------------------------------------------------

    "resolveCurrentIdentity returns preferred_username from Authorization Bearer JWT" {
        val username = "dave@example.com"
        val jwt = buildJwt(mapOf("preferred_username" to username, "sub" to "user456"))

        setRequest { addHeader(AuthSecurityService.AUTHORIZATION_HEADER, "Bearer $jwt") }

        val service = AuthSecurityService(objectMapper)
        service.resolveCurrentIdentity() shouldBe username
    }

    "resolveCurrentIdentity prefers CF_Authorization over Authorization header" {
        val cfEmail = "cf@example.com"
        val authUsername = "auth@example.com"
        val cfJwt = buildJwt(mapOf("email" to cfEmail))
        val authJwt = buildJwt(mapOf("preferred_username" to authUsername))

        setRequest {
            addHeader(AuthSecurityService.CF_AUTHORIZATION_HEADER, cfJwt)
            addHeader(AuthSecurityService.AUTHORIZATION_HEADER, "Bearer $authJwt")
        }

        val service = AuthSecurityService(objectMapper)
        service.resolveCurrentIdentity() shouldBe cfEmail
    }

    "resolveCurrentIdentity prefers Authorization over x-forwarded-email" {
        val authUsername = "auth@example.com"
        val forwardedEmail = "forwarded@example.com"
        val authJwt = buildJwt(mapOf("preferred_username" to authUsername))

        setRequest {
            addHeader(AuthSecurityService.AUTHORIZATION_HEADER, "Bearer $authJwt")
            addHeader(AuthSecurityService.X_FORWARDED_EMAIL_HEADER, forwardedEmail)
        }

        val service = AuthSecurityService(objectMapper)
        service.resolveCurrentIdentity() shouldBe authUsername
    }

    "resolveCurrentIdentity falls through Authorization to x-forwarded-email when preferred_username claim is absent" {
        val forwardedEmail = "fallback@example.com"
        val jwtWithoutUsername = buildJwt(mapOf("sub" to "user789"))

        setRequest {
            addHeader(AuthSecurityService.AUTHORIZATION_HEADER, "Bearer $jwtWithoutUsername")
            addHeader(AuthSecurityService.X_FORWARDED_EMAIL_HEADER, forwardedEmail)
        }

        val service = AuthSecurityService(objectMapper)
        service.resolveCurrentIdentity() shouldBe forwardedEmail
    }

    "resolveCurrentIdentity falls through Authorization with malformed JWT to x-forwarded-email" {
        val forwardedEmail = "fallback@example.com"

        setRequest {
            addHeader(AuthSecurityService.AUTHORIZATION_HEADER, "Bearer not.a.valid.jwt.at.all")
            addHeader(AuthSecurityService.X_FORWARDED_EMAIL_HEADER, forwardedEmail)
        }

        val service = AuthSecurityService(objectMapper)
        service.resolveCurrentIdentity() shouldBe forwardedEmail
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
