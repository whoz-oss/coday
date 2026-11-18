package io.whozoss.factory.forge.bmad

import io.whozoss.factory.sdk.spi.BriefContext
import io.whozoss.factory.sdk.spi.RunView
import io.whozoss.factory.sdk.spi.StepValidationContext
import io.whozoss.factory.sdk.spi.StepValidationVerdict
import io.whozoss.factory.sdk.spi.WatchedFileSnapshot
import io.whozoss.factory.sdk.spi.WorkspaceDelta
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ForgeStepRoleGuardTest {
    private val run = RunView(runId = "role-guards", runType = "forge-bmad")
    private val validator = ForgeBmadStepResultValidator()
    private val briefs = ForgeBmadBriefContributor()
    private val unchangedSpec = mapOf(
        "SPEC.md" to WatchedFileSnapshot("SPEC.md", true, "before", true, "before"),
    )

    @Test
    fun `implementation scope applies to standard workers on both delivery and correction`() {
        for ((flow, stepId, agent) in listOf(
            Triple("forge.implement-step", "step-implementation", "Backend"),
            Triple("forge.implement-step", "step-implementation", "Frontend"),
            Triple("forge.correction", "step-correction", "Backend"),
        )) {
            val context = StepValidationContext(
                run = run,
                flowType = flow,
                stepId = stepId,
                agentName = agent,
                fragmentInputs = mapOf("step" to mapOf("file_paths" to listOf("src/allowed.kt"))),
                workspaceDelta = WorkspaceDelta(
                    changedFilesDuringTurn = mapOf("src/forbidden.kt" to "MODIFIED"),
                    watchedFiles = unchangedSpec,
                ),
            )
            val verdict = validator.validate(context)
            assertThat(verdict).describedAs("$flow/$stepId assigned to $agent")
                .isInstanceOf(StepValidationVerdict.Rejected::class.java)
            val rejected = verdict as StepValidationVerdict.Rejected
            assertThat(rejected.reasonCode).isEqualTo("STEP_SCOPE_VIOLATION")
            assertThat(rejected.findings.single().filePath).isEqualTo("src/forbidden.kt")
        }
    }

    @Test
    fun `verification and research stay read only with renamed agents`() {
        for ((flow, stepId, agent) in listOf(
            Triple("forge.implement-step", "step-verification", "QADeveloper"),
            Triple("forge.correction", "step-verification", "Reviewer"),
            Triple("forge.tech-design", "codebase-research", "Architect"),
        )) {
            val verdict = validator.validate(StepValidationContext(
                run = run,
                flowType = flow,
                stepId = stepId,
                agentName = agent,
                workspaceDelta = WorkspaceDelta(changedFilesDuringTurn = mapOf("src/change.kt" to "MODIFIED")),
            ))
            assertThat(verdict).describedAs("$flow/$stepId assigned to $agent")
                .isInstanceOf(StepValidationVerdict.Rejected::class.java)
            assertThat((verdict as StepValidationVerdict.Rejected).reasonCode)
                .isEqualTo("READ_ONLY_WORKSPACE_VIOLATION")
        }
    }

    @Test
    fun `new delivery run refuses missing or unsafe implementation scope`() {
        val delivery = run.copy(runType = "forge.delivery")
        for (paths in listOf(
            emptyList<String>(),
            listOf("src/../outside.kt"),
            listOf("src/allowed.kt", "src/allowed.kt"),
        )) {
            val verdict = validator.validate(StepValidationContext(
                run = delivery,
                flowType = "forge.implement-step",
                stepId = "step-implementation",
                agentName = "Backend",
                fragmentInputs = mapOf("step" to mapOf("file_paths" to paths)),
                workspaceDelta = WorkspaceDelta(watchedFiles = unchangedSpec),
            ))
            assertThat(verdict).isInstanceOf(StepValidationVerdict.Rejected::class.java)
            assertThat((verdict as StepValidationVerdict.Rejected).reasonCode).isEqualTo("MISSING_STEP_SCOPE")
        }

        val allowed = validator.validate(StepValidationContext(
            run = delivery,
            flowType = "forge.implement-step",
            stepId = "step-implementation",
            agentName = "Backend",
            fragmentInputs = mapOf("step" to mapOf("file_paths" to listOf("src/allowed.kt"))),
            workspaceDelta = WorkspaceDelta(
                changedFilesDuringTurn = mapOf("src/allowed.kt" to "MODIFIED"),
                watchedFiles = unchangedSpec,
            ),
        ))
        assertThat(allowed).isInstanceOf(StepValidationVerdict.Accepted::class.java)

        val legacyWithoutScope = validator.validate(StepValidationContext(
            run = run,
            flowType = "forge.implement-step",
            stepId = "step-implementation",
            agentName = "ForgeBuilder",
            workspaceDelta = WorkspaceDelta(watchedFiles = unchangedSpec),
        ))
        assertThat(legacyWithoutScope).isInstanceOf(StepValidationVerdict.Accepted::class.java)

        val unguardedStep = validator.validate(StepValidationContext(
            run = delivery,
            flowType = "forge.implement-step",
            stepId = "unrecognized-implementation",
            agentName = "Backend",
            workspaceDelta = WorkspaceDelta(watchedFiles = unchangedSpec),
        ))
        assertThat(unguardedStep).isInstanceOf(StepValidationVerdict.Rejected::class.java)
        assertThat((unguardedStep as StepValidationVerdict.Rejected).reasonCode)
            .isEqualTo("UNKNOWN_DELIVERY_STEP")
    }

    @Test
    fun `brief follows the step responsibility for standard workers`() {
        val implementation = briefs.contribute(BriefContext(
            run = run,
            flowType = "forge.implement-step",
            stepId = "step-implementation",
            agentName = "Backend",
            fragmentInputs = mapOf("step" to mapOf("file_paths" to listOf("src/allowed.kt"))),
        ))
        assertThat(implementation.sections.joinToString("\n"))
            .contains("Allowed file scope for this step: src/allowed.kt")

        val verification = briefs.contribute(BriefContext(
            run = run,
            flowType = "forge.implement-step",
            stepId = "step-verification",
            agentName = "QADeveloper",
        ))
        assertThat(verification.sections.joinToString("\n"))
            .contains("Read-Only")
            .doesNotContain("Allowed file scope for this step")

        val research = briefs.contribute(BriefContext(
            run = run,
            flowType = "forge.tech-design",
            stepId = "codebase-research",
            agentName = "Architect",
        ))
        assertThat(research.sections.joinToString("\n"))
            .contains("Read-Only")
    }
}
