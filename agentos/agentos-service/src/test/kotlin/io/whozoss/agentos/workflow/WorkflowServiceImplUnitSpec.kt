package io.whozoss.agentos.workflow

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

class WorkflowServiceImplUnitSpec :
    StringSpec({
        val namespaceId = UUID.randomUUID()
        val inUse = mutableSetOf<UUID>()

        fun newService(): Pair<WorkflowServiceImpl, InMemoryWorkflowRepository> {
            val repo = InMemoryWorkflowRepository()
            return WorkflowServiceImpl(repo) { it in inUse } to repo
        }

        fun workflow(
            name: String = "wf",
            ns: UUID? = namespaceId,
        ) = Workflow(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            namespaceId = ns,
            name = name,
            steps = listOf(WorkflowStep(id = "search", tool = "SearchTalents"), WorkflowStep(id = "start", tool = "BatchCaseStarter")),
        )

        beforeTest { inUse.clear() }

        "create validates the definition" {
            val (service, _) = newService()
            shouldThrow<BadRequestException> { service.create(workflow().copy(steps = emptyList())) }
        }

        "names are unique per scope, case- and accent-insensitively" {
            val (service, _) = newService()
            service.create(workflow(name = "My Flow"))
            shouldThrow<ConflictException> { service.create(workflow(name = "my flow")) }
            service.create(workflow(name = "My Flow", ns = UUID.randomUUID())).name shouldBe "My Flow"
        }

        "update refuses to take the name of another workflow in the scope" {
            val (service, _) = newService()
            service.create(workflow(name = "a"))
            val b = service.create(workflow(name = "b"))
            shouldThrow<ConflictException> { service.update(b.copy(name = "a")) }
        }

        "findAvailable returns the namespace workflows then the platform ones" {
            val (service, _) = newService()
            val platform = service.create(workflow(name = "platform", ns = null))
            val own = service.create(workflow(name = "own"))
            service.create(workflow(name = "foreign", ns = UUID.randomUUID()))
            service.findAvailable(namespaceId).map { it.id } shouldContainExactly listOf(own.id, platform.id)
        }

        "resolveForScheduledPrompt defaults, accepts platform and own workflows, refuses foreign ones" {
            val (service, _) = newService()
            val default = service.create(DefaultWorkflowSeeder.defaultWorkflow())
            val platform = service.create(workflow(name = "platform", ns = null))
            val own = service.create(workflow(name = "own"))
            val foreign = service.create(workflow(name = "foreign", ns = UUID.randomUUID()))

            service.resolveForScheduledPrompt(null, namespaceId).id shouldBe default.id
            service.resolveForScheduledPrompt(platform.id, namespaceId).id shouldBe platform.id
            service.resolveForScheduledPrompt(own.id, namespaceId).id shouldBe own.id
            shouldThrow<BadRequestException> { service.resolveForScheduledPrompt(foreign.id, namespaceId) }.message shouldContain
                "another namespace"
            shouldThrow<BadRequestException> { service.resolveForScheduledPrompt(UUID.randomUUID(), namespaceId) }
        }

        "the default workflow cannot be deleted or renamed" {
            val (service, _) = newService()
            val default = service.create(DefaultWorkflowSeeder.defaultWorkflow())
            shouldThrow<ConflictException> { service.delete(default.id) }
            shouldThrow<BadRequestException> { service.update(default.copy(name = "renamed")) }
        }

        "a workflow referenced by a scheduled prompt cannot be deleted" {
            val (service, _) = newService()
            val used = service.create(workflow())
            inUse += used.id
            shouldThrow<ConflictException> { service.delete(used.id) }.message shouldContain "scheduled prompt"
        }

        "an unused workflow is soft-deleted and its name freed" {
            val (service, _) = newService()
            val wf = service.create(workflow(name = "temp"))
            service.delete(wf.id).shouldBeTrue()
            service.findById(wf.id) shouldBe null
            service.create(workflow(name = "temp")).name shouldBe "temp"
        }
    })
