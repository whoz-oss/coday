package io.whozoss.agentos.user

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.codayAgent.CodayAgentSeeder
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Unit tests for the rules of [PersonalNamespaceProvisioner]: what a user gets, idempotence, the
 * switch, the migration of the legacy platform `Coday`, and that no failure ever reaches the caller. The
 * wiring against a real database, events and transactions is covered by
 * [PersonalNamespaceProvisionerIntegrationSpec].
 */
class PersonalNamespaceProvisionerSpec :
    StringSpec({
        val userId = UUID.randomUUID()
        val externalId = "alice@example.com"
        val key = "user:alice@example.com"

        /** Records begin/commit/rollback in [events], to check what runs inside or outside which transaction. */
        class RecordingTransactionManager(
            private val events: MutableList<String>,
        ) : AbstractPlatformTransactionManager() {
            override fun doGetTransaction(): Any = Any()

            override fun doBegin(
                transaction: Any,
                definition: TransactionDefinition,
            ) {
                events += "begin"
            }

            override fun doCommit(status: DefaultTransactionStatus) {
                events += "commit"
            }

            override fun doRollback(status: DefaultTransactionStatus) {
                events += "rollback"
            }
        }

        class Fixture(
            enabled: Boolean = true,
            transactionManager: PlatformTransactionManager = mockk<PlatformTransactionManager>(relaxed = true),
        ) {
            val namespaces = mockk<NamespaceService>()
            val permissions = mockk<PermissionService>(relaxed = true)
            val agents = mockk<AgentConfigService>()
            val seeder = mockk<CodayAgentSeeder>()
            val users = mockk<UserService>()
            val provisioner =
                PersonalNamespaceProvisioner(
                    PersonalNamespaceProperties(enabled),
                    namespaces,
                    permissions,
                    agents,
                    seeder,
                    users,
                    transactionManager,
                )

            /** The `Coday` the namespace owns, as returned by the seeder. */
            val coday = AgentConfig(metadata = EntityMetadata(id = UUID.randomUUID()), namespaceId = UUID.randomUUID(), name = "Coday")

            /** The legacy platform `Coday` of earlier versions. */
            val legacy = AgentConfig(metadata = EntityMetadata(id = UUID.randomUUID()), namespaceId = null, name = "Coday")

            /** The seeder owns a `Coday` for any namespace, and no platform agent exists. */
            fun codayEnsured() {
                every { seeder.ensureIn(any()) } returns coday
                every { agents.findByNamespace(null, true) } returns emptyList()
            }

            /** The default agent is disabled: the seeder returns nothing. */
            fun codayDisabled() {
                every { seeder.ensureIn(any()) } returns null
            }

            fun existingNamespace(defaultAgentName: String? = null) =
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "alice", externalId = key, defaultAgentName = defaultAgentName)
        }

        "the namespace is keyed by user:<externalId> and named after the local part of an email" {
            PersonalNamespaceProvisioner.namespaceExternalId("alice@example.com") shouldBe "user:alice@example.com"
            PersonalNamespaceProvisioner.namespaceName("alice@example.com") shouldBe "alice"
            PersonalNamespaceProvisioner.namespaceName("vincent") shouldBe "vincent"
            PersonalNamespaceProvisioner.namespaceName("@example.com") shouldBe "@example.com"
        }

        "creates the namespace, makes the user its ADMIN, ensures its Coday, deploys it and makes it the default agent" {
            val f = Fixture()
            every { f.namespaces.findByExternalId(key) } returns null
            val created = slot<Namespace>()
            every { f.namespaces.create(capture(created)) } answers { created.captured }
            f.codayEnsured()
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
            verify { f.seeder.ensureIn(created.captured.id) }
            verify { f.namespaces.deployAgents(created.captured.id, listOf(f.coday.id)) }
            updated.captured.defaultAgentName shouldBe "Coday"
        }

        "is idempotent: an existing namespace is reused, not created again" {
            val f = Fixture()
            val existing = f.existingNamespace(defaultAgentName = "Coday")
            every { f.namespaces.findByExternalId(key) } returns existing
            f.codayEnsured()
            every { f.namespaces.deployAgents(any(), any()) } returns Unit

            f.provisioner.ensure(userId, externalId) shouldBe true
            f.provisioner.ensure(userId, externalId) shouldBe true

            verify(exactly = 0) { f.namespaces.create(any()) }
            verify(exactly = 0) { f.namespaces.update(any()) }
        }

        "only adds: a default agent the user chose is kept" {
            val f = Fixture()
            every { f.namespaces.findByExternalId(key) } returns f.existingNamespace(defaultAgentName = "mine")
            f.codayEnsured()
            every { f.namespaces.deployAgents(any(), any()) } returns Unit

            f.provisioner.ensure(userId, externalId)

            verify(exactly = 0) { f.namespaces.update(any()) }
        }

        "without a Coday agent (default agent disabled) the namespace and the grant are still provisioned" {
            val f = Fixture()
            val existing = f.existingNamespace()
            every { f.namespaces.findByExternalId(key) } returns existing
            f.codayDisabled()

            f.provisioner.ensure(userId, externalId) shouldBe true

            verify { f.permissions.grantPermission(userId.toString(), EntityType.NAMESPACE, existing.id.toString(), PermissionRelation.ADMIN) }
            verify(exactly = 0) { f.namespaces.deployAgents(any(), any()) }
            verify(exactly = 0) { f.namespaces.undeployAgents(any(), any()) }
            verify(exactly = 0) { f.namespaces.update(any()) }
        }

        "migration: the legacy platform Coday is undeployed from the personal namespace once it owns its Coday" {
            val f = Fixture()
            val existing = f.existingNamespace(defaultAgentName = "Coday")
            every { f.namespaces.findByExternalId(key) } returns existing
            every { f.seeder.ensureIn(existing.id) } returns f.coday
            every { f.agents.findByNamespace(null, true) } returns listOf(f.legacy, AgentConfig(metadata = EntityMetadata(id = UUID.randomUUID()), namespaceId = null, name = "Other"))
            every { f.namespaces.deployAgents(any(), any()) } returns Unit
            every { f.namespaces.undeployAgents(any(), any()) } returns Unit

            f.provisioner.ensure(userId, externalId) shouldBe true

            verify { f.namespaces.deployAgents(existing.id, listOf(f.coday.id)) }
            // Only the legacy Coday, and only its deployment on this namespace: nothing is deleted or rewritten.
            verify(exactly = 1) { f.namespaces.undeployAgents(existing.id, listOf(f.legacy.id)) }
            verify(exactly = 0) { f.agents.delete(any()) }
            verify(exactly = 0) { f.agents.update(any()) }
            verify(exactly = 0) { f.agents.findByName(any(), any()) }
        }

        "migration: no platform Coday means nothing to undeploy" {
            val f = Fixture()
            every { f.namespaces.findByExternalId(key) } returns f.existingNamespace(defaultAgentName = "Coday")
            f.codayEnsured()
            every { f.namespaces.deployAgents(any(), any()) } returns Unit

            f.provisioner.ensure(userId, externalId) shouldBe true

            verify(exactly = 0) { f.namespaces.undeployAgents(any(), any()) }
        }

        "migration: the legacy Coday stays deployed when the namespace owns no Coday (default agent disabled)" {
            val f = Fixture()
            every { f.namespaces.findByExternalId(key) } returns f.existingNamespace(defaultAgentName = "Coday")
            f.codayDisabled()
            every { f.agents.findByNamespace(null, true) } returns listOf(f.legacy)

            f.provisioner.ensure(userId, externalId) shouldBe true

            verify(exactly = 0) { f.namespaces.undeployAgents(any(), any()) }
            verify(exactly = 0) { f.agents.findByNamespace(null, any()) }
        }

        "a concurrent creation is not a failure: the namespace the winner made is used" {
            val f = Fixture()
            val winner = f.existingNamespace()
            every { f.namespaces.findByExternalId(key) } returnsMany listOf(null, winner)
            every { f.namespaces.create(any()) } throws ConflictException("already exists")
            f.codayDisabled()

            f.provisioner.ensure(userId, externalId) shouldBe true

            verify { f.permissions.grantPermission(userId.toString(), EntityType.NAMESPACE, winner.id.toString(), PermissionRelation.ADMIN) }
        }

        "a concurrent creation is recovered by a read made after the creation transaction rolled back" {
            val events = Collections.synchronizedList(mutableListOf<String>())
            val f = Fixture(transactionManager = RecordingTransactionManager(events))
            val winner = f.existingNamespace()
            every { f.namespaces.findByExternalId(key) } answers {
                events += "find"
                if (events.count { it == "find" } == 1) null else winner
            }
            every { f.namespaces.create(any()) } answers {
                events += "create"
                throw ConflictException("already exists")
            }
            f.codayDisabled()

            f.provisioner.ensure(userId, externalId) shouldBe true

            // The first find and the creation come before the rollback of the creation transaction, the re-read after it.
            events.toList().take(6) shouldBe listOf("begin", "find", "begin", "create", "rollback", "find")
            events.last() shouldBe "commit"
        }

        "concurrent calls for one user create the namespace once" {
            val f = Fixture()
            val created = AtomicReference<Namespace?>(null)
            val creations = AtomicInteger()
            every { f.namespaces.findByExternalId(key) } answers { created.get() }
            every { f.namespaces.create(any()) } answers {
                creations.incrementAndGet()
                Thread.sleep(100) // widens the window in which an unserialised caller would create too
                firstArg<Namespace>().also { created.set(it) }
            }
            f.codayDisabled()
            val user = User(metadata = EntityMetadata(id = userId), externalId = externalId, email = externalId)
            val executor = Executors.newFixedThreadPool(8)
            try {
                val start = CountDownLatch(1)
                val results =
                    (1..8).map { i ->
                        executor.submit<Boolean> {
                            start.await()
                            // Both entry points, the creation listener and the login filter, race here.
                            if (i % 2 == 0) f.provisioner.ensure(userId, externalId) else f.provisioner.ensureOnce(user).let { true }
                        }
                    }
                start.countDown()

                results.map { it.get(10, TimeUnit.SECONDS) } shouldBe List(8) { true }
                creations.get() shouldBe 1
            } finally {
                executor.shutdownNow()
            }
        }

        "a user being provisioned does not block another one" {
            val f = Fixture()
            val otherId = UUID.randomUUID()
            val inCreation = CountDownLatch(1)
            val release = CountDownLatch(1)
            every { f.namespaces.findByExternalId(key) } answers {
                inCreation.countDown()
                release.await(10, TimeUnit.SECONDS)
                f.existingNamespace(defaultAgentName = "Coday")
            }
            every { f.namespaces.findByExternalId("user:bob") } returns f.existingNamespace(defaultAgentName = "Coday")
            f.codayDisabled()
            val executor = Executors.newFixedThreadPool(2)
            try {
                val slow = executor.submit<Boolean> { f.provisioner.ensure(userId, externalId) }
                inCreation.await(5, TimeUnit.SECONDS) shouldBe true

                executor.submit<Boolean> { f.provisioner.ensure(otherId, "bob") }.get(5, TimeUnit.SECONDS) shouldBe true

                release.countDown()
                slow.get(5, TimeUnit.SECONDS) shouldBe true
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
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
            f.codayEnsured()
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
            f.codayEnsured()
            every { f.namespaces.deployAgents(any(), any()) } returns Unit
            f.provisioner.ensureOnce(alice)

            f.provisioner.ensureForAllUsers()

            verify(exactly = 1) { f.namespaces.findByExternalId("user:bob") }
            verify(exactly = 2) { f.namespaces.findByExternalId(key) }
        }
    })
