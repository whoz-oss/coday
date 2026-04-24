package io.whozoss.agentos.bootstrap

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import org.springframework.boot.ApplicationArguments

class BootstrapServiceImplSpec : StringSpec({

    val mockUserService = mockk<UserService>()
    val mockApplicationArguments = mockk<ApplicationArguments>()

    val bootstrapService = BootstrapServiceImpl(
        userService = mockUserService
    )

    beforeTest {
        clearAllMocks()
    }

    "should execute bootstrap on application startup" {
        // Given
        every { mockUserService.findAll() } returns emptyList()

        // When
        bootstrapService.run(mockApplicationArguments)

        // Then
        verify { mockUserService.findAll() }
    }

    "should log and skip bootstrap when users already exist" {
        // Given
        val existingUser = mockk<User>()
        every { mockUserService.findAll() } returns listOf(existingUser)

        // When
        bootstrapService.bootstrap()

        // Then
        verify { mockUserService.findAll() }
        // Le bootstrap devrait s'arrêter ici sans autres actions
    }

    "should log first user will be auto-promoted when no users exist" {
        // Given
        every { mockUserService.findAll() } returns emptyList()

        // When
        bootstrapService.bootstrap()

        // Then
        verify { mockUserService.findAll() }
        // Le log indiquera que le premier user sera auto-promu
    }

    "isBootstrapped should return true when users exist" {
        // Given
        val existingUser = mockk<User>()
        every { mockUserService.findAll() } returns listOf(existingUser)

        // When
        val result = bootstrapService.isBootstrapped()

        // Then
        result shouldBe true
    }

    "isBootstrapped should return false when no users exist" {
        // Given
        every { mockUserService.findAll() } returns emptyList()

        // When
        val result = bootstrapService.isBootstrapped()

        // Then
        result shouldBe false
    }

    "bootstrap should be idempotent - safe to call multiple times" {
        // Given
        val existingUser = mockk<User>()
        every { mockUserService.findAll() } returns listOf(existingUser)

        // When - appelé plusieurs fois
        bootstrapService.bootstrap()
        bootstrapService.bootstrap()
        bootstrapService.bootstrap()

        // Then - seulement vérifié, pas de modifications
        verify(exactly = 3) { mockUserService.findAll() }
    }
})