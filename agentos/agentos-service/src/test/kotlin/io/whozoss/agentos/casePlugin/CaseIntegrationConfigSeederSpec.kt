package io.whozoss.agentos.casePlugin

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
import io.whozoss.agentos.integrationConfig.IntegrationsProperties
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Unit tests for [CaseIntegrationConfigSeeder]: the reconciliation of the platform-scoped `CASE`
 * configuration declared by [CasePluginConfigProperties.enabledByDefault].
 *
 * The seeder writes through [IntegrationConfigService.unsecuredCreate], not the authorized
 * `*ForCaller` surface: it runs at startup, on behalf of no user, so there is no caller whose
 * privilege could be checked. The `unsecured` prefix is what makes that visible here and at the
 * call site. These expectations name that method deliberately — stubbing or verifying the
 * inherited `create` would pass vacuously while asserting nothing about what the seeder does.
 */
class CaseIntegrationConfigSeederSpec :
    StringSpec({

        val name = CaseIntegrationConfigSeeder.DEFAULT_CONFIG_NAME

        fun seeder(
            enabledByDefault: Boolean,
            service: IntegrationConfigService,
        ) = CaseIntegrationConfigSeeder(
            properties = CasePluginConfigProperties(enabledByDefault = enabledByDefault),
            integrationConfigService = service,
        )

        fun existingConfig(autoGrant: Boolean = false) =
            IntegrationConfig(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                namespaceId = null,
                userId = null,
                name = name,
                integrationType = CaseToolPlugin.INTEGRATION_TYPE,
                autoGrant = autoGrant,
            )

        // -------------------------------------------------------------------------
        // enabledByDefault = true
        // -------------------------------------------------------------------------

        "seeds a platform-scoped CASE config, not auto-granted and without parameters, when absent" {
            val service = mockk<IntegrationConfigService>()
            every { service.findByTriple(null, null, name) } returns null
            val created = slot<IntegrationConfig>()
            every { service.unsecuredCreate(capture(created)) } answers { created.captured }

            seeder(enabledByDefault = true, service = service).seed()

            created.captured.namespaceId shouldBe null
            created.captured.userId shouldBe null
            created.captured.name shouldBe "CASE"
            created.captured.integrationType shouldBe CaseToolPlugin.INTEGRATION_TYPE
            // FreeCaseStarter starts work: it is never handed to every agent without being named
            created.captured.autoGrant shouldBe false
            created.captured.parameters shouldBe null
        }

        "the seeded config can be saved: it does not ask for what the auto-grant policy refuses" {
            // CASE is not auto-grantable; a seeder that set autoGrant=true would fail every startup with a 422.
            IntegrationsProperties().isAutoGrantable(CaseToolPlugin.INTEGRATION_TYPE) shouldBe false

            val service = mockk<IntegrationConfigService>()
            every { service.findByTriple(null, null, name) } returns null
            val created = slot<IntegrationConfig>()
            every { service.unsecuredCreate(capture(created)) } answers { created.captured }

            seeder(enabledByDefault = true, service = service).seed()

            created.captured.autoGrant shouldBe false
        }

        "declares existence, not content: an existing config is never rewritten" {
            // The administrator owns the row once it exists, however far it has drifted.
            val service = mockk<IntegrationConfigService>()
            every { service.findByTriple(null, null, name) } returns existingConfig()

            seeder(enabledByDefault = true, service = service).seed()

            verify(exactly = 0) { service.unsecuredCreate(any()) }
            verify(exactly = 0) { service.unsecuredUpdate(any()) }
            verify(exactly = 0) { service.create(any()) }
            verify(exactly = 0) { service.update(any()) }
        }

        "a concurrent create by another instance is not a startup failure" {
            // Several instances booting at once all see the row missing; the tripleKey unique
            // constraint elects one winner and the others get a 409. Losing is the expected outcome.
            val service = mockk<IntegrationConfigService>()
            every { service.findByTriple(null, null, name) } returns null
            every { service.unsecuredCreate(any()) } throws ConflictException("already exists")

            seeder(enabledByDefault = true, service = service).seed()
        }

        "a non-conflict failure still propagates" {
            val service = mockk<IntegrationConfigService>()
            every { service.findByTriple(null, null, name) } returns null
            every { service.unsecuredCreate(any()) } throws ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "bad")

            shouldThrow<ResponseStatusException> {
                seeder(enabledByDefault = true, service = service).seed()
            }
        }

        // -------------------------------------------------------------------------
        // enabledByDefault = false
        // -------------------------------------------------------------------------

        "seeds nothing when disabled" {
            val service = mockk<IntegrationConfigService>()
            every { service.findByTriple(null, null, name) } returns null

            seeder(enabledByDefault = false, service = service).seed()

            verify(exactly = 0) { service.unsecuredCreate(any()) }
            verify(exactly = 0) { service.create(any()) }
        }

        "create-only: disabling afterwards never deletes an already-seeded config" {
            val service = mockk<IntegrationConfigService>()
            every { service.findByTriple(null, null, name) } returns existingConfig()

            seeder(enabledByDefault = false, service = service).seed()

            verify(exactly = 0) { service.delete(any()) }
            verify(exactly = 0) { service.unsecuredUpdate(any()) }
            verify(exactly = 0) { service.unsecuredCreate(any()) }
            verify(exactly = 0) { service.update(any()) }
            verify(exactly = 0) { service.create(any()) }
        }

        // -------------------------------------------------------------------------
        // Contract
        // -------------------------------------------------------------------------

        "the config name is the prefix of the tool names the LLM sees" {
            // Renaming it silently detaches every override and agent binding: the name is a contract.
            name shouldBe "CASE"
            "${name}__ReadCase" shouldBe ReadCaseTool.buildToolName(name)
            "${name}__FreeCaseStarter" shouldBe FreeCaseStarterTool.buildToolName(name)
            "${name}__FreeCaseStarterForUser" shouldBe FreeCaseStarterForUserTool.buildToolName(name)
        }
    })
