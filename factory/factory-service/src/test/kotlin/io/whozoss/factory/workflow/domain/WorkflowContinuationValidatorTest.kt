package io.whozoss.factory.workflow.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure unit tests of [WorkflowContinuationValidator]: the check of the steps an expanding step
 * appends to a run, performed when the result is submitted (nothing is stored here).
 *
 * Factory imposes no human approval: the first steps of the continuation run right after the expanding step.
 */
class WorkflowContinuationValidatorTest {

    private fun step(
        id: String,
        kind: String = "agent",
        name: String = "Frontend",
        dependsOn: List<String>? = emptyList(),
        extra: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> {
        val base = linkedMapOf<String, Any?>(
            "id" to id,
            "name" to "Step $id",
            "responsibility" to linkedMapOf<String, Any?>("kind" to kind, "name" to name),
        )
        if (dependsOn != null) base["dependsOn"] = dependsOn
        return base + extra
    }

    /** `prepare` -> `architect-plan` (declares expansion). */
    private val baseDefinition: Map<String, Any?> = run {
        val raw = linkedMapOf<String, Any?>(
            "schemaVersion" to "1",
            "workflowType" to "architect-test",
            "version" to "1.0.0",
            "title" to "Architect test",
            "steps" to listOf(
                step("prepare", name = "Archay"),
                step(
                    "architect-plan",
                    name = "Archay",
                    dependsOn = listOf("prepare"),
                    extra = mapOf("expansion" to mapOf("mode" to "append")),
                ),
            ),
        )
        (WorkflowDefinitionValidator.validate(raw) as WorkflowDefinitionValidation.Valid).definition
    }

    private fun validate(continuation: Any?, agents: List<String>? = listOf("Frontend", "Archay")) =
        WorkflowContinuationValidator.validate(baseDefinition, "architect-plan", continuation, agents)

    private fun continuation(vararg steps: Map<String, Any?>) = mapOf("steps" to steps.toList())

    private fun invalid(result: ContinuationValidation): List<ContinuationViolation> =
        (result as ContinuationValidation.Invalid).violations

    @Test
    fun `a valid continuation is merged and its first steps are wired on the expanding step`() {
        val result = validate(
            continuation(
                step("build"),
                step("approve", kind = "human", name = "Lead", dependsOn = listOf("build")),
            ),
        ) as ContinuationValidation.Valid

        assertThat(result.stepIds).containsExactly("build", "approve")
        val byId = result.steps.associateBy { it.id }
        assertThat(byId.getValue("build").dependsOn).containsExactly("architect-plan")
        assertThat(byId.getValue("approve").dependsOn).containsExactly("build")
        assertThat(byId.getValue("approve").responsibility.kind).isEqualTo(ResponsibilityKind.HUMAN)
        // base (2) + continuation (2): Factory adds nothing of its own.
        assertThat((result.effectiveDefinition["steps"] as List<*>).size).isEqualTo(4)
        assertThat(result.effectiveDefinitionHash).isNotEqualTo(hashWorkflowDefinition(baseDefinition))
    }

    @Test
    fun `a human step written by the Architect is accepted like any other step`() {
        val result = validate(
            continuation(
                step("review-gate", kind = "human", name = "Lead"),
                step("build", dependsOn = listOf("review-gate")),
            ),
        ) as ContinuationValidation.Valid

        val byId = result.steps.associateBy { it.id }
        assertThat(byId.getValue("review-gate").responsibility.kind).isEqualTo(ResponsibilityKind.HUMAN)
        assertThat(byId.getValue("review-gate").dependsOn).containsExactly("architect-plan")
        assertThat(byId.getValue("build").dependsOn).containsExactly("review-gate")
    }

    @Test
    fun `the base steps are untouched, including the expansion declaration`() {
        val result = validate(continuation(step("build"))) as ContinuationValidation.Valid

        val baseSteps = baseDefinition["steps"] as List<*>
        val effectiveSteps = result.effectiveDefinition["steps"] as List<*>
        assertThat(effectiveSteps.take(baseSteps.size)).isEqualTo(baseSteps)
        assertThat((effectiveSteps[1] as Map<*, *>)["expansion"]).isEqualTo(mapOf("mode" to "append"))
    }

    @Test
    fun `an unreadable agent list refuses the continuation (fail-closed)`() {
        val violations = invalid(validate(continuation(step("build", name = "Nobody")), agents = null))

        assertThat(violations).hasSize(1)
        assertThat(violations.single().code).isEqualTo(WorkflowContinuationCodes.AGENTS_UNVERIFIABLE)
        assertThat(violations.single().path).isEqualTo("result.continuation")
    }

    @Test
    fun `the agents are not checked again when the accepted continuation is added to the run`() {
        val result = WorkflowContinuationValidator.validate(
            baseDefinition,
            "architect-plan",
            continuation(step("build", name = "Nobody")),
            availableAgents = null,
            verifyAgents = false,
        )

        assertThat(result).isInstanceOf(ContinuationValidation.Valid::class.java)
    }

    @Test
    fun `an agent that is not enabled in the namespace is rejected with its location`() {
        val violations = invalid(validate(continuation(step("build", name = "Nobody"))))

        assertThat(violations).containsExactly(
            ContinuationViolation(
                "result.continuation.steps[0].responsibility.name",
                WorkflowContinuationCodes.AGENT_NOT_AVAILABLE,
                "is not an agent enabled in the namespace",
            ),
        )
    }

    @Test
    fun `a code step is not supported in a continuation`() {
        val violations = invalid(validate(continuation(step("ok"), step("verify", kind = "code", name = "check"))))

        assertThat(violations).hasSize(1)
        assertThat(violations.single().code).isEqualTo(WorkflowContinuationCodes.UNSUPPORTED_IN_CONTINUATION)
        assertThat(violations.single().path).isEqualTo("result.continuation.steps[1].responsibility.kind")
        assertThat(violations.single().message).contains("code_step")
    }

    @Test
    fun `a step id that exists in the base definition is rejected`() {
        val violations = invalid(validate(continuation(step("prepare"))))

        assertThat(violations.single().code).isEqualTo(WorkflowContinuationCodes.STEP_ID_COLLISION)
        assertThat(violations.single().path).isEqualTo("result.continuation.steps[0].id")
    }

    @Test
    fun `fifty steps are accepted`() {
        val fifty = mapOf("steps" to (1..50).map { step("s$it") })

        val result = validate(fifty)

        assertThat(result).isInstanceOf(ContinuationValidation.Valid::class.java)
        assertThat((result as ContinuationValidation.Valid).stepIds).hasSize(50)
    }

    @Test
    fun `a dependency on a base step other than the expanding step is rejected`() {
        val violations = invalid(validate(continuation(step("build", dependsOn = listOf("prepare")))))

        assertThat(violations.single().code).isEqualTo(WorkflowContinuationCodes.INVALID_DEPENDENCY)
        assertThat(violations.single().path).isEqualTo("result.continuation.steps[0].dependsOn[0]")
    }

    @Test
    fun `a dependency on the expanding step itself is accepted and stays on the expanding step`() {
        val result = validate(continuation(step("build", dependsOn = listOf("architect-plan")))) as ContinuationValidation.Valid

        assertThat(result.steps.single { it.id == "build" }.dependsOn).containsExactly("architect-plan")
    }

    @Test
    fun `a cycle inside the continuation is rejected`() {
        val violations = invalid(
            validate(continuation(step("a", dependsOn = listOf("b")), step("b", dependsOn = listOf("a")))),
        )

        assertThat(violations.single().code).isEqualTo(WorkflowDefinitionErrorCodes.DEPENDENCY_CYCLE)
        assertThat(violations.single().path).isEqualTo("result.continuation.steps")
    }

    @Test
    fun `an unknown dependency is rejected and mapped to the continuation index`() {
        val violations = invalid(validate(continuation(step("a"), step("b", dependsOn = listOf("ghost")))))

        assertThat(violations.single().code).isEqualTo(WorkflowDefinitionErrorCodes.MISSING_DEPENDENCY)
        assertThat(violations.single().path).isEqualTo("result.continuation.steps[1].dependsOn")
    }

    @Test
    fun `a missing dependsOn is rejected by the canonical validator at the continuation index`() {
        val violations = invalid(validate(continuation(step("a", dependsOn = null))))

        assertThat(violations.single().code).isEqualTo(WorkflowDefinitionErrorCodes.INVALID_VALUE)
        assertThat(violations.single().path).isEqualTo("result.continuation.steps[0].dependsOn")
    }

    @Test
    fun `an error on the first step points at steps 0 without any shift`() {
        val violations = invalid(validate(continuation(step("a", extra = mapOf("mission" to "Do it")))))

        assertThat(violations.single().path).isEqualTo("result.continuation.steps[0].entry")
    }

    @Test
    fun `an empty dependsOn means after the expanding step`() {
        val result = validate(continuation(step("a", dependsOn = emptyList()))) as ContinuationValidation.Valid

        assertThat(result.steps.single { it.id == "a" }.dependsOn).containsExactly("architect-plan")
    }

    @Test
    fun `nested expansion and outcome transitions are not supported in a continuation`() {
        val nested = invalid(validate(continuation(step("a", extra = mapOf("expansion" to mapOf("mode" to "append"))))))
        val outcome = invalid(
            validate(
                continuation(step("a", extra = mapOf("outcomeTransitions" to mapOf("fail" to mapOf("action" to "block"))))),
            ),
        )

        assertThat(nested.map { it.code }).contains(WorkflowContinuationCodes.UNSUPPORTED_IN_CONTINUATION)
        assertThat(outcome.map { it.code }).contains(WorkflowContinuationCodes.UNSUPPORTED_IN_CONTINUATION)
    }

    @Test
    fun `more than fifty steps are rejected`() {
        val tooMany = mapOf("steps" to (1..51).map { step("s$it") })

        val violations = invalid(validate(tooMany))

        assertThat(violations.single().code).isEqualTo(WorkflowContinuationCodes.MAX_ITEMS)
    }

    @Test
    fun `an empty or malformed continuation is rejected`() {
        listOf(
            mapOf("steps" to emptyList<Any>()),
            mapOf("steps" to "nope"),
            mapOf("steps" to listOf(step("a")), "extra" to true),
            "text",
            null,
        ).forEach { malformed ->
            val violations = invalid(validate(malformed))
            assertThat(violations.single().code).isEqualTo(WorkflowContinuationCodes.INVALID_CONTINUATION)
        }
    }

    @Test
    fun `a partial step contract is rejected at the offending field`() {
        val violations = invalid(validate(continuation(step("a", extra = mapOf("mission" to "Do it")))))

        assertThat(violations.single().path).isEqualTo("result.continuation.steps[0].entry")
    }

    @Test
    fun `a complete step contract is accepted`() {
        val result = validate(
            continuation(
                step(
                    "a",
                    extra = mapOf("entry" to listOf("Read the plan"), "mission" to "Build it", "exit" to listOf("Report")),
                ),
            ),
        )

        assertThat(result).isInstanceOf(ContinuationValidation.Valid::class.java)
    }

    @Test
    fun `a step that is not the declared expander gets CONTINUATION_NOT_ALLOWED`() {
        val violations = invalid(
            WorkflowContinuationValidator.validate(baseDefinition, "unknown", continuation(step("a")), listOf("Frontend")),
        )

        assertThat(violations.single().code).isEqualTo(WorkflowContinuationCodes.CONTINUATION_NOT_ALLOWED)
    }

    @Test
    fun `violations never repeat submitted values`() {
        val secret = "secret-value-xyz"
        val violations = invalid(
            validate(
                continuation(
                    step("a", kind = secret),
                    step("b", name = secret),
                    step("prepare"),
                    step("c", dependsOn = listOf(secret)),
                ),
            ),
        )

        assertThat(violations).isNotEmpty()
        assertThat(violations.toString()).doesNotContain(secret)
    }
}
