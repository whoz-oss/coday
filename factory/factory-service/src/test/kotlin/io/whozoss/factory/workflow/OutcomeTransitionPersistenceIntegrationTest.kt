package io.whozoss.factory.workflow

import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.workflow.domain.*
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.workflow.service.OutcomeTransitionService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/** Real Spring proxy/Neo4j boundaries, not a mocked transaction annotation. */
class OutcomeTransitionPersistenceIntegrationTest : Neo4jDomainIntegrationTest() {
    @Autowired private lateinit var repository: WorkflowRepository
    @Autowired private lateinit var router: OutcomeTransitionService
    @Autowired private lateinit var transactions: PlatformTransactionManager

    private fun step(id: String, deps: List<String> = emptyList()) = WorkflowStepDefinition(id, id,
        WorkflowStepResponsibility(ResponsibilityKind.AGENT, "Executor"), deps,
        outcomeTransitions = OutcomeTransitions(fail = OutcomeAction("return", id)))

    @Test
    fun `ledger survives reload and initialization replay without duplicate executions`() {
        val step = step("work")
        seed("durable", listOf(step))
        router.initialize(scope, "outcome-ns", "durable", listOf(step))
        val before = repository.findInstance(scope, "outcome-ns", "durable")!!
        val execution = (before.projection["stepExecutions"] as List<*>).single() as Map<*, *>
        router.initialize(scope, "outcome-ns", "durable", listOf(step))
        assertThat(repository.findInstance(scope, "outcome-ns", "durable")!!.revision).isEqualTo(before.revision)
        router.record(scope, "outcome-ns", "durable", "work", execution["executionId"] as String, "running")
        val reloaded = repository.findInstance(scope, "outcome-ns", "durable")!!
        assertThat(((reloaded.projection["stepExecutions"] as List<*>).single() as Map<*, *>)["startedAt"]).isNotNull()
        assertThatThrownBy { router.record(scope, "outcome-ns", "durable", "work", "stale", "completed") }
            .hasMessageContaining("EXECUTION_GENERATION_FENCED")
        assertThat(repository.findInstance(scope, "outcome-ns", "durable")!!.revision).isEqualTo(reloaded.revision)
    }

    @Test
    fun `outer rollback removes both ledger and current state allocation`() {
        val steps = listOf(step("work"))
        seed("rollback", steps)
        TransactionTemplate(transactions).executeWithoutResult { status ->
            router.initialize(scope, "outcome-ns", "rollback", steps)
            status.setRollbackOnly()
        }
        assertThat(repository.findInstance(scope, "outcome-ns", "rollback")!!.projection).doesNotContainKey("stepExecutions")
        assertThat(repository.findStepStates(scope, "outcome-ns", "rollback")).isEmpty()
        router.initialize(scope, "outcome-ns", "rollback", steps)
        assertThat(repository.findStepStates(scope, "outcome-ns", "rollback")).hasSize(1)
    }

    private fun seed(workflow: String, steps: List<WorkflowStepDefinition>) {
        val json = mapOf("schemaVersion" to "1", "workflowType" to workflow, "version" to "1.0.0", "title" to workflow,
            "steps" to steps.map { mapOf("id" to it.id, "name" to it.name, "responsibility" to it.responsibility.toJson(),
                "dependsOn" to it.dependsOn) + it.routingJson() })
        val hash = hashWorkflowDefinition(json)
        repository.saveDefinition(scope, WorkflowDefinitionRecord(workflow, "1.0.0", hash, json))
        val created = createWorkflowInstance(WorkflowStartCommand(workflow, workflow, workflow),
            WorkflowDefinitionInput(workflow, "1.0.0", hash, steps),
            ControllerExecutionInput("factory", "factory-run", "runner"))
        repository.insertInstance(scope, WorkflowInstanceRecord("outcome-ns", workflow, 1, "active", created.creationCommandHash,
            created.instance, created.projection))
    }
}
