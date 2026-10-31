package io.whozoss.agentos.user

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.transaction.PlatformTransactionManager
import java.util.UUID

/**
 * Unit tests for the rules of [PersonalNamespaceProvisioner]: what a user gets, idempotence, the
 * switch, and that no failure ever reaches the caller. The wiring against a real database, events and
 * transactions is covered by [PersonalNamespaceProvisionerIntegrationSpec].
 */
class PersonalNamespaceProvisionerSpec : StringSpec({
    val userId = UUID.randomUUID()
    val externalId = "alice@example.com"
    val key = "user:alice@example.com"

    class Fixture(
        enabled: Boolean = true,
    ) {
        val namespaces = mockk<NamespaceService>()
        val permissions = mockk<PermissionService>(relaxed = true)
        val agents = mockk<AgentConfigService>()
        val users = mockk<UserService>()
        val provisioner =
            PersonalNamespaceProvisioner(
                PersonalNamespaceProperties(enabled),
                namespaces,
                permissions,
                agents,
                users,
                mockk<PlatformTransactionManager>(relaxed = true),
            )
        val coday = AgentConfig(metadata = EntityMetadata(id = UUID.randomUUID()), namespaceId = null, name = "Coday")

        fun existingNamespace(defaultAgentName: String? = null) =
            Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "alice", externalId = key, defaultAgentName = defaultAgentName)
    }

    "the namespace is keyed by user:<externalId> and named after the local part of an email" {
        PersonalNamespaceProvisioner.namespaceExternalId("alice@example.com") shouldBe "user:alice@example.com"
        PersonalNamespaceProvisioner.namespaceName("alice@example.com") shouldBe "alice"
        PersonalNamespaceProvisioner.namespaceName("vincent") shouldBe "vincent"
        PersonalNamespaceProvisioner.namespaceName("@example.com") shouldBe "@example.com"
    }

    "creates the namespace, makes the user its ADMIN, deploys Coday and makes it the default agent" {
        val f = Fixture()
        every { f.namespaces.findByExternalId(key) } returns null
        val created = slot<Namespace>()
        every { f.namespaces.create(capture(created)) } answers { created.captured }
        every { f.agents.findByName(null, "Coday") } returns f.coday
        every { f.namespaces.deployAgents(any(), any()) } returns Unit
        val updated = slot<Namespace>()
        every { f.namespaces.update(capture(updated)) } answers { updated.captured }

        f.provisioner.ensure(userId, externalId) shouldBe true

        created.captured.externalId shouldBe key
        created.captured.name shouldBe "alice"
        created.captured.description shouldBe "Personal space of alice@example.com"
        verify {
            f.permissions.grantPermission(userId.toString(), EntityType.NAMESPACE, created.captured.id.toString(), PermissionRelation.ADMIN)
        }
        verify { f.namespaces.deployAgents(created.captured.id, listOf(f.coday.id)) }
        updated.captured.defaultAgentName shouldBe "Coday"
    }

    "is idempotent: an existing namespace is reused, not created again" {
        val f = Fixture()
        val existing = f.existingNamespace(defaultAgentName = "Coday")
        every { f.namespaces.findByExternalId(key) } returns existing
        every { f.agents.findByName(null, "Coday") } returns f.coday
        every { f.namespaces.deployAgents(any(), any()) } returns Unit

        f.provisioner.ensure(userId, externalId) shouldBe true
        f.provisioner.ensure(userId, externalId) shouldBe true

        verify(exactly = 0) { f.namespaces.create(any()) }
        verify(exactly = 0) { f.namespaces.update(any()) }
    }

    "only adds: a default agent the user chose is kept" {
        val f = Fixture()
        every { f.namespaces.findByExternalId(key) } returns f.existingNamespace(defaultAgentName = "mine")
        every { f.agents.findByName(null, "Coday") } returns f.coday
        every { f.namespaces.deployAgents(any(), any()) } returns Unit

        f.provisioner.ensure(userId, externalId)

        verify(exactly = 0) { f.namespaces.update(any()) }
    }

    "without a Coday agent the namespace and the grant are still provisioned" {
        val f = Fixture()
        val existing = f.existingNamespace()
        every { f.namespaces.findByExternalId(key) } returns existing
        every { f.agents.findByName(null, "Coday") } returns null

        f.provisioner.ensure(userId, externalId) shouldBe true

        verify { f.permissions.grantPermission(userId.toString(), EntityType.NAMESPACE, existing.id.toString(), PermissionRelation.ADMIN) }
        verify(exactly = 0) { f.namespaces.deployAgents(any(), any()) }
    }

    "a concurrent creation is not a failure: the namespace the winner made is used" {
        val f = Fixture()
        val winner = f.existingNamespace()
        every { f.namespaces.findByExternalId(key) } returnsMany listOf(null, winner)
        every { f.namespaces.create(any()) } throws ConflictException("already exists")
        every { f.agents.findByName(null, "Coday") } returns null

        f.provisioner.ensure(userId, externalId) shouldBe true

        verify { f.permissions.grantPermission(userId.toString(), EntityType.NAMESPACE, winner.id.toString(), PermissionRelation.ADMIN) }
    }

    "a failure is swallowed and the user is retried on the next login" {
        val f = Fixture()
        every { f.namespaces.findByExternalId(key) } throws RuntimeException("neo4j down")
        val user = User(metadata = EntityMetadata(id = userId), externalId = externalId, email = externalId)

        f.provisioner.ensure(userId, externalId) shouldBe false
        f.provisioner.ensureOnce(user)
        f.provisioner.ensureOnce(user)

        // Not cached as provisioned: every login tries again.
        verify(exactly = 3) { f.namespaces.findByExternalId(key) }
    }

    "a provisioned user costs nothing on the next logins" {
        val f = Fixture()
        every { f.namespaces.findByExternalId(key) } returns f.existingNamespace(defaultAgentName = "Coday")
        every { f.agents.findByName(null, "Coday") } returns f.coday
        every { f.namespaces.deployAgents(any(), any()) } returns Unit
        val user = User(metadata = EntityMetadata(id = userId), externalId = externalId, email = externalId)

        repeat(5) { f.provisioner.ensureOnce(user) }

        verify(exactly = 1) { f.namespaces.findByExternalId(key) }
    }

    "disabled, every entry point is a no-op" {
        val f = Fixture(enabled = false)
        val user = User(metadata = EntityMetadata(id = userId), externalId = externalId, email = externalId)

        f.provisioner.onUserCreated(UserCreatedEvent(userId, externalId))
        f.provisioner.ensureOnce(user)
        f.provisioner.ensureForAllUsers()
        f.provisioner.ensure(userId, externalId) shouldBe false

        verify(exactly = 0) { f.namespaces.findByExternalId(any()) }
        verify(exactly = 0) { f.users.findAll() }
    }

    "ensureForAllUsers provisions every user, bypassing the cache" {
        val f = Fixture()
        val bob = User(metadata = EntityMetadata(id = UUID.randomUUID()), externalId = "bob", email = "")
        val alice = User(metadata = EntityMetadata(id = userId), externalId = externalId, email = externalId)
        every { f.users.findAll() } returns listOf(alice, bob)
        every { f.namespaces.findByExternalId(any()) } answers { f.existingNamespace(defaultAgentName = "Coday") }
        every { f.agents.findByName(null, "Coday") } returns f.coday
        every { f.namespaces.deployAgents(any(), any()) } returns Unit
        f.provisioner.ensureOnce(alice)

        f.provisioner.ensureForAllUsers()

        verify(exactly = 1) { f.namespaces.findByExternalId("user:bob") }
        verify(exactly = 2) { f.namespaces.findByExternalId(key) }
    }
})
