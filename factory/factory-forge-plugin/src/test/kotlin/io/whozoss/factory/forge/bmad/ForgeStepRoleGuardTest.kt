package io.whozoss.factory.forge.bmad

import com.fasterxml.jackson.databind.ObjectMapper
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
    private val mapper = ObjectMapper()
    private val validator = ForgeBmadStepResultValidator()
    private val briefs = ForgeBmadBriefContributor()
    private val unchangedSpec = mapOf(
        "SPEC.md" to WatchedFileSnapshot("SPEC.md", true, "before", true, "before"),
    )

    private fun businessReport(values: Map<String, Any?>): Map<String, Any?> = mapOf(
        "artifacts" to listOf(mapOf(
            "kind" to ForgeBusinessOutputArtifact.KIND,
            "encoding" to "markdown",
            "content" to mapper.writeValueAsString(values),
        )),
    )

    private fun applicabilityReport(decisions: Map<String, Any?>) = businessReport(mapOf("applicability" to decisions))

    @Test
    fun `packet assembly cannot advance without trusted Git receipt`() {
        fun verdict(evidence: Map<String, Any?>) = validator.validate(StepValidationContext(
            run = run.copy(runType = "forge.delivery"), flowType = "forge.delivery",
            stepId = "packet-assembly", agentName = "ProductEngineer", externalEvidence = evidence,
        ))
        assertThat((verdict(emptyMap()) as StepValidationVerdict.Rejected).reasonCode)
            .isEqualTo("PACKET_GIT_EVIDENCE_MISSING")
        val receipt = mapOf<String, Any?>("repository" to "whoz", "commit" to "a".repeat(40),
            "manifestSha256" to "b".repeat(64), "canonicalManifest" to "{\"schemaVersion\":\"packet-manifest.v1\"}")
        val accepted = verdict(receipt) as StepValidationVerdict.Accepted
        assertThat(accepted.newDomainState?.get("approvedPacketCandidate")).isEqualTo(receipt)
    }

    @Test
    fun `delivery implementation plan rejects empty duplicate and unsafe steps`() {
        fun verdict(steps: List<Map<String, Any?>>) = validator.validate(StepValidationContext(
            run = run.copy(runType = "forge.delivery"),
            flowType = "forge.delivery",
            stepId = "implementation-planning",
            agentName = "Architect",
            report = businessReport(mapOf("delivery_plan" to mapOf("steps" to steps))),
        ))
        val step = mapOf<String, Any?>(
            "id" to "implement-1",
            "title" to "Implement scoped change",
            "file_paths" to listOf("src/allowed.kt"),
            "verification" to "./gradlew test",
        )
        val accepted = verdict(listOf(step)) as StepValidationVerdict.Accepted
        assertThat((accepted.newDomainState?.get("delivery_plan") as Map<*, *>)["steps"])
            .isEqualTo(listOf(step))
        assertThat((verdict(emptyList()) as StepValidationVerdict.Rejected).reasonCode)
            .isEqualTo("MISSING_DELIVERY_PLAN_STEPS")
        for (steps in listOf(
            listOf(step, step),
            listOf(step + ("file_paths" to listOf("src/../outside.kt"))),
            listOf(step + ("verification" to "")),
        )) {
            assertThat((verdict(steps) as StepValidationVerdict.Rejected).reasonCode)
                .isEqualTo("INVALID_DELIVERY_PLAN_STEP")
        }
    }

    @Test
    fun `delivery QA requires a nonempty verification plan on every route`() {
        fun verdict(value: Any?) = validator.validate(StepValidationContext(
            run = run.copy(runType = "forge.delivery"),
            flowType = "forge.delivery",
            stepId = "qa-design",
            agentName = "QAArchitect",
            report = businessReport(mapOf("verification_plan" to value)),
        ))
        val accepted = verdict("1. Run focused and regression tests") as StepValidationVerdict.Accepted
        assertThat(accepted.newDomainState?.get("verification_plan"))
            .isEqualTo("1. Run focused and regression tests")
        for (bad in listOf(null, "", "  ")) {
            assertThat((verdict(bad) as StepValidationVerdict.Rejected).reasonCode)
                .isEqualTo("EMPTY_VERIFICATION_PLAN")
        }
    }

    @Test
    fun `delivery assessment requires all four evidenced applicability decisions`() {
        val decisions: Map<String, Any?> = mapOf(
            "detailed_spec" to mapOf("applicable" to true, "reason" to "Complex product rules"),
            "ux_spec" to mapOf("applicable" to false, "reason" to "No user interface change"),
            "technical_spec" to mapOf("applicable" to true, "reason" to "New integration"),
            "qa_spec" to mapOf("applicable" to false, "reason" to "Verification plan covers it"),
        )
        fun verdict(report: Map<String, Any?>, delta: WorkspaceDelta = WorkspaceDelta()) =
            validator.validate(StepValidationContext(
                run = run.copy(runType = "forge.delivery"),
                flowType = "forge.delivery",
                stepId = "design-assessment",
                agentName = "ProductEngineer",
                report = report,
                workspaceDelta = delta,
            ))

        val accepted = verdict(applicabilityReport(decisions)) as StepValidationVerdict.Accepted
        assertThat(accepted.newDomainState?.get("applicability")).isEqualTo(decisions)
        for (bad in listOf(
            decisions - "qa_spec",
            decisions + ("ux_spec" to mapOf("applicable" to false, "reason" to "")),
            decisions + ("technical_spec" to mapOf("applicable" to "yes", "reason" to "New integration")),
        )) {
            val rejected = verdict(applicabilityReport(bad)) as StepValidationVerdict.Rejected
            assertThat(rejected.reasonCode).isEqualTo("INVALID_DESIGN_APPLICABILITY")
        }
        assertThat((verdict(emptyMap()) as StepValidationVerdict.Rejected).reasonCode)
            .isEqualTo("INVALID_DESIGN_APPLICABILITY")
        assertThat((verdict(
            applicabilityReport(decisions),
            WorkspaceDelta(changedFilesDuringTurn = mapOf("SPEC.md" to "MODIFIED")),
        ) as StepValidationVerdict.Rejected).reasonCode)
            .isEqualTo("READ_ONLY_WORKSPACE_VIOLATION")
    }

    @Test
    fun `delivery preflight is read only regardless of ProductEngineer result`() {
        val verdict = validator.validate(StepValidationContext(
            run = run.copy(runType = "forge.delivery"),
            flowType = "forge.delivery",
            stepId = "delivery-preflight",
            agentName = "ProductEngineer",
            workspaceDelta = WorkspaceDelta(changedFilesDuringTurn = mapOf("SPEC.md" to "MODIFIED")),
        ))
        assertThat((verdict as StepValidationVerdict.Rejected).reasonCode)
            .isEqualTo("READ_ONLY_WORKSPACE_VIOLATION")
    }

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
