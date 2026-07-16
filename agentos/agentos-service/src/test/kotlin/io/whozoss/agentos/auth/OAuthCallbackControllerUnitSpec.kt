package io.whozoss.agentos.auth

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.http.HttpStatus

/**
 * Unit tests for [OAuthCallbackController].
 *
 * Permission checks are declarative (`@PreAuthorize`) and only fire through Spring AOP.
 * Direct instantiation bypasses the proxy, so authorization is NOT tested here.
 */
class OAuthCallbackControllerUnitSpec : StringSpec({

    val pendingRegistry = mockk<OAuthPendingRegistry>()
    val controller = OAuthCallbackController(pendingRegistry)

    // -------------------------------------------------------------------------
    // handleCallback
    // -------------------------------------------------------------------------

    "handleCallback resolves pending flow and returns 200" {
        every { pendingRegistry.resolve("valid-state", "auth-code") } returns true

        val response = controller.handleCallback(OAuthCallbackRequest(code = "auth-code", state = "valid-state"))

        response.statusCode shouldBe HttpStatus.OK
        verify(exactly = 1) { pendingRegistry.resolve("valid-state", "auth-code") }
    }

    "handleCallback returns 400 when state is unknown" {
        every { pendingRegistry.resolve("unknown-state", "auth-code") } returns false

        val response = controller.handleCallback(OAuthCallbackRequest(code = "auth-code", state = "unknown-state"))

        response.statusCode shouldBe HttpStatus.BAD_REQUEST
        verify(exactly = 1) { pendingRegistry.resolve("unknown-state", "auth-code") }
    }
})
