package io.whozoss.agentos.queryUser

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.integrationConfig.IntegrationConfigService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Unit tests for [QueryUserConfigSeeder]: the reconciliation of the platform-scoped
 * `QUERY_USER` configuration declared by [QueryUserConfigProperties.enabledByDefault].
 */
class QueryUserConfigSeederSpec : StringSpec({

    fun seeder(
        enabledByDefault: Boolean,
        service: IntegrationConfigService,
    ) = QueryUserConfigSeeder(
        properties = QueryUserConfigProperties(enabledByDefault = enabledByDefault),
        integrationConfigService = service,
    )

    fun existingConfig(autoGrant: Boolean = true) = IntegrationConfig(
        metadata = EntityMetadata(id = UUID.randomUUID()),
        namespaceId = null,
        userId = null,
        name = QueryUserConfigSeeder.DEFAULT_CONFIG_NAME,
        integrationType = QueryUserToolPlugin.INTEGRATION_TYPE,
        autoGrant = autoGrant,
    )

    // -------------------------------------------------------------------------
    // enabledByDefault = true
    // -------------------------------------------------------------------------

    "seeds a platform-scoped auto-granted config allowing all three question types when absent" {
        val service = mockk<IntegrationConfigService>()
        every { service.findByTriple(null, null, QueryUserConfigSeeder.DEFAULT_CONFIG_NAME) } returns null
        val created = slot<IntegrationConfig>()
        every { service.create(capture(created)) } answers { created.captured }

        seeder(enabledByDefault = true, service = service).seed()

        created.captured.namespaceId shouldBe null
        created.captured.userId shouldBe null
        created.captured.name shouldBe QueryUserConfigSeeder.DEFAULT_CONFIG_NAME
        created.captured.integrationType shouldBe QueryUserToolPlugin.INTEGRATION_TYPE
        created.captured.autoGrant shouldBe true
        created.captured.parameters.shouldNotBeNull()
        created.captured.parameters!!["allowedQuestionTypes"].map { it.asText() } shouldBe
            listOf("FREE_TEXT", "SINGLE_CHOICE", "OPEN_CHOICE")
    }

    "declares existence, not content: an existing config is never rewritten" {
        // The administrator owns the row once it exists, however far it has drifted --
        // here autoGrant has been turned off deliberately and must stay off.
        val service = mockk<IntegrationConfigService>()
        every { service.findByTriple(null, null, QueryUserConfigSeeder.DEFAULT_CONFIG_NAME) } returns existingConfig(autoGrant = false)

        seeder(enabledByDefault = true, service = service).seed()

        verify(exactly = 0) { service.create(any()) }
        verify(exactly = 0) { service.update(any()) }
    }

    "a concurrent create by another instance is not a startup failure" {
        // Several instances booting at once all see the row missing; the tripleKey unique
        // constraint elects one winner and the others get a 409. Losing is the expected outcome.
        val service = mockk<IntegrationConfigService>()
        every { service.findByTriple(null, null, QueryUserConfigSeeder.DEFAULT_CONFIG_NAME) } returns null
        every { service.create(any()) } throws ResponseStatusException(HttpStatus.CONFLICT, "already exists")

        seeder(enabledByDefault = true, service = service).seed()
    }

    "a non-conflict failure still propagates" {
        val service = mockk<IntegrationConfigService>()
        every { service.findByTriple(null, null, QueryUserConfigSeeder.DEFAULT_CONFIG_NAME) } returns null
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
        every { service.findByTriple(null, null, QueryUserConfigSeeder.DEFAULT_CONFIG_NAME) } returns null

        seeder(enabledByDefault = false, service = service).seed()

        verify(exactly = 0) { service.create(any()) }
    }

    "create-only: disabling afterwards never deletes an already-seeded config" {
        // The asymmetry is deliberate -- deleting persisted data on the strength of a boolean
        // would be a worse surprise. A warning names the remedy instead.
        val service = mockk<IntegrationConfigService>()
        every { service.findByTriple(null, null, QueryUserConfigSeeder.DEFAULT_CONFIG_NAME) } returns existingConfig(autoGrant = true)

        seeder(enabledByDefault = false, service = service).seed()

        verify(exactly = 0) { service.delete(any()) }
        verify(exactly = 0) { service.update(any()) }
        verify(exactly = 0) { service.create(any()) }
    }
})
