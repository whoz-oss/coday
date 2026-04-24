package io.whozoss.agentos.user

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.*
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.security.SecurityService
import java.util.UUID

/**
 * Tests spécifiques pour Story 1.5 : User Identity Resolution et Auto-Creation
 * Vérifie que le premier utilisateur devient super-admin et les suivants non.
 */
class UserServiceIdentityResolutionSpec : StringSpec({

    val mockUserRepository = mockk<UserRepository>()
    val mockSecurityService = mockk<SecurityService>()

    val userService = UserServiceImpl(
        userRepository = mockUserRepository,
        securityService = mockSecurityService
    )

    beforeTest {
        clearAllMocks()
    }

    "premier utilisateur créé doit être super-admin" {
        // Given - aucun utilisateur dans le système
        val externalId = "premier.user@example.com"
        every { mockUserRepository.findByExternalId(externalId) } returns null
        every { mockUserRepository.count() } returns 0L
        every { mockUserRepository.save(any()) } answers { firstArg() }

        // When
        val createdUser = userService.resolveOrCreateByExternalId(externalId)

        // Then
        createdUser shouldNotBe null
        createdUser.isAdmin shouldBe true
        createdUser.externalId shouldBe externalId
        createdUser.email shouldBe externalId // email extrait car contient @

        verify {
            mockUserRepository.count()
            mockUserRepository.save(match { user ->
                user.isAdmin == true &&
                user.externalId == externalId &&
                user.email == externalId
            })
        }
    }

    "utilisateurs suivants ne doivent pas être super-admin" {
        // Given - un utilisateur existe déjà
        val externalId = "deuxieme.user@example.com"
        every { mockUserRepository.findByExternalId(externalId) } returns null
        every { mockUserRepository.count() } returns 1L // Un user existe déjà
        every { mockUserRepository.save(any()) } answers { firstArg() }

        // When
        val createdUser = userService.resolveOrCreateByExternalId(externalId)

        // Then
        createdUser shouldNotBe null
        createdUser.isAdmin shouldBe false
        createdUser.externalId shouldBe externalId
        createdUser.email shouldBe externalId

        verify {
            mockUserRepository.count()
            mockUserRepository.save(match { user ->
                user.isAdmin == false &&
                user.externalId == externalId
            })
        }
    }

    "utilisateur existant ne doit pas être modifié" {
        // Given - l'utilisateur existe déjà
        val existingUser = User(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            externalId = "existing@example.com",
            email = "existing@example.com",
            isAdmin = false
        )
        every { mockUserRepository.findByExternalId(existingUser.externalId) } returns existingUser

        // When
        val resolvedUser = userService.resolveOrCreateByExternalId(existingUser.externalId)

        // Then
        resolvedUser shouldBe existingUser
        verify(exactly = 0) { mockUserRepository.count() }
        verify(exactly = 0) { mockUserRepository.save(any()) }
    }

    "email doit être extrait de externalId si c'est une adresse email" {
        // Given
        val emailExtId = "user@company.com"
        every { mockUserRepository.findByExternalId(emailExtId) } returns null
        every { mockUserRepository.count() } returns 5L
        every { mockUserRepository.save(any()) } answers { firstArg() }

        // When
        val createdUser = userService.resolveOrCreateByExternalId(emailExtId)

        // Then
        createdUser.email shouldBe emailExtId
        createdUser.externalId shouldBe emailExtId
    }

    "email doit être vide si externalId n'est pas une adresse email" {
        // Given - OS username par exemple
        val username = "john.doe"
        every { mockUserRepository.findByExternalId(username) } returns null
        every { mockUserRepository.count() } returns 5L
        every { mockUserRepository.save(any()) } answers { firstArg() }

        // When
        val createdUser = userService.resolveOrCreateByExternalId(username)

        // Then
        createdUser.email shouldBe ""
        createdUser.externalId shouldBe username
    }

    "getCurrentUser doit utiliser SecurityService et auto-créer si nécessaire" {
        // Given
        val identity = "current.user@example.com"
        every { mockSecurityService.resolveCurrentIdentity() } returns identity
        every { mockUserRepository.findByExternalId(identity) } returns null
        every { mockUserRepository.count() } returns 0L
        every { mockUserRepository.save(any()) } answers { firstArg() }

        // When
        val currentUser = userService.getCurrentUser()

        // Then
        currentUser shouldNotBe null
        currentUser.externalId shouldBe identity
        currentUser.isAdmin shouldBe true // Premier user

        verify {
            mockSecurityService.resolveCurrentIdentity()
            mockUserRepository.findByExternalId(identity)
            mockUserRepository.count()
        }
    }

    "resolveOrCreateByExternalId doit être idempotent" {
        // Given
        val externalId = "idempotent@example.com"
        val existingUser = User(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            externalId = externalId,
            email = externalId,
            isAdmin = true
        )

        // Premier appel - création
        every { mockUserRepository.findByExternalId(externalId) } returns null andThen existingUser
        every { mockUserRepository.count() } returns 0L
        every { mockUserRepository.save(any()) } returns existingUser

        // When - appelé deux fois
        val firstCall = userService.resolveOrCreateByExternalId(externalId)
        val secondCall = userService.resolveOrCreateByExternalId(externalId)

        // Then
        firstCall shouldBe existingUser
        secondCall shouldBe existingUser

        // Vérifie que save n'est appelé qu'une fois
        verify(exactly = 1) { mockUserRepository.save(any()) }
        verify(exactly = 2) { mockUserRepository.findByExternalId(externalId) }
    }
})