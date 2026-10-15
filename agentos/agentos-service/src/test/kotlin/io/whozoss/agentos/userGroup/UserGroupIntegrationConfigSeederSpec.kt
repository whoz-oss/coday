package io.whozoss.agentos.userGroup

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

class UserGroupIntegrationConfigSeederSpec :
    StringSpec({

        fun seeder(
            enabledByDefault: Boolean,
            service: IntegrationConfigService,
        ) = UserGroupIntegrationConfigSeeder(
            properties = UserGroupToolConfigProperties(enabledByDefault = enabledByDefault),
            integrationConfigService = service,
        )

        fun existingConfig(autoGrant: Boolean = true) =
            IntegrationConfig(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                namespaceId = null,
                userId = null,
                name = UserGroupIntegrationConfigSeeder.DEFAULT_CONFIG_NAME,
                integrationType = UserGroupToolPlugin.INTEGRATION_TYPE,
                autoGrant = autoGrant,
            )

        // -------------------------------------------------------------------------
        // enabledByDefault = true
        // -------------------------------------------------------------------------

        "seeds a platform-scoped auto-granted config when absent" {
            val service = mockk<IntegrationConfigService>()
            every {
                service.findByTriple(null, null, UserGroupIntegrationConfigSeeder.DEFAULT_CONFIG_NAME)
            } returns null
            val created = slot<IntegrationConfig>()
            every { service.create(capture(created)) } answers { created.captured }

            seeder(enabledByDefault = true, service = service).seed()

            created.captured.namespaceId shouldBe null
            created.captured.userId shouldBe null
            created.captured.name shouldBe "USER_GROUP"
            created.captured.integrationType shouldBe "USER_GROUP"
            created.captured.autoGrant shouldBe true
            // The integration requires no parameters
            created.captured.parameters shouldBe null
        }

        "declares existence, not content: an existing config is never rewritten" {
            val service = mockk<IntegrationConfigService>()
            every {
                service.findByTriple(null, null, UserGroupIntegrationConfigSeeder.DEFAULT_CONFIG_NAME)
            } returns existingConfig(autoGrant = false)

            seeder(enabledByDefault = true, service = service).seed()

            verify(exactly = 0) { service.create(any()) }
            verify(exactly = 0) { service.update(any()) }
        }

        "a concurrent create by another instance is not a startup failure" {
            val service = mockk<IntegrationConfigService>()
            every {
                service.findByTriple(null, null, UserGroupIntegrationConfigSeeder.DEFAULT_CONFIG_NAME)
            } returns null
            every { service.create(any()) } throws ConflictException("already exists")

            seeder(enabledByDefault = true, service = service).seed()
        }

        "a non-conflict failure still propagates" {
            val service = mockk<IntegrationConfigService>()
            every {
                service.findByTriple(null, null, UserGroupIntegrationConfigSeeder.DEFAULT_CONFIG_NAME)
            } returns null
            every { service.create(any()) } throws ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "bad")

            shouldThrow<ResponseStatusException> {
                seeder(enabledByDefault = true, service = service).seed()
            }
        }

        // -------------------------------------------------------------------------
        // enabledByDefault = false
        // -------------------------------------------------------------------------

        "seeds nothing when disabled" {
            val service = mockk<IntegrationConfigService>()
            every {
                service.findByTriple(null, null, UserGroupIntegrationConfigSeeder.DEFAULT_CONFIG_NAME)
            } returns null

            seeder(enabledByDefault = false, service = service).seed()

            verify(exactly = 0) { service.create(any()) }
        }

        "create-only: disabling afterwards never deletes an already-seeded config" {
            val service = mockk<IntegrationConfigService>()
            every {
                service.findByTriple(null, null, UserGroupIntegrationConfigSeeder.DEFAULT_CONFIG_NAME)
            } returns existingConfig(autoGrant = true)

            seeder(enabledByDefault = false, service = service).seed()

            verify(exactly = 0) { service.delete(any()) }
            verify(exactly = 0) { service.update(any()) }
            verify(exactly = 0) { service.create(any()) }
        }
    })
