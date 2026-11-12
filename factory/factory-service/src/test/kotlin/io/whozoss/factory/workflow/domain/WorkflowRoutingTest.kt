package io.whozoss.factory.workflow.domain

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.agentattempt.domain.AgentStepResultValidation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WorkflowRoutingTest {
    private fun step(id: String, deps: List<String> = emptyList(), kind: ResponsibilityKind = ResponsibilityKind.AGENT) =
        WorkflowStepDefinition(id, id, WorkflowStepResponsibility(kind, "Executor"), deps)

    private val choice = WorkflowDecision("multiple", listOf(WorkflowRoute("frontend", "front"), WorkflowRoute("backend", "back")))
    private fun steps() = listOf(
        step("architect").copy(decision = choice),
        step("front", listOf("architect")),
        step("back", listOf("architect"), ResponsibilityKind.HUMAN),
        step("review", listOf("front", "back")).copy(join = "architect"),
    )

    @Test
    fun `selection is named bounded explicit and respects mode`() {
        assertThat(choice.accepts(listOf("frontend"))).isTrue()
        assertThat(choice.accepts(listOf("backend"))).isTrue()
        assertThat(choice.accepts(listOf("frontend", "backend"))).isTrue()
        listOf(null, emptyList(), listOf("front"), listOf("fullstack"), listOf("frontend", "frontend")).forEach {
            assertThat(choice.accepts(it)).isFalse()
        }
        assertThat(choice.copy(mode = "single").accepts(listOf("frontend", "backend"))).isFalse()
        assertThat(WorkflowDecision.parse(choice.toJson())).isEqualTo(choice)
        assertThat(WorkflowDecision.parse(choice.toJson() + ("unexpected" to true))).isNull()
        assertThat(WorkflowDecision.parse(choice.copy(routes = List(17) { WorkflowRoute("r$it", "s$it") }).toJson())).isNull()
        assertThat(WorkflowDecision.parse(choice.copy(routes = listOf(choice.routes.first())).toJson())).isNull()
    }

    @Test
    fun `front only back only and both wait for every selected success`() {
        for (selected in listOf(setOf("front"), setOf("back"), setOf("front", "back"))) {
            val steps = steps()
            val statuses = linkedMapOf("architect" to "completed", "front" to "skipped", "back" to "skipped", "review" to "pending")
            selected.forEach { statuses[it] = "ready" }
            assertThat(SessionSequencer.readySteps(steps, statuses)).containsExactlyElementsOf(listOf("front", "back").filter { it in selected })
            assertThat(SessionSequencer.evaluate(steps, statuses).ready).doesNotContain("review")
            selected.forEach { statuses[it] = "completed" }
            assertThat(SessionSequencer.evaluate(steps, statuses).ready).containsExactly("review")
            statuses["review"] = "completed"
            assertThat(SessionSequencer.terminalStatus(steps, statuses)).isEqualTo("completed")
        }
    }

    @Test
    fun `selected failure human waiting and research never satisfy join`() {
        val base = mapOf("architect" to "completed", "front" to "completed", "back" to "failed", "review" to "pending")
        assertThat(SessionSequencer.evaluate(steps(), base).blocked).containsExactly("review")
        for (waiting in listOf("waiting_human", "needs_research", "running", "pending")) {
            assertThat(SessionSequencer.evaluate(steps(), base + ("back" to waiting)).ready).doesNotContain("review")
        }
        assertThat(SessionSequencer.terminalStatus(steps(), base + ("back" to "waiting_human"))).isEqualTo("waiting_human")
        assertThat(SessionSequencer.terminalStatus(steps(), base + ("back" to "needs_research"))).isEqualTo("needs_research")
    }

    @Test
    fun `ordinary AND dependencies are never relaxed by skipped status`() {
        val steps = steps() + step("audit")
        val join = steps.first { it.id == "review" }.copy(dependsOn = listOf("front", "back", "audit"))
        val statuses = mapOf("architect" to "completed", "front" to "completed", "back" to "skipped", "audit" to "skipped")
        assertThat(WorkflowRouting.dependenciesSatisfied(join, steps, statuses)).isFalse()
        assertThat(WorkflowRouting.dependenciesSatisfied(join.copy(join = null), steps, statuses + ("audit" to "completed"))).isFalse()
        assertThat(WorkflowRouting.dependenciesSatisfied(join, steps, statuses + ("audit" to "completed"))).isTrue()
    }

    @Test
    fun `unsupported topology is rejected rather than silently skipping path steps`() {
        val good = steps()
        assertThat(SessionDefinitionValidator.validate(good)).isInstanceOf(SessionDefinitionValidator.Result.Valid::class.java)
        val invalid = listOf(
            good.map { if (it.id == "back") it.copy(dependsOn = listOf("front")) else it },
            good + step("escape", listOf("front")),
            good.map { if (it.id == "review") it.copy(join = null) else it },
            good.map { if (it.id == "architect") it.copy(dependsOn = listOf("review")) else it },
            good.map { if (it.id == "architect") it.copy(decision = choice.copy(routes = listOf(WorkflowRoute("frontend", "missing"), choice.routes.last()))) else it },
            good.map { if (it.id == "back") it.copy(decision = choice) else it },
        )
        invalid.forEach { assertThat(SessionDefinitionValidator.validate(it)).isInstanceOf(SessionDefinitionValidator.Result.Invalid::class.java) }
    }

    @Test
    fun `legacy normalized definition hash and ordinary AND stay unchanged`() {
        val raw = mapOf("schemaVersion" to "1", "workflowType" to "legacy", "version" to "1.0.0", "title" to "Legacy", "steps" to listOf(
            mapOf("id" to "a", "name" to "a", "responsibility" to mapOf("kind" to "agent", "name" to "Executor"), "dependsOn" to emptyList<String>()),
        ))
        val valid = WorkflowDefinitionValidator.validate(raw) as WorkflowDefinitionValidation.Valid
        assertThat(valid.definition).isEqualTo(raw)
        assertThat(hashWorkflowDefinition(valid.definition)).isEqualTo(hashWorkflowDefinition(raw))
        assertThat(valid.steps.single().routingJson()).isEmpty()
    }

    @Test
    fun `structured result rejects duplicates malformed names and choices on non PASS`() {
        val mapper = ObjectMapper()
        fun result(status: String, selection: String) = mapper.readTree("""{"status":"$status","summary":"Scope","claims":{"modifiedFiles":[]},"selectedRoutes":$selection}""")
        assertThat(AgentStepResultValidation.violations(result("PASS", "[\"frontend\",\"backend\"]"))).isEmpty()
        for (selection in listOf("[]", "null", "[4]", "[\"frontend\",\"frontend\"]", "[\"bad route\"]")) {
            assertThat(AgentStepResultValidation.violations(result("PASS", selection))).isNotEmpty()
        }
        assertThat(AgentStepResultValidation.violations(result("NEEDS_RESEARCH", "[\"frontend\"]"))).isNotEmpty()
    }
}
