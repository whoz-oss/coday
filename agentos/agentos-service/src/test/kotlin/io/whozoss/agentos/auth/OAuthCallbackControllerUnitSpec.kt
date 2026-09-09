package io.whozoss.agentos.auth

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import org.springframework.http.HttpStatus
import java.util.UUID

/**
 * Unit tests for [OAuthCallbackController].
 *
 * Permission checks are declarative (`@PreAuthorize`) and only fire through Spring AOP.
 * Direct instantiation bypasses the proxy, so authorization is NOT tested here.
 */
class OAuthCallbackControllerUnitSpec : StringSpec({

    val pendingRegistry = mockk<OAuthPendingRegistry>()
    val userService = mockk<UserService>()
    val controller = OAuthCallbackController(pendingRegistry, userService)

    val aliceId = UUID.randomUUID()
    val alice = User(
        metadata = EntityMetadata(id = aliceId),
        externalId = "alice@example.com",
        email = "alice@example.com",
    )

    // -------------------------------------------------------------------------
    // handleCallback
    // -------------------------------------------------------------------------

    "handleCallback resolves pending flow and returns 200" {
        every { userService.getCurrentUser() } returns alice
        every { pendingRegistry.resolve("valid-state", "auth-code", aliceId) } returns true

        val response = controller.handleCallback(OAuthCallbackRequest(code = "auth-code", state = "valid-state"))

        response.statusCode shouldBe HttpStatus.OK
        verify(exactly = 1) { pendingRegistry.resolve("valid-state", "auth-code", aliceId) }
    }

    "handleCallback returns 400 when state is unknown" {
        every { userService.getCurrentUser() } returns alice
        every { pendingRegistry.resolve("unknown-state", "auth-code", aliceId) } returns false

        val response = controller.handleCallback(OAuthCallbackRequest(code = "auth-code", state = "unknown-state"))

        response.statusCode shouldBe HttpStatus.BAD_REQUEST
        verify(exactly = 1) { pendingRegistry.resolve("unknown-state", "auth-code", aliceId) }
    }
})
