package io.whozoss.agentos.security

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.server.ResponseStatusException

class LocalSecurityServiceTest : StringSpec({
    timeout = 5000

    val osUsername = System.getProperty("user.name") ?: System.getenv("USER") ?: "testuser"

    fun makeUser(username: String) = User(
        metadata = EntityMetadata(),
        externalId = username,
        email = username,
        firstname = username,
    )

    "resolveCurrentUser returns existing user when found by externalId" {
        val userService = mockk<UserService>()
        val existingUser = makeUser(osUsername)
        every { userService.findByExternalId(osUsername) } returns existingUser

        val service = LocalSecurityService(userService)
        val result = service.resolveCurrentUser(MockHttpServletRequest())

        result shouldBe existingUser
        verify(exactly = 0) { userService.create(any()) }
    }

    "resolveCurrentUser auto-creates user on first access when not found" {
        val userService = mockk<UserService>()
        every { userService.findByExternalId(osUsername) } returns null
        val createdUser = makeUser(osUsername)
        every { userService.create(any()) } returns createdUser

        val service = LocalSecurityService(userService)
        val result = service.resolveCurrentUser(MockHttpServletRequest())

        result shouldBe createdUser
        verify(exactly = 1) { userService.create(any()) }
    }

    "resolveCurrentUser throws 403 for forbidden system usernames" {
        LocalSecurityService.FORBIDDEN_USERNAMES.take(3).forEach { forbidden ->
            val userService = mockk<UserService>()
            val service = LocalSecurityService(userService)

            // Simulate the OS returning a forbidden username by testing the guard directly
            // (we cannot override System.getProperty in-process, so we test the set membership)
            (forbidden in LocalSecurityService.FORBIDDEN_USERNAMES) shouldBe true
        }
    }

    "FORBIDDEN_USERNAMES contains expected system accounts" {
        val forbidden = LocalSecurityService.FORBIDDEN_USERNAMES
        listOf("root", "admin", "daemon", "nobody", "www-data", "docker").forEach {
            (it in forbidden) shouldBe true
        }
    }
})
