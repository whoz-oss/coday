package io.whozoss.agentos.scheduledTask

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.entity.EntityRepository
import io.whozoss.agentos.entity.InMemoryEntityRepository
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

class CaseDefinitionServiceImplUnitSpec : StringSpec({

    fun repository(): CaseDefinitionRepository {
        val inMemory = InMemoryEntityRepository<CaseDefinition, UUID>(
            parentIdExtractor = { it.namespaceId },
            comparator = compareBy { it.name },
        )
        return object : CaseDefinitionRepository,
            EntityRepository<CaseDefinition, UUID> by inMemory {}
    }

    fun service(repo: CaseDefinitionRepository = repository()) = CaseDefinitionServiceImpl(repo)

    val namespaceId: UUID = UUID.randomUUID()
    val agentId: UUID = UUID.randomUUID()

    fun def(
        name: String = "my-def",
        nsId: UUID = namespaceId,
        userGroupId: UUID? = null,
        userId: UUID? = null,
        description: String? = null,
        enabled: Boolean = true,
        cronExpression: String = "0 8 * * *",
    ) = CaseDefinition(
        metadata = EntityMetadata(id = UUID.randomUUID()),
        namespaceId = nsId,
        userGroupId = userGroupId,
        userId = userId,
        name = name,
        description = description,
        agentId = agentId,
        prompt = "Good morning!",
        cronExpression = cronExpression,
        enabled = enabled,
    )

    // -------------------------------------------------------------------------
    // CaseDefinition init validation
    // -------------------------------------------------------------------------

    "CaseDefinition with namespaceId only is valid" {
        def() // no exception
    }

    "CaseDefinition with namespaceId + userGroupId is valid" {
        def(userGroupId = UUID.randomUUID()) // no exception
    }

    "CaseDefinition with namespaceId + userId is valid" {
        def(userId = UUID.randomUUID()) // no exception
    }

    "CaseDefinition with userGroupId + userId throws IllegalArgumentException" {
        shouldThrow<IllegalArgumentException> {
            def(userGroupId = UUID.randomUUID(), userId = UUID.randomUUID())
        }
    }

    // -------------------------------------------------------------------------
    // create
    // -------------------------------------------------------------------------

    "create persists and returns the definition" {
        val repo = repository()
        val svc = service(repo)
        val d = def("daily-standup", cronExpression = "0 9 * * *")

        val saved = svc.create(d)

        saved.name shouldBe "daily-standup"
        saved.namespaceId shouldBe namespaceId
        saved.cronExpression shouldBe "0 9 * * *"
        saved.enabled.shouldBeTrue()
    }

    // -------------------------------------------------------------------------
    // findByParent
    // -------------------------------------------------------------------------

    "findByParent returns definitions scoped to the given namespace" {
        val repo = repository()
        val svc = service(repo)
        val otherNs = UUID.randomUUID()
        repo.save(def("in-ns"))
        repo.save(def("other-ns", nsId = otherNs))

        val result = svc.findByParent(namespaceId)

        result shouldHaveSize 1
        result.first().name shouldBe "in-ns"
    }

    "findByParent returns empty list when namespace has no definitions" {
        service().findByParent(UUID.randomUUID()) shouldBe emptyList()
    }

    "findByParent returns definitions sorted by name" {
        val repo = repository()
        val svc = service(repo)
        repo.save(def("zeta"))
        repo.save(def("alpha"))
        repo.save(def("mu"))

        svc.findByParent(namespaceId).map { it.name } shouldBe listOf("alpha", "mu", "zeta")
    }

    // -------------------------------------------------------------------------
    // update
    // -------------------------------------------------------------------------

    "update persists changes" {
        val repo = repository()
        val svc = service(repo)
        val saved = repo.save(def("original"))

        val updated = svc.update(saved.copy(name = "renamed", enabled = false))

        updated.name shouldBe "renamed"
        updated.enabled.shouldBeFalse()
    }

    // -------------------------------------------------------------------------
    // delete
    // -------------------------------------------------------------------------

    "delete returns true and soft-deletes" {
        val repo = repository()
        val svc = service(repo)
        val saved = repo.save(def())

        svc.delete(saved.id).shouldBeTrue()
        svc.findByParent(namespaceId) shouldBe emptyList()
    }

    "delete returns false when definition does not exist" {
        service().delete(UUID.randomUUID()).shouldBeFalse()
    }

    // -------------------------------------------------------------------------
    // deleteByParent
    // -------------------------------------------------------------------------

    "deleteByParent soft-deletes all definitions in the namespace" {
        val repo = repository()
        val svc = service(repo)
        repo.save(def("d1"))
        repo.save(def("d2"))

        svc.deleteByParent(namespaceId) shouldBe 2
        svc.findByParent(namespaceId) shouldBe emptyList()
    }

    "deleteByParent does not affect other namespaces" {
        val repo = repository()
        val svc = service(repo)
        val otherNs = UUID.randomUUID()
        repo.save(def("in-ns"))
        repo.save(def("other", nsId = otherNs))

        svc.deleteByParent(namespaceId)

        svc.findByParent(otherNs) shouldHaveSize 1
    }

    // -------------------------------------------------------------------------
    // setEnabled
    // -------------------------------------------------------------------------

    "setEnabled(false) disables an enabled definition" {
        val repo = repository()
        val svc = service(repo)
        svc.setEnabled(repo.save(def(enabled = true)).id, false).enabled.shouldBeFalse()
    }

    "setEnabled(true) enables a disabled definition" {
        val repo = repository()
        val svc = service(repo)
        svc.setEnabled(repo.save(def(enabled = false)).id, true).enabled.shouldBeTrue()
    }

    "setEnabled throws ResourceNotFoundException when definition does not exist" {
        shouldThrow<ResourceNotFoundException> { service().setEnabled(UUID.randomUUID(), true) }
    }

    // -------------------------------------------------------------------------
    // findById
    // -------------------------------------------------------------------------

    "findById returns the definition when it exists" {
        val repo = repository()
        val svc = service(repo)
        val saved = repo.save(def())
        svc.findById(saved.id) shouldBe saved
    }

    "findById returns null when definition does not exist" {
        service().findById(UUID.randomUUID()) shouldBe null
    }

    // -------------------------------------------------------------------------
    // Targeting fields round-trip
    // -------------------------------------------------------------------------

    "namespaceId round-trips through service" {
        val repo = repository()
        val svc = service(repo)
        val nsId = UUID.randomUUID()
        svc.create(def(nsId = nsId)).namespaceId shouldBe nsId
    }

    "userGroupId round-trips through service" {
        val repo = repository()
        val svc = service(repo)
        val groupId = UUID.randomUUID()
        svc.create(def(userGroupId = groupId)).userGroupId shouldBe groupId
    }

    "userId round-trips through service" {
        val repo = repository()
        val svc = service(repo)
        val uid = UUID.randomUUID()
        svc.create(def(userId = uid)).userId shouldBe uid
    }

    // -------------------------------------------------------------------------
    // description / cron round-trips
    // -------------------------------------------------------------------------

    "description round-trips" {
        val repo = repository()
        val svc = service(repo)
        svc.create(def(description = "Daily standup")).description shouldBe "Daily standup"
    }

    "DAILY cron round-trips" {
        val repo = repository()
        val svc = service(repo)
        svc.create(def(cronExpression = "0 9 * * *")).cronExpression shouldBe "0 9 * * *"
    }

    "WEEKLY cron round-trips" {
        val repo = repository()
        val svc = service(repo)
        svc.create(def(cronExpression = "30 14 * * FRI")).cronExpression shouldBe "30 14 * * FRI"
    }
})
