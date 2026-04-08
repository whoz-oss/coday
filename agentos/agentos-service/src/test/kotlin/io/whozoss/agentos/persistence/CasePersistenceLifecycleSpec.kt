package io.whozoss.agentos.persistence

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.caseEvent.InMemoryCaseEventRepository
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.InMemoryCaseRepository
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * CRUD lifecycle contract tests for in-memory persistence.
 *
 * Covers the repository contract shared by all persistence backends:
 * create, read, update, soft-delete, parent isolation, [deleteByParent],
 * and idempotent delete.
 *
 * Restart-survival semantics are verified by the Neo4j integration specs
 * ([Neo4jCasePersistenceSpec], [Neo4jCaseEventPersistenceSpec]).
 */
class CasePersistenceLifecycleSpec : StringSpec({

    // =========================================================================
    // Full CRUD lifecycle
    // =========================================================================

    "full CRUD lifecycle: create, read, update, delete" {
        val namespaceId = UUID.randomUUID()
        val repo = InMemoryCaseRepository()

        val case = Case(
            metadata = EntityMetadata(createdBy = "agent-1"),
            namespaceId = namespaceId,
            status = CaseStatus.PENDING,
        )
        val created = repo.save(case)
        created.metadata.createdBy shouldBe "agent-1"
        created.namespaceId shouldBe namespaceId

        val found = repo.findByIds(listOf(created.metadata.id))
        found shouldHaveSize 1
        found.first().status shouldBe CaseStatus.PENDING

        val updated = created.copy(status = CaseStatus.RUNNING)
        repo.save(updated)
        repo.findByIds(listOf(created.metadata.id)).first().status shouldBe CaseStatus.RUNNING

        repo.delete(created.metadata.id).shouldBeTrue()
        repo.findByIds(listOf(created.metadata.id)).shouldBeEmpty()
    }

    // =========================================================================
    // Events stored and retrieved by parent
    // =========================================================================

    "case events are stored and retrieved by caseId" {
        val namespaceId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        val eventRepo = InMemoryCaseEventRepository()

        val event = MessageEvent(
            metadata = EntityMetadata(),
            namespaceId = namespaceId,
            caseId = caseId,
            actor = Actor(id = "u1", displayName = "User", role = ActorRole.USER),
            content = listOf(MessageContent.Text("hello")),
        )
        eventRepo.save(event)

        val found = eventRepo.findByParent(caseId)
        found shouldHaveSize 1
        found.first().metadata.id shouldBe event.metadata.id
    }

    // =========================================================================
    // Multiple cases per namespace
    // =========================================================================

    "multiple cases in same namespace are all retrievable" {
        val namespaceId = UUID.randomUUID()
        val repo = InMemoryCaseRepository()

        val ids = (1..5).map {
            repo.save(Case(metadata = EntityMetadata(createdBy = "agent-$it"), namespaceId = namespaceId)).metadata.id
        }

        val found = repo.findByParent(namespaceId)
        found shouldHaveSize 5
        found.map { it.metadata.id }.containsAll(ids).shouldBeTrue()
    }

    // =========================================================================
    // Isolation between namespaces
    // =========================================================================

    "cases in different namespaces are isolated" {
        val ns1 = UUID.randomUUID()
        val ns2 = UUID.randomUUID()
        val repo = InMemoryCaseRepository()

        repo.save(Case(metadata = EntityMetadata(), namespaceId = ns1))
        repo.save(Case(metadata = EntityMetadata(), namespaceId = ns1))
        repo.save(Case(metadata = EntityMetadata(), namespaceId = ns2))

        repo.findByParent(ns1) shouldHaveSize 2
        repo.findByParent(ns2) shouldHaveSize 1
    }

    // =========================================================================
    // deleteByParent cascade
    // =========================================================================

    "deleteByParent removes all cases in a namespace without touching others" {
        val ns1 = UUID.randomUUID()
        val ns2 = UUID.randomUUID()
        val repo = InMemoryCaseRepository()

        repo.save(Case(metadata = EntityMetadata(), namespaceId = ns1))
        repo.save(Case(metadata = EntityMetadata(), namespaceId = ns1))
        val survivor = repo.save(Case(metadata = EntityMetadata(), namespaceId = ns2))

        val deleted = repo.deleteByParent(ns1)
        deleted shouldBe 2
        repo.findByParent(ns1).shouldBeEmpty()
        repo.findByParent(ns2) shouldHaveSize 1
        repo.findByParent(ns2).first().metadata.id shouldBe survivor.metadata.id
    }

    // =========================================================================
    // Idempotent delete
    // =========================================================================

    "deleting an already-deleted case returns false" {
        val repo = InMemoryCaseRepository()
        val c = repo.save(Case(metadata = EntityMetadata(), namespaceId = UUID.randomUUID()))

        repo.delete(c.metadata.id).shouldBeTrue()
        repo.delete(c.metadata.id).shouldBeFalse()
    }
})
