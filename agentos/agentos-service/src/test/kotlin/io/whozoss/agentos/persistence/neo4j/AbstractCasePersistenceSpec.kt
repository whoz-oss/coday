package io.whozoss.agentos.persistence.neo4j

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.caseFlow.saveChange
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceRepository
import io.whozoss.agentos.sdk.aiProvider.ReasoningEffort
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * Shared case persistence contract tests.
 *
 * Subclasses activate a specific persistence mode (Testcontainers or embedded)
 * and inherit all test cases, ensuring both modes satisfy the same contract.
 *
 * A [Namespace] node must exist before [Case] nodes are saved because
 * [io.whozoss.agentos.caseFlow.CaseNodeNeo4jRepository.findActiveByNamespaceId] traverses the BELONGS_TO
 * edge to the Namespace node. [namespaceRepo] is used to pre-create namespaces.
 */
abstract class AbstractCasePersistenceSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var repo: CaseRepository

    @Autowired
    lateinit var namespaceRepo: NamespaceRepository

    @Autowired
    lateinit var driver: Driver

    fun namespace() = Namespace(metadata = EntityMetadata(), name = "test-ns")

    fun case(
        namespaceId: UUID,
        status: CaseStatus = CaseStatus.CREATED,
    ) = Case(metadata = EntityMetadata(), namespaceId = namespaceId, status = status)

    init {
        beforeEach { Neo4jContainerSupport.clearDatabase(driver) }

        "save and findByIds returns the same case" {
            val ns = namespaceRepo.save(namespace())
            val saved = repo.save(case(ns.id))
            val found = repo.findByIds(listOf(saved.id))
            found shouldHaveSize 1
            found.first().id shouldBe saved.id
            found.first().status shouldBe CaseStatus.CREATED
        }

        "save and findByIds round-trips reasoningEffort, absent by default" {
            val ns = namespaceRepo.save(namespace())
            val withEffort = repo.save(case(ns.id).copy(reasoningEffort = ReasoningEffort.MAX))
            val withoutEffort = repo.save(case(ns.id))

            val found = repo.findByIds(listOf(withEffort.id, withoutEffort.id)).associateBy { it.id }

            found.getValue(withEffort.id).reasoningEffort shouldBe ReasoningEffort.MAX
            found.getValue(withoutEffort.id).reasoningEffort.shouldBeNull()
        }

        "save and findByIds round-trips summary and summaryMessageCount, absent by default" {
            val ns = namespaceRepo.save(namespace())
            val summarized = repo.save(case(ns.id).copy(summary = "Billing migration, in progress.", summaryMessageCount = 4))
            val plain = repo.save(case(ns.id))

            val found = repo.findByIds(listOf(summarized.id, plain.id)).associateBy { it.id }

            found.getValue(summarized.id).summary shouldBe "Billing migration, in progress."
            found.getValue(summarized.id).summaryMessageCount shouldBe 4
            found.getValue(plain.id).summary.shouldBeNull()
            found.getValue(plain.id).summaryMessageCount.shouldBeNull()
        }

        "pending naming intent survives persistence and disappears on completion" {
            val ns = namespaceRepo.save(namespace())
            val pending = repo.save(case(ns.id).copy(title = "WZ-35428", namingInstructions = "Investigate login", namingPrefix = "WZ-35428"))
            repo.save(case(ns.id))

            val found = repo.findPendingNaming(10).single()
            found.id shouldBe pending.id
            found.namingInstructions shouldBe "Investigate login"
            found.namingPrefix shouldBe "WZ-35428"
            repo.save(found.copy(title = "WZ-35428 Login", namingInstructions = null, namingPrefix = null))
            repo.findPendingNaming(10).shouldBeEmpty()
        }

        "competing naming claims have one winner and an expired claim can be recovered" {
            //given
            val ns = namespaceRepo.save(namespace())
            val pending = repo.save(case(ns.id).copy(namingInstructions = "Investigate login"))
            val snapshots = repo.findPendingNaming(10).single()
            val now = java.time.Instant.parse("2026-01-01T00:00:00Z")
            val gate = java.util.concurrent.CountDownLatch(1)
            val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                val results = listOf("worker-a", "worker-b").map { owner ->
                    pool.submit<Case?> {
                        gate.await()
                        repo.claimNaming(snapshots, owner, now, now.plusSeconds(60))
                    }
                }
                //when
                gate.countDown()
                val claims = results.map { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }.filterNotNull()

                //then
                claims.size shouldBe 1
                repo.claimNaming(repo.findByIds(listOf(pending.id)).single(), "worker-c", now, now.plusSeconds(60)) shouldBe null
                val recovered = repo.claimNaming(repo.findByIds(listOf(pending.id)).single(), "worker-c", now.plusSeconds(61), now.plusSeconds(121))
                recovered?.namingLeaseOwner shouldBe "worker-c"
            } finally {
                pool.shutdownNow()
            }
        }

        "expired naming input is purged without overwriting the visible title" {
            //given
            val ns = namespaceRepo.save(namespace())
            val pending = repo.save(case(ns.id).copy(title = "WZ-42", namingInstructions = "private instructions", namingPrefix = "WZ-42"))

            //when
            repo.purgeExpiredNaming(java.time.Instant.now().plusSeconds(1), 10)

            //then
            val retained = repo.findByIds(listOf(pending.id)).single()
            retained.title shouldBe "WZ-42"
            retained.namingInstructions.shouldBeNull()
            retained.namingPrefix.shouldBeNull()
        }

        "naming completion fences expired leases and stale owners at database write" {
            //given
            val ns = namespaceRepo.save(namespace())
            val pending = repo.save(case(ns.id).copy(title = "WZ-42", namingInstructions = "private", namingPrefix = "WZ-42"))
            val now = java.time.Instant.now()
            val expired = repo.claimNaming(pending, "old", now.minusSeconds(60), now.minusSeconds(1))!!

            //when
            val refused = repo.completeNaming(expired, "WZ-42 stale")
            val recovered = repo.claimNaming(repo.findByIds(listOf(pending.id)).single(), "new", now, now.plusSeconds(60))!!
            val stale = repo.completeNaming(expired, "WZ-42 stale")
            val accepted = repo.completeNaming(recovered, "WZ-42 accepted")

            //then
            refused shouldBe null
            stale shouldBe null
            accepted?.title shouldBe "WZ-42 accepted"
            repo.findByIds(listOf(pending.id)).single().namingInstructions.shouldBeNull()
        }

        "same-title manual rename fences a previously claimed naming result" {
            //given
            val ns = namespaceRepo.save(namespace())
            val pending = repo.save(case(ns.id).copy(title = "WZ-42", namingInstructions = "private"))
            val now = java.time.Instant.now()
            val claimed = repo.claimNaming(pending, "worker", now, now.plusSeconds(60))!!

            //when
            repo.saveChange(claimed) {
                it.copy(titleEditGeneration = "manual", namingInstructions = null, namingLeaseOwner = null, namingLeaseUntil = null)
            }
            val staleResult = repo.completeNaming(claimed, "Generated")

            //then
            staleResult.shouldBeNull()
            val stored = repo.findByIds(listOf(pending.id)).single()
            stored.title shouldBe "WZ-42"
            stored.titleEditGeneration shouldBe "manual"
            stored.namingInstructions.shouldBeNull()
        }

        "manual rename and naming completion racing preserve the manual title" {
            //given
            val ns = namespaceRepo.save(namespace())
            val pending = repo.save(case(ns.id).copy(title = "WZ-42", namingInstructions = "private"))
            val now = java.time.Instant.now()
            val claimed = repo.claimNaming(pending, "worker", now, now.plusSeconds(60))!!
            val gate = java.util.concurrent.CountDownLatch(1)
            val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                //when
                val manual = pool.submit {
                    gate.await()
                    repo.saveChange(claimed) {
                        it.copy(title = "Manual", titleEditGeneration = "manual", namingInstructions = null, namingLeaseOwner = null, namingLeaseUntil = null)
                    }
                }
                val generated = pool.submit<Case?> { gate.await(); repo.completeNaming(claimed, "Generated") }
                gate.countDown()
                manual.get(10, java.util.concurrent.TimeUnit.SECONDS)
                generated.get(10, java.util.concurrent.TimeUnit.SECONDS)

                //then
                repo.findByIds(listOf(pending.id)).single().title shouldBe "Manual"
            } finally {
                pool.shutdownNow()
            }
        }

        "deletion removes pending naming instructions even from removed cases" {
            //given
            val ns = namespaceRepo.save(namespace())
            val pending = repo.save(case(ns.id).copy(namingInstructions = "private instructions", namingPrefix = "WZ-42"))

            //when
            repo.delete(pending.id)

            //then
            val deleted = repo.findByIds(listOf(pending.id), withRemoved = true).single()
            deleted.namingInstructions.shouldBeNull()
            deleted.namingPrefix.shouldBeNull()
            deleted.namingLeaseOwner.shouldBeNull()
            deleted.namingLeaseUntil.shouldBeNull()
        }

        "findByParent returns cases for that namespace only" {
            val ns1 = namespaceRepo.save(namespace())
            val ns2 = namespaceRepo.save(namespace())
            repo.save(case(ns1.id))
            repo.save(case(ns1.id))
            repo.save(case(ns2.id))
            repo.findByParent(ns1.id) shouldHaveSize 2
            repo.findByParent(ns2.id) shouldHaveSize 1
        }

        "update: save with same id replaces the node" {
            val ns = namespaceRepo.save(namespace())
            val c = repo.save(case(ns.id))
            repo.save(c.copy(status = CaseStatus.RUNNING))
            val found = repo.findByIds(listOf(c.id))
            found.first().status shouldBe CaseStatus.RUNNING
        }

        "soft delete removes case from findByIds" {
            val ns = namespaceRepo.save(namespace())
            val c = repo.save(case(ns.id))
            repo.delete(c.id).shouldBeTrue()
            repo.findByIds(listOf(c.id)).shouldBeEmpty()
        }

        "double delete returns false" {
            val ns = namespaceRepo.save(namespace())
            val c = repo.save(case(ns.id))
            repo.delete(c.id).shouldBeTrue()
            repo.delete(c.id).shouldBeFalse()
        }

        "saving a case does not overwrite Namespace node properties" {
            // Regression: NamespaceNode.stub() used to write empty name/description
            // onto the existing Namespace node when the BELONGS_TO edge was saved
            // via the @Relationship field.
            val ns = namespaceRepo.save(Namespace(metadata = EntityMetadata(), name = "my-namespace", description = "important"))
            repo.save(case(ns.id))
            repo.save(case(ns.id))
            val found = namespaceRepo.findByIds(listOf(ns.id))
            found shouldHaveSize 1
            found.first().name shouldBe "my-namespace"
            found.first().description shouldBe "important"
        }

        "findActiveDescendants returns empty list when case has no children" {
            val ns = namespaceRepo.save(namespace())
            val root = repo.save(case(ns.id))
            repo.findActiveDescendants(root.id).shouldBeEmpty()
        }

        "findActiveDescendants returns direct children" {
            val ns = namespaceRepo.save(namespace())
            val root = repo.save(case(ns.id))
            val child1 = repo.save(case(ns.id).copy(parentCaseId = root.id))
            val child2 = repo.save(case(ns.id).copy(parentCaseId = root.id))
            repo.linkParentToChild(root.id, child1.id)
            repo.linkParentToChild(root.id, child2.id)
            val descendants = repo.findActiveDescendants(root.id)
            descendants shouldHaveSize 2
            descendants.map { it.id }.toSet() shouldBe setOf(child1.id, child2.id)
        }

        "findActiveDescendants returns multi-level descendants leaves first" {
            val ns = namespaceRepo.save(namespace())
            val root = repo.save(case(ns.id))
            val child = repo.save(case(ns.id).copy(parentCaseId = root.id))
            val grandchild = repo.save(case(ns.id).copy(parentCaseId = child.id))
            repo.linkParentToChild(root.id, child.id)
            repo.linkParentToChild(child.id, grandchild.id)
            val descendants = repo.findActiveDescendants(root.id)
            descendants shouldHaveSize 2
            // grandchild is at depth 2, child at depth 1 — grandchild must come first
            descendants.first().id shouldBe grandchild.id
            descendants.last().id shouldBe child.id
        }

        "findActiveDescendants excludes terminal-status descendants" {
            val ns = namespaceRepo.save(namespace())
            val root = repo.save(case(ns.id))
            val active = repo.save(case(ns.id).copy(parentCaseId = root.id))
            val killed = repo.save(case(ns.id, CaseStatus.KILLED).copy(parentCaseId = root.id))
            val errored = repo.save(case(ns.id, CaseStatus.ERROR).copy(parentCaseId = root.id))
            repo.linkParentToChild(root.id, active.id)
            repo.linkParentToChild(root.id, killed.id)
            repo.linkParentToChild(root.id, errored.id)
            val descendants = repo.findActiveDescendants(root.id)
            descendants shouldHaveSize 1
            descendants.first().id shouldBe active.id
        }

        "findActiveDescendants excludes soft-deleted descendants" {
            val ns = namespaceRepo.save(namespace())
            val root = repo.save(case(ns.id))
            val child = repo.save(case(ns.id).copy(parentCaseId = root.id))
            repo.linkParentToChild(root.id, child.id)
            repo.delete(child.id)
            repo.findActiveDescendants(root.id).shouldBeEmpty()
        }

        "findActiveDescendants does not include the root case itself" {
            val ns = namespaceRepo.save(namespace())
            val root = repo.save(case(ns.id))
            val child = repo.save(case(ns.id).copy(parentCaseId = root.id))
            repo.linkParentToChild(root.id, child.id)
            val descendants = repo.findActiveDescendants(root.id)
            descendants.map { it.id } shouldBe listOf(child.id)
        }

        "scheduledPromptId round-trips through toDomain/fromDomain" {
            val ns = namespaceRepo.save(namespace())
            val spId = UUID.randomUUID()
            val saved = repo.save(case(ns.id).copy(scheduledPromptId = spId))
            val found = repo.findByIds(listOf(saved.id))
            found shouldHaveSize 1
            found.first().scheduledPromptId shouldBe spId
        }

        "scheduledPromptId is null when not set" {
            val ns = namespaceRepo.save(namespace())
            val saved = repo.save(case(ns.id))
            val found = repo.findByIds(listOf(saved.id))
            found.first().scheduledPromptId shouldBe null
        }

        "originCaseId round-trips, and a started case stays out of its origin's tree" {
            val ns = namespaceRepo.save(namespace())
            val origin = repo.save(case(ns.id))
            val started = repo.save(case(ns.id).copy(originCaseId = origin.id))

            val found = repo.findByIds(listOf(started.id)).single()
            found.originCaseId shouldBe origin.id
            found.parentCaseId shouldBe null
            repo.findActiveDescendants(origin.id) shouldBe emptyList()
            repo.countAncestorDepth(started.id) shouldBe 0
        }

        "save stamps modified on every write and the value round-trips correctly" {
            val ns = namespaceRepo.save(namespace())
            val first = repo.save(case(ns.id))

            // modified is set by Neo4jCaseRepository.save() from the injected Clock —
            // not by the caller. It must be non-null after the first save.
            first.metadata.modified shouldNotBe null

            val second = repo.save(first.copy(status = CaseStatus.RUNNING))

            second.metadata.modified shouldNotBe null
            second.status shouldBe CaseStatus.RUNNING

            // Verify the value persisted and round-trips through toDomain().
            val found = repo.findByIds(listOf(first.id)).first()
            found.metadata.modified shouldBe second.metadata.modified
        }

        "deleteByParent removes all cases in namespace without touching others" {
            val ns1 = namespaceRepo.save(namespace())
            val ns2 = namespaceRepo.save(namespace())
            repo.save(case(ns1.id))
            repo.save(case(ns1.id))
            val survivor = repo.save(case(ns2.id))
            val deleted = repo.deleteByParent(ns1.id)
            deleted shouldBe 2
            repo.findByParent(ns1.id).shouldBeEmpty()
            repo.findByParent(ns2.id) shouldHaveSize 1
            repo.findByParent(ns2.id).first().id shouldBe survivor.id
        }
    }
}
