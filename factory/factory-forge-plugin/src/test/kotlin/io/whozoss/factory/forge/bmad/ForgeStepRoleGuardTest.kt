package io.whozoss.factory.forge.bmad

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.sdk.spi.BriefContext
import io.whozoss.factory.sdk.spi.RunView
import io.whozoss.factory.sdk.spi.StepValidationContext
import io.whozoss.factory.sdk.spi.StepValidationVerdict
import io.whozoss.factory.sdk.spi.WatchedFileSnapshot
import io.whozoss.factory.sdk.spi.WorkspaceDelta
import io.whozoss.factory.sdk.spi.HumanCheckpointContext
import io.whozoss.factory.sdk.spi.HumanCheckpointVerdict
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
    fun `final join requires both audited approvals on the current work candidate`() {
        val packet = mapOf("repository" to "whoz", "commit" to "a".repeat(40), "manifestSha256" to "b".repeat(64))
        val workCommit = "c".repeat(40)
        val state = mapOf<String, Any?>(
            "approvedPacketCandidate" to packet,
            "build_report" to mapOf("manifest_sha256" to packet["manifestSha256"], "work_commit" to workCommit),
            "verification_report" to mapOf("work_commit" to workCommit),
            "review_report" to mapOf("work_commit" to workCommit, "pr_head_commit" to workCommit,
                "pr_number" to 8488, "observed_pr" to mapOf("prHeadCommit" to workCommit, "prNumber" to 8488)),
            "deployment_evidence" to mapOf("workCommit" to workCommit, "tag" to "integration-talent-portal_rc100",
                "target" to "forge-talent-portal", "receipt" to "github-actions:run/1/job/2"),
        )
        val policy = ForgeDeliveryHumanCheckpointPolicy()
        val base = run.copy(runType = "forge.delivery", domainState = state)
        fun decision(step: String): Map<String, Any?> {
            val facts = (policy.present(HumanCheckpointContext(base, "forge.delivery", step))
                as HumanCheckpointVerdict.Allowed).facts
            return mapOf("stepId" to step, "outcome" to "pass",
                "source" to mapOf("kind" to "factory-human", "actorId" to "member-1"),
                "facts" to facts + mapOf("gate" to step, "decidedAt" to "2026-11-18T22:00:00Z"))
        }
        val technical = decision("technical-validation")
        val functional = decision("functional-validation")
        fun verdict(decisions: List<Map<String, Any?>>) = validator.validate(StepValidationContext(
            run = base.copy(humanDecisions = decisions), flowType = "forge.delivery",
            stepId = "delivery-completion", agentName = "ForgeCloser",
        ))
        assertThat((verdict(listOf(technical)) as StepValidationVerdict.Rejected).reasonCode)
            .isEqualTo("HUMAN_APPROVAL_MISSING")
        assertThat((verdict(listOf(technical, functional + ("facts" to mapOf("gate" to "functional-validation"))))
            as StepValidationVerdict.Rejected).reasonCode).isEqualTo("HUMAN_APPROVAL_STALE")
        assertThat(verdict(listOf(technical, functional))).isInstanceOf(StepValidationVerdict.Accepted::class.java)
    }

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
    fun `delivery implementation plan requires scoped stack and ordered dependencies`() {
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
            "stack" to "backend",
            "depends_on" to null,
            "file_paths" to listOf("src/allowed.kt"),
            "verification" to "./gradlew test",
        )
        val second = step + mapOf(
            "id" to "implement-2",
            "stack" to "frontend",
            "depends_on" to "implement-1",
            "file_paths" to listOf("frontend/allowed.ts"),
            "verification" to "pnpm test",
        )
        val accepted = verdict(listOf(step, second)) as StepValidationVerdict.Accepted
        assertThat((accepted.newDomainState?.get("delivery_plan") as Map<*, *>)["steps"])
            .isEqualTo(listOf(step, second))
        assertThat((verdict(emptyList()) as StepValidationVerdict.Rejected).reasonCode)
            .isEqualTo("MISSING_DELIVERY_PLAN_STEPS")
        for (steps in listOf(
            listOf(step, step),
            listOf(step + ("file_paths" to listOf("src/../outside.kt"))),
            listOf(step + ("verification" to "")),
            listOf(step - "stack"),
            listOf(step + ("stack" to "full-stack")),
            listOf(step - "depends_on"),
            listOf(step + ("depends_on" to "implement-2")),
            listOf(second, step),
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
    fun `one Build step accounts for every planned backend and frontend item`() {
        val manifest = "b".repeat(64)
        val packet = mapOf("repository" to "whoz", "commit" to "f".repeat(40), "manifestSha256" to manifest)
        val backend = mapOf("id" to "back-1", "stack" to "backend", "file_paths" to listOf("src/api.kt"))
        val frontend = mapOf("id" to "front-2", "stack" to "frontend", "file_paths" to listOf("ui/view.ts"))
        val run = run.copy(runType = "forge.delivery", domainState = mapOf(
            "delivery_plan" to mapOf("steps" to listOf(backend, frontend)),
            "approvedPacketCandidate" to packet,
        ), humanDecisions = listOf(mapOf("stepId" to "packet-approval", "outcome" to "pass",
            "source" to mapOf("kind" to "factory-human", "actorId" to "member-1"),
            "facts" to packet + mapOf("gate" to "packet-approval", "decidedAt" to "2026-11-18T22:00:00Z"))))
        val items = listOf(
            mapOf("step_id" to "back-1", "stack" to "backend", "status" to "PASS",
                "changed_files" to listOf("src/api.kt"), "summary" to "Implemented API"),
            mapOf("step_id" to "front-2", "stack" to "frontend", "status" to "PASS",
                "changed_files" to listOf("ui/view.ts"), "summary" to "Implemented view"),
        )
        val report = mapOf("manifest_sha256" to manifest, "work_commit" to "a".repeat(40), "items" to items)
        fun verdict(value: Map<String, Any?>, delta: WorkspaceDelta = WorkspaceDelta(), agent: String = "ForgeDeveloper") =
            validator.validate(StepValidationContext(run = run, flowType = "forge.delivery",
                stepId = "implementation-build", agentName = agent,
                report = businessReport(mapOf("build_report" to value)), workspaceDelta = delta,
                externalEvidence = packet))

        val accepted = verdict(report, WorkspaceDelta(changedFilesDuringTurn = mapOf("src/api.kt" to "MODIFIED")))
            as StepValidationVerdict.Accepted
        assertThat((accepted.newDomainState?.get("build_report") as Map<*, *>) ["items"]).isEqualTo(items)
        for (invalid in listOf(
            report + ("items" to items.take(1)),
            report + ("items" to items.reversed()),
            report + ("items" to (items.take(1) + (items[1] + ("changed_files" to emptyList<String>())))),
            report + ("items" to (items.take(1) + (items[1] + ("changed_files" to listOf("src/api.kt"))))),
            report + ("manifest_sha256" to "c".repeat(64)),
            report + ("work_commit" to "uncommitted"),
        )) {
            assertThat((verdict(invalid) as StepValidationVerdict.Rejected).reasonCode)
                .isEqualTo("INVALID_BUILD_REPORT")
        }
        assertThat((verdict(report, WorkspaceDelta(changedFilesDuringTurn = mapOf("secrets.txt" to "ADDED")))
            as StepValidationVerdict.Rejected).reasonCode).isEqualTo("BUILD_FILE_SCOPE_VIOLATION")
        assertThat((verdict(report, agent = "BmadBuilder") as StepValidationVerdict.Rejected).reasonCode)
            .isEqualTo("INVALID_BUILD_COORDINATOR")
        val unapproved = validator.validate(StepValidationContext(run = run.copy(humanDecisions = emptyList()),
            flowType = "forge.delivery", stepId = "implementation-build", agentName = "ForgeDeveloper",
            report = businessReport(mapOf("build_report" to report)), externalEvidence = packet))
        assertThat((unapproved as StepValidationVerdict.Rejected).reasonCode)
            .isEqualTo("PACKET_APPROVAL_MISSING_OR_STALE")
    }

    @Test
    fun `deployment observation persists only trusted evidence for the accepted Build commit`() {
        val commit = "a".repeat(40)
        val run = run.copy(runType = "forge.delivery", domainState = mapOf(
            "build_report" to mapOf("work_commit" to commit),
        ))
        val evidence = mapOf<String, Any?>(
            "workCommit" to commit, "tag" to "integration-talent-portal_rc100",
            "receipt" to "github-actions:run/42/job/7",
        )
        fun verdict(observed: Map<String, Any?>) = validator.validate(StepValidationContext(
            run = run, flowType = "forge.delivery", stepId = "integration-observation",
            agentName = "ForgeDeploymentObserver", externalEvidence = observed,
        ))
        val accepted = verdict(evidence) as StepValidationVerdict.Accepted
        assertThat(accepted.newDomainState?.get("deployment_evidence")).isEqualTo(evidence)
        assertThat((verdict(emptyMap()) as StepValidationVerdict.Rejected).reasonCode)
            .isEqualTo("DEPLOYMENT_EVIDENCE_MISSING")
        assertThat((verdict(evidence + ("workCommit" to "b".repeat(40))) as StepValidationVerdict.Rejected).reasonCode)
            .isEqualTo("DEPLOYMENT_EVIDENCE_MISSING")
    }

    @Test
    fun `independent verification covers every planned item in order without writing`() {
        val plan = listOf(
            mapOf("id" to "backend-1", "stack" to "backend", "verification" to "./gradlew test"),
            mapOf("id" to "frontend-2", "stack" to "frontend", "verification" to "pnpm nx test ui"),
        )
        val run = run.copy(runType = "forge.delivery", domainState = mapOf(
            "delivery_plan" to mapOf("steps" to plan),
            "build_report" to mapOf("work_commit" to "a".repeat(40)),
        ))
        val items = listOf(
            mapOf("step_id" to "backend-1", "stack" to "backend", "status" to "PASS",
                "green" to true, "command" to "./gradlew test", "case_id" to "case-back",
                "summary" to "Backend tests passed"),
            mapOf("step_id" to "frontend-2", "stack" to "frontend", "status" to "PASS",
                "green" to true, "command" to "pnpm nx test ui", "case_id" to "case-front",
                "summary" to "Frontend tests passed"),
        )
        fun verdict(receipts: List<Map<String, Any?>>, delta: WorkspaceDelta = WorkspaceDelta()) =
            validator.validate(StepValidationContext(run = run, flowType = "forge.delivery",
                stepId = "implementation-verification", agentName = "ForgeVerifier",
                report = businessReport(mapOf("verification_report" to mapOf(
                    "work_commit" to "a".repeat(40), "items" to receipts))), workspaceDelta = delta))
        val accepted = verdict(items) as StepValidationVerdict.Accepted
        assertThat((accepted.newDomainState?.get("verification_report") as Map<*, *>) ["items"]).isEqualTo(items)
        assertThat((verdict(items.reversed()) as StepValidationVerdict.Rejected).reasonCode)
            .isEqualTo("INVALID_VERIFICATION_REPORT")
        assertThat((verdict(items.take(1)) as StepValidationVerdict.Rejected).reasonCode)
            .isEqualTo("INVALID_VERIFICATION_REPORT")
        assertThat((verdict(items.take(1) + (items[1] + ("green" to false))) as StepValidationVerdict.Rejected).reasonCode)
            .isEqualTo("INVALID_VERIFICATION_REPORT")
        assertThat(verdict(items, WorkspaceDelta(changedFilesDuringTurn = mapOf("ui/view.ts" to "MODIFIED"))))
            .isInstanceOf(StepValidationVerdict.Rejected::class.java)
    }

    @Test
    fun `review cannot pass without the matching Build head and four clean votes`() {
        val commit = "a".repeat(40)
        val run = run.copy(runType = "forge.delivery", domainState = mapOf(
            "build_report" to mapOf("work_commit" to commit),
            "verification_report" to mapOf("work_commit" to commit),
        ))
        val votes = mapOf("clarity_correctness" to "PASS", "scope_architecture" to "PASS",
            "security_permissions" to "PASS", "edge_cases_resilience" to "PASS")
        val clean = mapOf<String, Any?>("status" to "PASS", "work_commit" to commit,
            "pr_head_commit" to commit, "pr_number" to 8488, "votes" to votes,
            "findings" to emptyList<Any>())
        val observed = mapOf<String, Any?>("prHeadCommit" to commit, "prNumber" to 8488,
            "repository" to "biznet-io/whoz", "branch" to "work/talent-portal/ticket")
        fun verdict(report: Map<String, Any?>) = validator.validate(StepValidationContext(
            run = run, flowType = "forge.delivery", stepId = "implementation-review",
            agentName = "Reviewer", report = businessReport(mapOf("review_report" to report)),
            externalEvidence = observed,
        ))
        assertThat(((verdict(clean) as StepValidationVerdict.Accepted).newDomainState?.get("review_report") as Map<*, *>) ["observed_pr"])
            .isEqualTo(observed)
        assertThat((validator.validate(StepValidationContext(
            run = run, flowType = "forge.delivery", stepId = "implementation-review",
            agentName = "ForgeReviewer", report = businessReport(mapOf("review_report" to clean)),
            externalEvidence = observed,
        )) as StepValidationVerdict.Rejected).reasonCode).isEqualTo("INVALID_REVIEWER")
        for (invalid in listOf(
            clean + ("pr_head_commit" to "b".repeat(40)),
            clean + ("votes" to (votes + ("security_permissions" to "FAIL"))),
            clean + ("findings" to listOf(mapOf("severity" to "major", "description" to "Contract defect"))),
        )) {
            assertThat((verdict(invalid) as StepValidationVerdict.Rejected).reasonCode)
                .isEqualTo("INVALID_REVIEW_REPORT")
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
            agentName = "ForgeDeveloper",
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
