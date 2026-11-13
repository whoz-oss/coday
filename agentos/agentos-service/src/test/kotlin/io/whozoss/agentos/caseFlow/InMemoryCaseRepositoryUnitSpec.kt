package io.whozoss.agentos.caseFlow

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * The in-memory repository stands in for the real one in many specs: it must keep the contract of
 * [CaseRepository.assignScheduledPrompt] (the Neo4j one is covered by the persistence spec).
 */
class InMemoryCaseRepositoryUnitSpec :
    StringSpec({
        val namespaceId = UUID.randomUUID()

        fun newCase(scheduledPromptId: UUID? = null) =
            Case(metadata = EntityMetadata(), namespaceId = namespaceId, scheduledPromptId = scheduledPromptId)

        "assignScheduledPrompt links a case that has none and changes nothing else" {
            val repository = InMemoryCaseRepository()
            val case = repository.save(newCase().copy(title = "keep me"))
            val scheduledPromptId = UUID.randomUUID()

            repository.assignScheduledPrompt(case.id, scheduledPromptId) shouldBe true

            repository.findByIds(listOf(case.id)).single() shouldBe case.copy(scheduledPromptId = scheduledPromptId)
        }

        "assignScheduledPrompt is write-once: an existing link is never overwritten" {
            val repository = InMemoryCaseRepository()
            val first = UUID.randomUUID()
            val case = repository.save(newCase(scheduledPromptId = first))

            repository.assignScheduledPrompt(case.id, UUID.randomUUID()) shouldBe false

            repository.findByIds(listOf(case.id)).single().scheduledPromptId shouldBe first
        }

        "assignScheduledPrompt ignores an unknown case" {
            InMemoryCaseRepository().assignScheduledPrompt(UUID.randomUUID(), UUID.randomUUID()) shouldBe false
        }

        "assignScheduledPrompt ignores a soft-deleted case" {
            val repository = InMemoryCaseRepository()
            val case = repository.save(newCase())
            repository.delete(case.id)

            repository.assignScheduledPrompt(case.id, UUID.randomUUID()) shouldBe false

            repository.findByIds(listOf(case.id), withRemoved = true).single().scheduledPromptId shouldBe null
        }
    })
