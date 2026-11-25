package io.whozoss.factory.workflow.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OutcomeTransitionsTest {
    private fun step(id: String, vararg deps: String) = WorkflowStepDefinition(id, id,
        WorkflowStepResponsibility(ResponsibilityKind.AGENT, "Executor"), deps.toList())
    private fun returning(target: String, max: Int? = null) = OutcomeTransitions(fail = OutcomeAction("return", target, max))

    @Test
    fun `strict actions reject unknown fields missing destinations fractions and misplaced budgets`() {
        assertThat(OutcomeTransitions.parse(mapOf("fail" to mapOf("action" to "return", "target" to "analyze"))))
            .isEqualTo(returning("analyze"))
        val invalid = listOf(
            mapOf("fail" to mapOf("action" to "return")),
            mapOf("fail" to mapOf("action" to "return", "target" to "a", "maxCycles" to 1.5)),
            mapOf("fail" to mapOf("action" to "return", "target" to "a", "maxCycles" to 0)),
            mapOf("fail" to mapOf("action" to "block", "target" to "a")),
            mapOf("pass" to mapOf("action" to "activate", "target" to "a", "maxCycles" to 2)),
            mapOf("pass" to mapOf("action" to "jump", "target" to "a")),
            mapOf("needsResearch" to mapOf("action" to "return", "target" to "a")),
        )
        invalid.forEach { assertThat(OutcomeTransitions.parse(it)).describedAs(it.toString()).isNull() }
    }

    @Test
    fun `return scope includes every descendant and preserves unrelated branches`() {
        val steps = listOf(step("a"), step("b", "a"), step("review", "b").copy(outcomeTransitions = returning("a")),
            step("approval", "review").copy(responsibility = WorkflowStepResponsibility(ResponsibilityKind.HUMAN, "Owner")),
            step("independent"))
        assertThat(OutcomeReplayPlan.error(steps)).isNull()
        assertThat(OutcomeReplayPlan.scope(steps, "a")).containsExactlyInAnyOrder("a", "b", "review", "approval")
        assertThat(OutcomeReplayPlan.error(steps.map { if (it.id == "review") it.copy(outcomeTransitions = returning("independent")) else it }))
            .isEqualTo("return_target_must_be_self_or_ancestor")
        assertThat(OutcomeReplayPlan.error(listOf(step("a").copy(outcomeTransitions = returning("a"))))).isNull()
    }

    @Test
    fun `PASS destination retains AND and other successor activation`() {
        val steps = listOf(step("a").copy(outcomeTransitions = OutcomeTransitions(pass = OutcomeAction("activate", "join"))),
            step("b"), step("join", "a", "b"), step("other", "a"))
        assertThat(OutcomeReplayPlan.error(steps)).isNull()
        val statuses = mapOf("a" to "completed", "b" to "running", "join" to "pending", "other" to "pending")
        assertThat(SessionSequencer.evaluate(steps, statuses).ready).containsExactly("other")
        assertThat(SessionSequencer.evaluate(steps, statuses + ("b" to "completed")).ready).containsExactly("join", "other")
    }

    @Test
    fun `partial decision replay and overlapping regions are rejected`() {
        val decision = step("choose").copy(decision = WorkflowDecision("single", listOf(WorkflowRoute("x", "front"), WorkflowRoute("y", "back"))))
        val steps = listOf(decision, step("front", "choose"), step("back", "choose"),
            step("join", "front", "back").copy(join = "choose", outcomeTransitions = returning("front")))
        assertThat(OutcomeReplayPlan.error(steps)).isEqualTo("return_splits_decision_region")
        assertThat(OutcomeReplayPlan.error(steps.map { if (it.id == "join") it.copy(outcomeTransitions = returning("choose")) else it })).isNull()
        assertThat(OutcomeReplayPlan.error(listOf(step("a"), step("b", "a").copy(outcomeTransitions = returning("a")),
            step("c", "b").copy(outcomeTransitions = returning("b"))))).isEqualTo("overlapping_return_regions")
    }

    @Test
    fun `code and human return declarations are explicitly rejected`() {
        for (kind in listOf(ResponsibilityKind.CODE, ResponsibilityKind.HUMAN)) {
            val candidate = step("work").copy(responsibility = WorkflowStepResponsibility(kind, "Executor"),
                outcomeTransitions = returning("work"))
            assertThat(OutcomeReplayPlan.error(listOf(candidate))).isEqualTo("return_requires_agent_result")
        }
    }

    @Test
    fun `canonical serialization is opt in and survives policy reconstruction`() {
        val legacyStep = mapOf("id" to "a", "name" to "a", "responsibility" to mapOf("kind" to "agent", "name" to "Executor"), "dependsOn" to emptyList<String>())
        val raw = mapOf("schemaVersion" to "1", "workflowType" to "test", "version" to "1.0.0", "title" to "Test", "steps" to listOf(legacyStep))
        val legacy = WorkflowDefinitionValidator.validate(raw) as WorkflowDefinitionValidation.Valid
        assertThat(legacy.definition).isEqualTo(raw)
        val transition = returning("a").toJson()
        val valid = WorkflowDefinitionValidator.validate(raw + ("steps" to listOf(legacyStep + ("outcomeTransitions" to transition)))) as WorkflowDefinitionValidation.Valid
        assertThat(valid.steps.single().outcomeTransitions).isEqualTo(returning("a"))
        assertThat((valid.definition["steps"] as List<*>).single()).isEqualTo(legacyStep + ("outcomeTransitions" to transition))
    }
}
