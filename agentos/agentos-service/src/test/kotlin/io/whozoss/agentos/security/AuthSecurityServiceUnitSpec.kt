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
    val service = AuthSecurityService(objectMapper)

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

        service.extractEmailFromJwt(jwt) shouldBe "alice@example.com"
    }

    "extractEmailFromJwt returns null when email claim is absent" {
        val jwt = buildJwt(mapOf("sub" to "user123"))

        service.extractEmailFromJwt(jwt) shouldBe null
    }

    "extractEmailFromJwt returns null for malformed token" {
        service.extractEmailFromJwt("not.a.jwt") shouldBe null
        service.extractEmailFromJwt("onlyone") shouldBe null
    }

    // -------------------------------------------------------------------------
    // resolveCurrentIdentity — X-External-User-Id (highest priority)
    // -------------------------------------------------------------------------

    "resolveCurrentIdentity returns X-External-User-Id when present" {
        setRequest { addHeader(AuthSecurityService.X_EXTERNAL_USER_ID_HEADER, "alice@example.com") }

        service.resolveCurrentIdentity() shouldBe "alice@example.com"
    }

    "resolveCurrentIdentity prefers X-External-User-Id over CF_Authorization" {
        val cfJwt = buildJwt(mapOf("email" to "cf@example.com"))
        setRequest {
            addHeader(AuthSecurityService.X_EXTERNAL_USER_ID_HEADER, "gateway@example.com")
            addHeader(AuthSecurityService.CF_AUTHORIZATION_HEADER, cfJwt)
        }

        service.resolveCurrentIdentity() shouldBe "gateway@example.com"
    }

    // -------------------------------------------------------------------------
    // resolveCurrentIdentity — CF_Authorization header
    // -------------------------------------------------------------------------

    "resolveCurrentIdentity returns email from CF JWT header" {
        val jwt = buildJwt(mapOf("email" to "bob@example.com"))
        setRequest { addHeader(AuthSecurityService.CF_AUTHORIZATION_HEADER, jwt) }

        service.resolveCurrentIdentity() shouldBe "bob@example.com"
    }

    // -------------------------------------------------------------------------
    // resolveCurrentIdentity — Authorization Bearer JWT
    // -------------------------------------------------------------------------

    "resolveCurrentIdentity returns preferred_username from Authorization Bearer JWT" {
        val jwt = buildJwt(mapOf("preferred_username" to "dave@example.com", "sub" to "user456"))
        setRequest { addHeader(AuthSecurityService.AUTHORIZATION_HEADER, "Bearer $jwt") }

        service.resolveCurrentIdentity() shouldBe "dave@example.com"
    }

    "resolveCurrentIdentity prefers CF_Authorization over Authorization header" {
        val cfJwt = buildJwt(mapOf("email" to "cf@example.com"))
        val authJwt = buildJwt(mapOf("preferred_username" to "auth@example.com"))
        setRequest {
            addHeader(AuthSecurityService.CF_AUTHORIZATION_HEADER, cfJwt)
            addHeader(AuthSecurityService.AUTHORIZATION_HEADER, "Bearer $authJwt")
        }

        service.resolveCurrentIdentity() shouldBe "cf@example.com"
    }

    "resolveCurrentIdentity prefers Authorization over x-forwarded-email" {
        val authJwt = buildJwt(mapOf("preferred_username" to "auth@example.com"))
        setRequest {
            addHeader(AuthSecurityService.AUTHORIZATION_HEADER, "Bearer $authJwt")
            addHeader(AuthSecurityService.X_FORWARDED_EMAIL_HEADER, "forwarded@example.com")
        }

        service.resolveCurrentIdentity() shouldBe "auth@example.com"
    }

    "resolveCurrentIdentity falls through Authorization to x-forwarded-email when preferred_username claim is absent" {
        val jwtWithoutUsername = buildJwt(mapOf("sub" to "user789"))
        setRequest {
            addHeader(AuthSecurityService.AUTHORIZATION_HEADER, "Bearer $jwtWithoutUsername")
            addHeader(AuthSecurityService.X_FORWARDED_EMAIL_HEADER, "fallback@example.com")
        }

        service.resolveCurrentIdentity() shouldBe "fallback@example.com"
    }

    "resolveCurrentIdentity falls through malformed Authorization JWT to x-forwarded-email" {
        setRequest {
            addHeader(AuthSecurityService.AUTHORIZATION_HEADER, "Bearer not.a.valid.jwt.at.all")
            addHeader(AuthSecurityService.X_FORWARDED_EMAIL_HEADER, "fallback@example.com")
        }

        service.resolveCurrentIdentity() shouldBe "fallback@example.com"
    }

    // -------------------------------------------------------------------------
    // resolveCurrentIdentity — x-forwarded-email fallback
    // -------------------------------------------------------------------------

    "resolveCurrentIdentity falls back to x-forwarded-email when all JWT headers are absent" {
        setRequest { addHeader(AuthSecurityService.X_FORWARDED_EMAIL_HEADER, "carol@example.com") }

        service.resolveCurrentIdentity() shouldBe "carol@example.com"
    }

    // -------------------------------------------------------------------------
    // resolveCurrentIdentity — error cases
    // -------------------------------------------------------------------------

    "resolveCurrentIdentity throws 401 when no identity header is present" {
        setRequest()

        val ex = runCatching { service.resolveCurrentIdentity() }
            .exceptionOrNull() as? ResponseStatusException
        ex?.statusCode?.value() shouldBe HttpStatus.UNAUTHORIZED.value()
    }
})
