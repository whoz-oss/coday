package io.whozoss.agentos.user

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.security.SecurityService
import java.util.UUID

/**
 * Unit tests for [UserServiceImpl].
 *
 * Covers:
 * - [UserServiceImpl.getCurrentUser] — delegates to SecurityService then resolveOrCreateByExternalId
 * - [UserServiceImpl.resolveOrCreateByExternalId] — returns existing user or auto-creates
 */
class UserServiceImplSpec : StringSpec({
    timeout = 5000

    fun makeUser(externalId: String) = User(
        metadata = EntityMetadata(id = UUID.randomUUID()),
        externalId = externalId,
    )

    // -------------------------------------------------------------------------
    // getCurrentUser
    // -------------------------------------------------------------------------

    "getCurrentUser resolves identity from SecurityService and returns the matching user" {
        val identity = "alice@example.com"
        val user = makeUser(identity)
        val securityService = mockk<SecurityService>()
        val userRepository = mockk<UserRepository>()
        every { securityService.resolveCurrentIdentity() } returns identity
        every { userRepository.findByExternalId(identity) } returns user

        val service = UserServiceImpl(userRepository, securityService)
        val result = service.getCurrentUser()

        result shouldBe user
        verify(exactly = 1) { securityService.resolveCurrentIdentity() }
        verify(exactly = 1) { userRepository.findByExternalId(identity) }
    }

    "getCurrentUser auto-creates user when identity is not yet registered" {
        val identity = "new@example.com"
        val createdUser = makeUser(identity)
        val securityService = mockk<SecurityService>()
        val userRepository = mockk<UserRepository>()
        every { securityService.resolveCurrentIdentity() } returns identity
        every { userRepository.findByExternalId(identity) } returns null
        every { userRepository.save(any()) } returns createdUser

        val service = UserServiceImpl(userRepository, securityService)
        val result = service.getCurrentUser()

        result shouldBe createdUser
        verify(exactly = 1) { userRepository.save(any()) }
    }

    // -------------------------------------------------------------------------
    // resolveOrCreateByExternalId
    // -------------------------------------------------------------------------

    "resolveOrCreateByExternalId returns existing user when found" {
        val externalId = "bob@example.com"
        val user = makeUser(externalId)
        val userRepository = mockk<UserRepository>()
        every { userRepository.findByExternalId(externalId) } returns user

        val service = UserServiceImpl(userRepository, mockk())
        val result = service.resolveOrCreateByExternalId(externalId)

        result shouldBe user
        verify(exactly = 0) { userRepository.save(any()) }
    }

    "resolveOrCreateByExternalId auto-creates user when not found" {
        val externalId = "carol@example.com"
        val createdUser = makeUser(externalId)
        val userRepository = mockk<UserRepository>()
        every { userRepository.findByExternalId(externalId) } returns null
        every { userRepository.save(any()) } returns createdUser

        val service = UserServiceImpl(userRepository, mockk())
        val result = service.resolveOrCreateByExternalId(externalId)

        result shouldBe createdUser
        // email and firstname are no longer seeded from externalId at creation time
        verify(exactly = 1) { userRepository.save(match { it.externalId == externalId && it.email == "" }) }
    }
})
