package io.whozoss.agentos.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.server.ResponseStatusException
import java.util.Base64

class AuthSecurityServiceTest : StringSpec({
    timeout = 5000

    val objectMapper = ObjectMapper().registerKotlinModule()

    fun makeUser(email: String) = User(
        metadata = EntityMetadata(),
        externalId = email,
        email = email,
    )

    fun buildJwt(claims: Map<String, Any>): String {
        val header = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"alg\":\"RS256\"}".toByteArray())
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(objectMapper.writeValueAsBytes(claims))
        return "$header.$payload.fakesig"
    }

    // -------------------------------------------------------------------------
    // JWT email extraction
    // -------------------------------------------------------------------------

    "extractEmailFromJwt returns email claim from valid CF JWT" {
        val jwt = buildJwt(mapOf("email" to "alice@example.com", "sub" to "user123"))
        val service = AuthSecurityService(mockk(), objectMapper)

        service.extractEmailFromJwt(jwt) shouldBe "alice@example.com"
    }

    "extractEmailFromJwt returns null when email claim is absent" {
        val jwt = buildJwt(mapOf("sub" to "user123"))
        val service = AuthSecurityService(mockk(), objectMapper)

        service.extractEmailFromJwt(jwt) shouldBe null
    }

    "extractEmailFromJwt returns null for malformed token" {
        val service = AuthSecurityService(mockk(), objectMapper)

        service.extractEmailFromJwt("not.a.jwt") shouldBe null
        service.extractEmailFromJwt("onlyone") shouldBe null
    }

    // -------------------------------------------------------------------------
    // resolveCurrentUser — CF_Authorization header
    // -------------------------------------------------------------------------

    "resolveCurrentUser resolves user from CF JWT header" {
        val email = "bob@example.com"
        val jwt = buildJwt(mapOf("email" to email))
        val user = makeUser(email)

        val userService = mockk<UserService>()
        every { userService.findByExternalId(email) } returns user

        val service = AuthSecurityService(userService, objectMapper)
        val request = MockHttpServletRequest().apply {
            addHeader(AuthSecurityService.CF_AUTHORIZATION_HEADER, jwt)
        }

        service.resolveCurrentUser(request) shouldBe user
    }

    // -------------------------------------------------------------------------
    // resolveCurrentUser — x-forwarded-email fallback
    // -------------------------------------------------------------------------

    "resolveCurrentUser falls back to x-forwarded-email when CF header is absent" {
        val email = "carol@example.com"
        val user = makeUser(email)

        val userService = mockk<UserService>()
        every { userService.findByExternalId(email) } returns user

        val service = AuthSecurityService(userService, objectMapper)
        val request = MockHttpServletRequest().apply {
            addHeader(AuthSecurityService.X_FORWARDED_EMAIL_HEADER, email)
        }

        service.resolveCurrentUser(request) shouldBe user
    }

    // -------------------------------------------------------------------------
    // resolveCurrentUser — error cases
    // -------------------------------------------------------------------------

    "resolveCurrentUser throws 401 when no identity header is present" {
        val service = AuthSecurityService(mockk(), objectMapper)
        val request = MockHttpServletRequest()

        val ex = runCatching { service.resolveCurrentUser(request) }
            .exceptionOrNull() as? ResponseStatusException
        ex?.statusCode?.value() shouldBe HttpStatus.UNAUTHORIZED.value()
    }

    "resolveCurrentUser throws 404 when user is not registered" {
        val email = "unknown@example.com"
        val jwt = buildJwt(mapOf("email" to email))

        val userService = mockk<UserService>()
        every { userService.findByExternalId(email) } returns null

        val service = AuthSecurityService(userService, objectMapper)
        val request = MockHttpServletRequest().apply {
            addHeader(AuthSecurityService.CF_AUTHORIZATION_HEADER, jwt)
        }

        val ex = runCatching { service.resolveCurrentUser(request) }
            .exceptionOrNull() as? ResponseStatusException
        ex?.statusCode?.value() shouldBe HttpStatus.NOT_FOUND.value()
    }
})
