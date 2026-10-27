package io.whozoss.agentos.selfConfig

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.integrationConfig.IntegrationConfigService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Unit tests for [SelfConfigIntegrationConfigSeeder].
 *
 * The seeder writes through [IntegrationConfigService.unsecuredCreate], not the authorized
 * `*ForCaller` surface: it runs at startup, on behalf of no user, so there is no caller whose
 * privilege could be checked. These expectations name that method deliberately -- stubbing the
 * inherited `create` would pass vacuously while asserting nothing about what the seeder does.
 *
 * Mirrors [io.whozoss.agentos.userGroup.UserGroupIntegrationConfigSeederSpec]; the one assertion
 * specific to this integration is that the seeded row is never auto-granted.
 */
class SelfConfigIntegrationConfigSeederSpec : StringSpec({

    fun seeder(
        enabledByDefault: Boolean,
        service: IntegrationConfigService,
    ) = SelfConfigIntegrationConfigSeeder(
        properties = SelfConfigProperties(enabledByDefault = enabledByDefault),
        integrationConfigService = service,
    )

    fun existingConfig(autoGrant: Boolean = false) =
        IntegrationConfig(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            namespaceId = null,
            userId = null,
            name = SelfConfigIntegrationConfigSeeder.DEFAULT_CONFIG_NAME,
            integrationType = SelfConfigToolPlugin.INTEGRATION_TYPE,
            autoGrant = autoGrant,
        )

    "seeds a platform-scoped config, never auto-granted, when absent" {
        val service = mockk<IntegrationConfigService>()
        every {
            service.findByTriple(null, null, SelfConfigIntegrationConfigSeeder.DEFAULT_CONFIG_NAME)
        } returns null
        val created = slot<IntegrationConfig>()
        every { service.unsecuredCreate(capture(created)) } answers { created.captured }

        seeder(enabledByDefault = true, service = service).seed()

        verify(exactly = 1) { service.unsecuredCreate(any()) }
        created.captured.namespaceId shouldBe null
        created.captured.userId shouldBe null
        created.captured.name shouldBe "SELF_CONFIG"
        created.captured.integrationType shouldBe "SELF_CONFIG"
        created.captured.parameters shouldBe null
        // An agent able to rewrite configuration is an explicit assignment, never a blanket grant.
        created.captured.autoGrant shouldBe false
    }

    "declares existence, not content: an existing config is never rewritten" {
        val service = mockk<IntegrationConfigService>()
        every {
            service.findByTriple(null, null, SelfConfigIntegrationConfigSeeder.DEFAULT_CONFIG_NAME)
        } returns existingConfig()

        seeder(enabledByDefault = true, service = service).seed()

        verify(exactly = 0) { service.unsecuredCreate(any()) }
        verify(exactly = 0) { service.unsecuredUpdate(any()) }
        verify(exactly = 0) { service.create(any()) }
        verify(exactly = 0) { service.update(any()) }
    }

    "a concurrent create by another instance is not a startup failure" {
        val service = mockk<IntegrationConfigService>()
        every {
            service.findByTriple(null, null, SelfConfigIntegrationConfigSeeder.DEFAULT_CONFIG_NAME)
        } returns null
        every { service.unsecuredCreate(any()) } throws ConflictException("already exists")

        seeder(enabledByDefault = true, service = service).seed()

        verify(exactly = 1) { service.unsecuredCreate(any()) }
    }

    "a non-conflict failure still propagates" {
        val service = mockk<IntegrationConfigService>()
        every {
            service.findByTriple(null, null, SelfConfigIntegrationConfigSeeder.DEFAULT_CONFIG_NAME)
        } returns null
        every { service.unsecuredCreate(any()) } throws ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "bad")

        shouldThrow<ResponseStatusException> {
            seeder(enabledByDefault = true, service = service).seed()
        }
    }

    "seeds nothing when disabled" {
        val service = mockk<IntegrationConfigService>()
        every {
            service.findByTriple(null, null, SelfConfigIntegrationConfigSeeder.DEFAULT_CONFIG_NAME)
        } returns null

        seeder(enabledByDefault = false, service = service).seed()

        verify(exactly = 0) { service.unsecuredCreate(any()) }
        verify(exactly = 0) { service.create(any()) }
    }

    "create-only: disabling afterwards never deletes an already-seeded config" {
        val service = mockk<IntegrationConfigService>()
        every {
            service.findByTriple(null, null, SelfConfigIntegrationConfigSeeder.DEFAULT_CONFIG_NAME)
        } returns existingConfig()

        seeder(enabledByDefault = false, service = service).seed()

        verify(exactly = 0) { service.delete(any()) }
        verify(exactly = 0) { service.unsecuredCreate(any()) }
        verify(exactly = 0) { service.unsecuredUpdate(any()) }
        verify(exactly = 0) { service.update(any()) }
        verify(exactly = 0) { service.create(any()) }
    }
})
