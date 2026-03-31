package io.whozoss.agentos.security

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService

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

        val result = LocalSecurityService(userService).resolveCurrentUser()

        result shouldBe existingUser
        verify(exactly = 0) { userService.create(any()) }
    }

    "resolveCurrentUser auto-creates user on first access when not found" {
        val userService = mockk<UserService>()
        every { userService.findByExternalId(osUsername) } returns null
        val createdUser = makeUser(osUsername)
        every { userService.create(any()) } returns createdUser

        val result = LocalSecurityService(userService).resolveCurrentUser()

        result shouldBe createdUser
        verify(exactly = 1) { userService.create(any()) }
    }

    "FORBIDDEN_USERNAMES contains expected system accounts" {
        val forbidden = LocalSecurityService.FORBIDDEN_USERNAMES
        listOf("root", "admin", "daemon", "nobody", "www-data", "docker").forEach {
            (it in forbidden) shouldBe true
        }
    }
})
