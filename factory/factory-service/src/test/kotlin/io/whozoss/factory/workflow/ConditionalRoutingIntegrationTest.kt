package io.whozoss.factory.workflow

import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.agentattempt.domain.*
import io.whozoss.factory.capability.CapabilityExecution
import io.whozoss.factory.capability.CapabilityExecutionService
import io.whozoss.factory.capability.CapabilityOutcome
import io.whozoss.factory.workflow.domain.*
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.workflow.service.SessionRunService
import io.whozoss.factory.workflow.sse.WorkflowSseHub
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager

/** Real durable state/projection restart coverage; external capability execution is deterministic. */
class ConditionalRoutingIntegrationTest : Neo4jDomainIntegrationTest() {
    @Autowired private lateinit var repository: WorkflowRepository
    @Autowired private lateinit var evidence: WorkflowEvidenceRepository
    @Autowired private lateinit var interactions: HumanInteractionRepository
    @Autowired private lateinit var hub: WorkflowSseHub
    @Autowired private lateinit var transactions: PlatformTransactionManager

    @Test
    fun `single selected routes and both persist inactive state and replay without any redispatch`() {
        for ((suffix, selected) in listOf("front" to listOf("frontend"), "back" to listOf("backend"), "both" to listOf("frontend", "backend"))) {
            val id = "routing-$suffix"
            val ns = "routing-ns"
            val steps = fixture()
            val raw = mapOf("schemaVersion" to "1", "workflowType" to id, "version" to "1.0.0", "title" to id,
                "steps" to steps.map { mapOf("id" to it.id, "name" to it.name, "responsibility" to it.responsibility.toJson(), "dependsOn" to it.dependsOn) + it.routingJson() })
            val normalized = WorkflowDefinitionValidator.validate(raw) as WorkflowDefinitionValidation.Valid
            val hash = hashWorkflowDefinition(normalized.definition)
            repository.saveDefinition(scope, WorkflowDefinitionRecord(id, "1.0.0", hash, normalized.definition))
            val created = createWorkflowInstance(WorkflowStartCommand(id, id, id), WorkflowDefinitionInput(id, "1.0.0", hash, steps), ControllerExecutionInput("test", "agentos", "runner"))
            repository.insertInstance(scope, WorkflowInstanceRecord(ns, id, 1, "active", created.creationCommandHash, created.instance, created.projection))
            val capability = mockk<CapabilityExecutionService>()
            every { capability.hasFinalizedAttempt(any(), any(), any(), any()) } returns false
            val called = mutableListOf<String>()
            every { capability.routingResult(any(), any(), any(), "architect", "attempt-architect") } returns AgentStepResultSubmitted(
                "result-submitted", "result-architect", "attempt-architect", id, "architect", ns, "case", "Executor", "brief",
                AgentStepResultStatus.PASS, "Scope", emptyList(), AgentStepResultClaim(emptyList()), emptyList(), "2026-01-01T00:00:00Z", "result-hash", selectedRoutes = selected,
            )
            every { capability.resolveAndRecord(any(), any(), any(), any(), any(), any(), any()) } answers {
                val step = invocation.args[3] as WorkflowStepDefinition
                called += step.id
                // Branch dispatch must observe committed decision + inactive branch records.
                if (step.id != "architect") {
                    val stored = repository.findStepStates(scope, ns, id).associateBy { it.stepId }
                    assertThat(stored.getValue("architect").payload["routeDecision"]).isNotNull()
                    for (route in steps.first().decision!!.routes.filter { it.name !in selected }) {
                        assertThat(stored.getValue(route.target).status).isEqualTo("skipped")
                        assertThat(stored.getValue(route.target).payload["executed"]).isEqualTo(false)
                    }
                }
                val ev = "proof-${step.id}"
                evidence.append(scope, ns, id, WorkflowEvidenceItem(ev, ns, id, step.id, "agent-result", "pass", mapOf("kind" to "test"), mapOf("attemptId" to "attempt-${step.id}", "validatorsAccepted" to true), null, null))
                CapabilityExecution(CapabilityOutcome.AgentCompleted(step.id, "Executor", "PASS", emptyMap()), evidenceId = ev, attemptId = "attempt-${step.id}")
            }
            fun runner() = SessionRunService(repository, evidence, interactions, capability, hub, transactionManager = transactions)
            assertThat(runner().runSession(scope, ns, id, Path.of("." )).status).isEqualTo("completed")
            assertThat(called).containsExactlyElementsOf(listOf("architect") + listOf("front", "back").filter { if (it == "front") "frontend" in selected else "backend" in selected } + "review")
            val count = called.size
            // New service instance: no in-memory route cache or accepted-result re-selection needed.
            assertThat(runner().runSession(scope, ns, id, Path.of(".")).status).isEqualTo("completed")
            assertThat(called).hasSize(count)
            val projected = repository.findInstance(scope, ns, id)!!.projection["steps"] as List<*>
            assertThat(projected.filterIsInstance<Map<*, *>>().first { it["id"] == "architect" }["routeDecision"]).isNotNull()
        }
    }

    private fun fixture(): List<WorkflowStepDefinition> {
        fun step(id: String, deps: List<String> = emptyList()) = WorkflowStepDefinition(id, id, WorkflowStepResponsibility(ResponsibilityKind.AGENT, "Executor"), deps)
        return listOf(
            step("architect").copy(decision = WorkflowDecision("multiple", listOf(WorkflowRoute("frontend", "front"), WorkflowRoute("backend", "back")))),
            step("front", listOf("architect")), step("back", listOf("architect")),
            step("review", listOf("front", "back")).copy(join = "architect"),
        )
    }
}
