package io.whozoss.factory.forge.bmad

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.sdk.spi.BriefContext
import io.whozoss.factory.sdk.spi.FindingSeverity
import io.whozoss.factory.sdk.spi.FindingStatus
import io.whozoss.factory.sdk.spi.FlowDecision
import io.whozoss.factory.sdk.spi.FragmentOutcome
import io.whozoss.factory.sdk.spi.FragmentView
import io.whozoss.factory.sdk.spi.RunUpdate
import io.whozoss.factory.sdk.spi.RunView
import io.whozoss.factory.sdk.spi.StepOutcomeView
import io.whozoss.factory.sdk.spi.StepValidationContext
import io.whozoss.factory.sdk.spi.StepValidationVerdict
import io.whozoss.factory.sdk.spi.WatchedFileSnapshot
import io.whozoss.factory.sdk.spi.WorkspaceCaptureStatus
import io.whozoss.factory.sdk.spi.WorkspaceDelta
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Comprehensive unit & scenario tests for the Forge BMAD plugin extensions
 * (Lot 2, Lot 3, Lot 4, Lot 6):
 * - [ForgeBmadCatalogContributor]: 10 static flows with unified `ForgeBuilder`/`ForgeVerifier`
 * - [ForgeBmadBriefContributor]: BMAD brief sections and `watchedPaths`
 * - [ForgeBmadStepResultValidator]: phantom artifact detection, `SPEC.md` write-lock,
 *   expected section check, structured `delivery_plan.steps[]` check, and `file_paths` scope check
 * - [ForgeBmadFlowSelector]: full multi-step scenario (`reduce` + `next`), correction loop,
 *   and trivial fast-track (D9)
 * - [ForgeBmadLifecycleObserver]: fragment & run lifecycle events
 */
class ForgeBmadFlowAndValidatorTest {

    private val mapper = ObjectMapper()
    private val catalog = ForgeBmadCatalogContributor()
    private val briefContributor = ForgeBmadBriefContributor()
    private val validator = ForgeBmadStepResultValidator()
    private val selector = ForgeBmadFlowSelector()
    private val observer = ForgeBmadLifecycleObserver()

    @Test
    fun `catalog contributes all 10 valid static JSON flow definitions using unified ForgeBuilder`() {
        val flows = catalog.flows()
        assertThat(flows).hasSize(10)

        val parsed = flows.map { mapper.readValue(it, Map::class.java) }
        val flowTypes = parsed.map { it["workflowType"] as String }
        assertThat(flowTypes).containsExactly(
            "forge.product-spec",
            "forge.product-approval",
            "forge.assessment",
            "forge.ux-design",
            "forge.tech-design",
            "forge.spec-approval",
            "forge.implement-step",
            "forge.correction",
            "forge.code-review",
            "forge.acceptance",
        )

        val implementStep = parsed.single { it["workflowType"] == "forge.implement-step" }
        val steps = (implementStep["steps"] as List<*>).filterIsInstance<Map<*, *>>()
        val builderResp = steps.first { it["id"] == "step-implementation" }["responsibility"] as Map<*, *>
        assertThat(builderResp["name"]).isEqualTo("ForgeBuilder")
    }

    @Test
    fun `validator rejects phantom SPEC_md artifact when content sha256 did not change`() {
        val run = RunView(runId = "r-1", runType = "forge-controller-v1")
        val delta = WorkspaceDelta(
            captureStatus = WorkspaceCaptureStatus.COMPLETE,
            watchedFiles = mapOf(
                "SPEC.md" to WatchedFileSnapshot(
                    path = "SPEC.md",
                    existsBefore = true,
                    sha256Before = "sha-same",
                    existsAfter = true,
                    sha256After = "sha-same",
                ),
                ".memlog.md" to WatchedFileSnapshot(
                    path = ".memlog.md",
                    existsBefore = true,
                    sha256Before = "sha-1",
                    existsAfter = true,
                    sha256After = "sha-2",
                ),
            ),
        )
        val verdict = validator.validate(
            StepValidationContext(
                run = run,
                flowType = "forge.product-spec",
                stepId = "product-specification",
                agentName = "ForgeProductWorker",
                workspaceDelta = delta,
            ),
        )
        assertThat(verdict).isInstanceOf(StepValidationVerdict.Rejected::class.java)
        val rejected = verdict as StepValidationVerdict.Rejected
        assertThat(rejected.reasonCode).isEqualTo("PHANTOM_SPEC_ARTIFACT")
        assertThat(rejected.findings.single().severity).isEqualTo(FindingSeverity.CRITICAL)
    }

    @Test
    fun `validator enforces SPEC_md write-lock on non-authoring agents like ForgeBuilder and Searcher`() {
        val run = RunView(runId = "r-1", runType = "forge-controller-v1")
        val deltaWithUnauthorizedSpecEdit = WorkspaceDelta(
            captureStatus = WorkspaceCaptureStatus.COMPLETE,
            changedFilesDuringTurn = mapOf("SPEC.md" to "MODIFIED"),
            watchedFiles = mapOf(
                "SPEC.md" to WatchedFileSnapshot(
                    path = "SPEC.md",
                    existsBefore = true,
                    sha256Before = "sha-before",
                    existsAfter = true,
                    sha256After = "sha-unauthorized",
                ),
            ),
        )
        val verdict = validator.validate(
            StepValidationContext(
                run = run,
                flowType = "forge.implement-step",
                stepId = "step-implementation",
                agentName = "ForgeBuilder",
                workspaceDelta = deltaWithUnauthorizedSpecEdit,
            ),
        )
        assertThat(verdict).isInstanceOf(StepValidationVerdict.Rejected::class.java)
        assertThat((verdict as StepValidationVerdict.Rejected).reasonCode).isEqualTo("SPEC_WRITE_LOCK_VIOLATION")
    }

    @Test
    fun `validator enforces step file_paths scope on ForgeBuilder`() {
        val run = RunView(runId = "r-1", runType = "forge-controller-v1")
        val deltaWithOutOfScopeFile = WorkspaceDelta(
            captureStatus = WorkspaceCaptureStatus.COMPLETE,
            changedFilesDuringTurn = mapOf(
                "src/allowed.kt" to "MODIFIED",
                "src/forbidden.kt" to "MODIFIED",
            ),
            watchedFiles = mapOf(
                "SPEC.md" to WatchedFileSnapshot("SPEC.md", true, "s1", true, "s1"),
            ),
        )
        val verdict = validator.validate(
            StepValidationContext(
                run = run,
                flowType = "forge.implement-step",
                stepId = "step-implementation",
                agentName = "ForgeBuilder",
                fragmentInputs = mapOf(
                    "step_index" to 0,
                    "step" to mapOf("file_paths" to listOf("src/allowed.kt")),
                ),
                workspaceDelta = deltaWithOutOfScopeFile,
            ),
        )
        assertThat(verdict).isInstanceOf(StepValidationVerdict.Rejected::class.java)
        val rejected = verdict as StepValidationVerdict.Rejected
        assertThat(rejected.reasonCode).isEqualTo("STEP_SCOPE_VIOLATION")
        assertThat(rejected.findings.single().filePath).isEqualTo("src/forbidden.kt")
    }

    @Test
    fun `selector drives full multi-step BMAD scenario and trivial fast-track scenario (D9)`() {
        // --- Scenario A: Trivial Fast-Track (D9) ---
        val initialView = RunView(runId = "run-trivial", runType = "forge-controller-v1", ticket = "WZ-42")
        val (startUpdate, startDecision) = selector.start(initialView)
        assertThat((startDecision as FlowDecision.Apply).flowType).isEqualTo("forge.product-spec")

        var state = startUpdate.newDomainState!!
        val afterProductSpec = selector.reduce(
            initialView.copy(domainState = state),
            FragmentOutcome("f1", "forge.product-spec", "completed"),
        )
        state = afterProductSpec.newDomainState!!
        val afterTrivialProductApproval = selector.reduce(
            initialView.copy(domainState = state),
            FragmentOutcome("f2", "forge.product-approval", "completed"),
        )
        state = afterTrivialProductApproval.newDomainState!!
        // After product-spec -> product-approval -> assessment (ux_applicable=false, trivial=true)
        val afterAssessmentUpdate = selector.reduce(
            initialView.copy(domainState = state),
            FragmentOutcome(
                fragmentId = "f3",
                flowType = "forge.assessment",
                status = "completed",
                stepOutcomes = listOf(
                    StepOutcomeView("ux-assessment", "agent", "completed", report = mapOf("ux_applicable" to false)),
                    StepOutcomeView(
                        "technical-assessment",
                        "agent",
                        "completed",
                        report = mapOf(
                            "trivial" to true,
                            "trivial_scope" to mapOf(
                                "file_paths" to listOf("apps/client/src/button.ts"),
                                "verification" to "front-verify",
                            ),
                        ),
                    ),
                ),
            ),
        )
        state = afterAssessmentUpdate.newDomainState!!
        val trivialDecision = selector.next(
            initialView.copy(
                domainState = state,
                fragments = listOf(FragmentView("f3", 3, "forge.assessment", "completed")),
            ),
        )
        // Trivial fast-track jumps straight to forge.implement-step (skipping ux-design, tech-design, spec-approval!)
        assertThat(trivialDecision).isInstanceOf(FlowDecision.Apply::class.java)
        val applyTrivial = trivialDecision as FlowDecision.Apply
        assertThat(applyTrivial.flowType).isEqualTo("forge.implement-step")
        assertThat(applyTrivial.inputs["step_index"]).isEqualTo(0)
        assertThat(applyTrivial.inputs["verifications"]).isEqualTo(mapOf("step-build-test" to "front-verify"))

        // --- Scenario B: Multi-step plan unfolding + correction + completeness ---
        val multiStart = selector.start(initialView).first.newDomainState!!
        val multiAfterProduct = selector.reduce(
            initialView.copy(domainState = multiStart),
            FragmentOutcome("m1", "forge.product-spec", "completed"),
        )
        state = selector.reduce(
            initialView.copy(domainState = multiAfterProduct.newDomainState!!),
            FragmentOutcome("m2", "forge.product-approval", "completed"),
        ).newDomainState!!
        val multiSteps = listOf(
            mapOf("id" to "s1", "title" to "Back API", "file_paths" to listOf("src/Api.kt"), "verification" to "back-test"),
            mapOf("id" to "s2", "title" to "Front UI", "file_paths" to listOf("src/Ui.ts"), "verification" to "front-test"),
        )
        val afterTechUpdate = selector.reduce(
            initialView.copy(domainState = state),
            FragmentOutcome(
                fragmentId = "f5",
                flowType = "forge.tech-design",
                status = "completed",
                stepOutcomes = listOf(
                    StepOutcomeView(
                        "technical-design",
                        "agent",
                        "completed",
                        report = mapOf("delivery_plan" to mapOf("steps" to multiSteps)),
                    ),
                ),
            ),
        )
        state = afterTechUpdate.newDomainState!!
        val afterSpecApproval = selector.reduce(
            initialView.copy(domainState = state),
            FragmentOutcome("f6", "forge.spec-approval", "completed"),
        )
        state = afterSpecApproval.newDomainState!!
        val launchStep0 = selector.next(
            initialView.copy(
                domainState = state,
                fragments = listOf(FragmentView("f6", 6, "forge.spec-approval", "completed")),
            ),
        ) as FlowDecision.Apply
        assertThat(launchStep0.flowType).isEqualTo("forge.implement-step")
        assertThat(launchStep0.inputs["step_index"]).isEqualTo(0)

        // Step 0 fails -> selector schedules forge.correction with loopKey = "correction:step-0"
        val afterStep0Fail = selector.reduce(
            initialView.copy(domainState = state),
            FragmentOutcome(
                fragmentId = "f7",
                flowType = "forge.implement-step",
                status = "failed",
                stepOutcomes = listOf(StepOutcomeView("step-build-test", "code", "failed", errorCode = "BUILD_KO")),
            ),
        )
        state = afterStep0Fail.newDomainState!!
        val correctionDecision = selector.next(
            initialView.copy(
                domainState = state,
                fragments = listOf(FragmentView("f7", 7, "forge.implement-step", "failed")),
            ),
        ) as FlowDecision.Apply
        assertThat(correctionDecision.flowType).isEqualTo("forge.correction")
        assertThat(correctionDecision.loopKey).isEqualTo("correction:step-0")

        // Correction succeeds -> advances to step 1 (`current_step_index == 1`)
        val afterCorrectionPass = selector.reduce(
            initialView.copy(
                domainState = state,
                fragments = listOf(
                    FragmentView(
                        "f8",
                        8,
                        "forge.correction",
                        "completed",
                        inputs = mapOf("step_index" to 0),
                    ),
                ),
            ),
            FragmentOutcome(
                "f8",
                "forge.correction",
                "completed",
                stepOutcomes = listOf(StepOutcomeView("step-verification", "agent", "completed")),
            ),
        )
        state = afterCorrectionPass.newDomainState!!
        val launchStep1 = selector.next(
            initialView.copy(
                domainState = state,
                fragments = listOf(FragmentView("f8", 8, "forge.correction", "completed")),
            ),
        ) as FlowDecision.Apply
        assertThat(launchStep1.flowType).isEqualTo("forge.implement-step")
        assertThat(launchStep1.inputs["step_index"]).isEqualTo(1)

        // Step 1 succeeds -> advances to forge.code-review
        val afterStep1Pass = selector.reduce(
            initialView.copy(
                domainState = state,
                fragments = listOf(
                    FragmentView(
                        "f9",
                        9,
                        "forge.implement-step",
                        "completed",
                        inputs = mapOf("step_index" to 1),
                    ),
                ),
            ),
            FragmentOutcome(
                "f9",
                "forge.implement-step",
                "completed",
                stepOutcomes = listOf(StepOutcomeView("step-verification", "agent", "completed")),
            ),
        )
        state = afterStep1Pass.newDomainState!!
        val launchReview = selector.next(
            initialView.copy(
                domainState = state,
                fragments = listOf(FragmentView("f9", 9, "forge.implement-step", "completed")),
            ),
        ) as FlowDecision.Apply
        assertThat(launchReview.flowType).isEqualTo("forge.code-review")

        // Code review succeeds -> advances to forge.acceptance
        val afterReviewPass = selector.reduce(
            initialView.copy(domainState = state),
            FragmentOutcome("f10", "forge.code-review", "completed"),
        )
        state = afterReviewPass.newDomainState!!
        val launchAcceptance = selector.next(
            initialView.copy(
                domainState = state,
                fragments = listOf(FragmentView("f10", 10, "forge.code-review", "completed")),
            ),
        ) as FlowDecision.Apply
        assertThat(launchAcceptance.flowType).isEqualTo("forge.acceptance")

        // Acceptance succeeds -> completeness check passes -> Complete!
        val afterAcceptancePass = selector.reduce(
            initialView.copy(domainState = state),
            FragmentOutcome("f11", "forge.acceptance", "completed"),
        )
        state = afterAcceptancePass.newDomainState!!
        val finalDecision = selector.next(
            initialView.copy(
                domainState = state,
                fragments = listOf(FragmentView("f11", 11, "forge.acceptance", "completed")),
            ),
        )
        assertThat(finalDecision).isInstanceOf(FlowDecision.Complete::class.java)

        // Verify brief contributor & lifecycle observer
        val brief = briefContributor.contribute(
            BriefContext(
                run = initialView.copy(domainState = state),
                flowType = "forge.implement-step",
                stepId = "step-implementation",
                agentName = "ForgeBuilder",
                fragmentInputs = mapOf("step_index" to 0, "step" to multiSteps[0]),
            ),
        )
        assertThat(brief.watchedPaths).contains("SPEC.md", ".memlog.md")
        assertThat(brief.sections.single()).contains("Unified Builder Contract")

        observer.onRunFinished(initialView.copy(domainState = state), "completed")
        assertThat(observer.events()).hasSize(1)
        assertThat(observer.events().single().kind).isEqualTo("run_finished")
    }

    @Test
    fun `selector retry after assessment escalation relaunches assessment`() {
        val decision = selector.next(
            RunView(
                runId = "run-retry",
                runType = "forge-controller-v1",
                domainState = mapOf("spec_path" to "planning/WZ-1/SPEC.md"),
                fragments = listOf(
                    FragmentView("f3", 3, "forge.assessment", "ESCALATION_RESOLVED"),
                ),
            ),
        )
        assertThat(decision).isInstanceOf(FlowDecision.Apply::class.java)
        assertThat((decision as FlowDecision.Apply).flowType).isEqualTo("forge.assessment")
    }

    @Test
    fun `successful correction only resolves findings explicitly verified and keeps requested step index`() {
        val run = RunView(
            runId = "run-correction",
            runType = "forge-controller-v1",
            domainState = mapOf(
                "current_step_index" to 2,
                "delivery_plan" to mapOf(
                    "steps" to listOf(
                        mapOf("id" to "s0"),
                        mapOf("id" to "s1"),
                    ),
                ),
                "revisions" to mapOf("product" to 1, "ux" to 0, "tech" to 1, "plan" to 1),
                "step_evidence" to emptyMap<String, Any?>(),
            ),
            findings = listOf(
                io.whozoss.factory.sdk.spi.FindingView(
                    id = "targeted",
                    code = "REVIEW_A",
                    severity = FindingSeverity.MAJOR,
                    status = FindingStatus.OPEN,
                    message = "Targeted",
                ),
                io.whozoss.factory.sdk.spi.FindingView(
                    id = "unrelated",
                    code = "REVIEW_B",
                    severity = FindingSeverity.MAJOR,
                    status = FindingStatus.OPEN,
                    message = "Unrelated",
                ),
            ),
            fragments = listOf(
                FragmentView("fix", 8, "forge.correction", "completed", inputs = mapOf("step_index" to 1)),
            ),
        )
        val update = selector.reduce(
            run,
            FragmentOutcome(
                "fix",
                "forge.correction",
                "completed",
                stepOutcomes = listOf(
                    StepOutcomeView(
                        "step-implementation",
                        "agent",
                        "completed",
                        report = mapOf("resolved_finding_ids" to listOf("unrelated")),
                    ),
                    StepOutcomeView(
                        "step-verification",
                        "agent",
                        "completed",
                        report = mapOf("resolved_finding_ids" to listOf("targeted")),
                    ),
                ),
            ),
        )
        assertThat(update.findingResolutions.keys).containsExactly("targeted")
        assertThat(update.newDomainState!!["current_step_index"]).isEqualTo(2)
        @Suppress("UNCHECKED_CAST")
        val evidence = update.newDomainState!!["step_evidence"] as Map<String, Any?>
        assertThat(evidence).containsKey("1")
    }

    @Test
    fun `delivery outcome outside plan bounds creates critical finding without evidence or resolutions`() {
        val update = selector.reduce(
            RunView(
                runId = "invalid-index",
                runType = "forge-controller-v1",
                domainState = mapOf(
                    "current_step_index" to 0,
                    "step_evidence" to emptyMap<String, Any?>(),
                ),
                findings = listOf(
                    io.whozoss.factory.sdk.spi.FindingView(
                        id = "must-stay-open",
                        code = "X",
                        severity = FindingSeverity.MAJOR,
                        status = FindingStatus.OPEN,
                        message = "Unresolved",
                    ),
                ),
                fragments = listOf(
                    FragmentView("bad", 1, "forge.correction", "completed", inputs = mapOf("step_index" to 1)),
                ),
            ),
            FragmentOutcome(
                "bad",
                "forge.correction",
                "completed",
                stepOutcomes = listOf(
                    StepOutcomeView(
                        "step-verification",
                        "agent",
                        "completed",
                        report = mapOf("resolved_finding_ids" to listOf("must-stay-open")),
                    ),
                ),
            ),
        )
        assertThat(update.addedFindings).hasSize(1)
        val finding = update.addedFindings.single()
        assertThat(finding.code).isEqualTo("INVALID_DELIVERY_STEP_INDEX")
        assertThat(finding.severity).isEqualTo(FindingSeverity.CRITICAL)
        assertThat(update.findingResolutions).isEmpty()
        assertThat(update.newDomainState).isNull()
    }

    @Test
    fun `implementation invalidates review and acceptance evidence and never lets builder resolve findings`() {
        val run = RunView(
            runId = "run-invalidate",
            runType = "forge-controller-v1",
            domainState = mapOf(
                "current_step_index" to 1,
                "delivery_plan" to mapOf("steps" to listOf(mapOf("id" to "s0"))),
                "revisions" to mapOf("product" to 1, "ux" to 0, "tech" to 1, "plan" to 1),
                "step_evidence" to emptyMap<String, Any?>(),
                "review_evidence" to mapOf("status" to "passed"),
                "acceptance_evidence" to mapOf("status" to "passed"),
            ),
            findings = listOf(
                io.whozoss.factory.sdk.spi.FindingView(
                    id = "builder-claimed",
                    code = "X",
                    severity = FindingSeverity.MAJOR,
                    status = FindingStatus.OPEN,
                    message = "Must remain open",
                ),
            ),
            fragments = listOf(FragmentView("fix-old", 9, "forge.correction", "completed", inputs = mapOf("step_index" to 0))),
        )
        val update = selector.reduce(
            run,
            FragmentOutcome(
                "fix-old",
                "forge.correction",
                "completed",
                stepOutcomes = listOf(
                    StepOutcomeView(
                        "step-correction",
                        "agent",
                        "completed",
                        report = mapOf("resolved_finding_ids" to listOf("builder-claimed")),
                    ),
                    StepOutcomeView("step-verification", "agent", "completed"),
                ),
            ),
        )
        assertThat(update.findingResolutions).isEmpty()
        assertThat(update.newDomainState!!["current_step_index"]).isEqualTo(1)
        assertThat(update.newDomainState!!).doesNotContainKeys("review_evidence", "acceptance_evidence")
    }

    @Test
    fun `failed review removes stale successful review and acceptance evidence`() {
        val state = mapOf<String, Any?>(
            "review_evidence" to mapOf("status" to "passed"),
            "acceptance_evidence" to mapOf("status" to "passed"),
        )
        val update = selector.reduce(
            RunView(runId = "review", runType = "forge-controller-v1", domainState = state),
            FragmentOutcome(
                "review-failed",
                "forge.code-review",
                "completed",
                listOf(
                    StepOutcomeView(
                        "code-review",
                        "agent",
                        "completed",
                        report = mapOf("findings" to listOf(mapOf("severity" to "MAJOR"))),
                    ),
                ),
            ),
        )
        assertThat(update.newDomainState!!).doesNotContainKeys("review_evidence", "acceptance_evidence")
    }

    @Test
    fun `completion rejects an empty delivery plan even when acceptance completed`() {
        val decision = selector.next(
            RunView(
                runId = "empty-plan",
                runType = "forge-controller-v1",
                domainState = mapOf(
                    "delivery_plan" to mapOf("steps" to emptyList<Any>()),
                    "revisions" to mapOf("product" to 1, "plan" to 1, "product_approved" to 1),
                ),
                fragments = listOf(FragmentView("accept", 10, "forge.acceptance", "completed")),
            ),
        )
        assertThat(decision).isInstanceOf(FlowDecision.EscalateHuman::class.java)
        assertThat((decision as FlowDecision.EscalateHuman).reason).isEqualTo("MISSING_DELIVERY_PLAN")
    }

    @Test
    fun `read-only assessment rejects any repository modification`() {
        val verdict = validator.validate(
            StepValidationContext(
                run = RunView(runId = "r-ro", runType = "forge-controller-v1"),
                flowType = "forge.assessment",
                stepId = "ux-assessment",
                agentName = "ForgeUXDesigner",
                workspaceDelta = WorkspaceDelta(
                    captureStatus = WorkspaceCaptureStatus.COMPLETE,
                    changedFilesDuringTurn = mapOf("src/unrelated.ts" to "MODIFIED"),
                ),
            ),
        )
        assertThat(verdict).isInstanceOf(StepValidationVerdict.Rejected::class.java)
        assertThat((verdict as StepValidationVerdict.Rejected).reasonCode)
            .isEqualTo("READ_ONLY_WORKSPACE_VIOLATION")
    }

    @Test
    fun `lifecycle observer preserves Jira and dedup without creating sprint-status file (Lot 6)`() {
        val tempRepo = java.nio.file.Files.createTempDirectory("forge-lot6-sprint")
        val transitions = mutableListOf<Pair<String, String>>()
        val comments = mutableListOf<Pair<String, String>>()

        val stubJira = object : io.whozoss.factory.forge.port.JiraClient {
            override fun isConfigured(): Boolean = true
            override fun missingCredentials(): List<String> = emptyList()
            override fun fetchTicket(ticketId: String): io.whozoss.factory.forge.port.JiraTicketFetch =
                error("not used")

            override fun transitionTicket(ticketId: String, targetStatusOrTransitionName: String): Boolean {
                transitions += ticketId to targetStatusOrTransitionName
                return true
            }

            override fun postComment(ticketId: String, markdownBody: String): Boolean {
                comments += ticketId to markdownBody
                return true
            }
        }

        val lot6Observer = ForgeBmadLifecycleObserver(stubJira)
        val runView = RunView(
            runId = "run-lot6",
            runType = "forge-controller-v1",
            ticket = "WZ-600",
            domainRevision = 3L,
            domainState = mapOf(
                "repo_root" to tempRepo.toString(),
                "sprint_status_path" to "sprint-status.yaml",
            ),
            fragments = listOf(FragmentView("run-lot6__f1", 1, "forge.implement-step", "running")),
        )

        // Fragment start still notifies Jira; file synchronization is disabled.
        lot6Observer.onFragmentStarted(runView, runView.fragments.single())
        // Duplicate call is deduplicated
        lot6Observer.onFragmentStarted(runView, runView.fragments.single())

        assertThat(transitions).containsExactly("WZ-600" to "In Progress")
        assertThat(comments).hasSize(1)

        val sprintYaml = tempRepo.resolve("sprint-status.yaml")
        assertThat(java.nio.file.Files.exists(sprintYaml)).isFalse()
        val outcome = FragmentOutcome("run-lot6__f1", "forge.implement-step", "completed")
        lot6Observer.onFragmentFinished(runView, outcome)
        lot6Observer.onFragmentFinished(runView, outcome)
        assertThat(java.nio.file.Files.exists(sprintYaml)).isFalse()

        // Run finish still transitions Jira to Done, without touching the filesystem.
        lot6Observer.onRunFinished(runView, "completed")
        assertThat(transitions).containsExactly(
            "WZ-600" to "In Progress",
            "WZ-600" to "Done",
        )
        lot6Observer.onRunFinished(runView, "completed")
        assertThat(comments).hasSize(2)
        assertThat(lot6Observer.events()).hasSize(3)
        assertThat(lot6Observer.events().map { it.sprintStatusUpdated }).containsOnly(false)
        assertThat(java.nio.file.Files.exists(sprintYaml)).isFalse()

        // Configured traversal is ignored, not interpreted as a filesystem destination.
        val traversalRun = runView.copy(
            runId = "run-traversal",
            domainState = mapOf(
                "repo_root" to tempRepo.toString(),
                "sprint_status_path" to "../escaped.yaml",
            ),
        )
        lot6Observer.onRunFinished(traversalRun, "completed")
        assertThat(lot6Observer.events().last().sprintStatusUpdated).isFalse()
    }

    @Test
    fun `all lifecycle callbacks leave existing files unchanged and create no files for traversal or symlink paths`() {
        val sandbox = java.nio.file.Files.createTempDirectory("forge-lifecycle-no-writes")
        val repo = java.nio.file.Files.createDirectory(sandbox.resolve("repo"))
        val outside = java.nio.file.Files.createDirectory(sandbox.resolve("outside"))
        val existing = java.nio.file.Files.write(repo.resolve("sprint-status.yaml"), byteArrayOf(0, 1, 2, 13, 10, -1))
        val escaped = java.nio.file.Files.writeString(outside.resolve("existing.yaml"), "must remain unchanged\n")
        val directoryLink = java.nio.file.Files.createSymbolicLink(repo.resolve("linked"), outside)
        val fileLink = java.nio.file.Files.createSymbolicLink(repo.resolve("status-link.yaml"), escaped)
        val rootLink = java.nio.file.Files.createSymbolicLink(sandbox.resolve("repo-link"), repo)
        val expectedBytes = java.nio.file.Files.readAllBytes(existing)
        val escapedBytes = java.nio.file.Files.readAllBytes(escaped)
        fun entries() = java.nio.file.Files.walk(sandbox).use { paths ->
            paths.map { sandbox.relativize(it).toString() }.sorted().toList()
        }
        val before = entries()
        val destinations = listOf(
            repo.toString() to "sprint-status.yaml",
            repo.toString() to "new/nested/status.yaml",
            repo.toString() to "../outside/existing.yaml",
            repo.toString() to "../outside/new.yaml",
            repo.toString() to "linked/existing.yaml",
            repo.toString() to "linked/new.yaml",
            repo.toString() to "status-link.yaml",
            rootLink.toString() to "new.yaml",
        )
        destinations.forEachIndexed { index, (repoRoot, statusPath) ->
            val observer = ForgeBmadLifecycleObserver()
            val fragment = FragmentView("fragment-$index", 1, "forge.implement-step", "running")
            val run = RunView("no-write-$index", "forge-controller-v1", domainState = mapOf(
                "repo_root" to repoRoot, "sprint_status_path" to statusPath,
            ))
            val callbacks: List<() -> Unit> = listOf(
                { observer.onFragmentStarted(run, fragment) },
                { observer.onFragmentFinished(run, FragmentOutcome(fragment.fragmentId, fragment.flowType, "completed")) },
                { observer.onRunFinished(run, "completed") },
            )
            callbacks.forEach { callback ->
                callback()
                assertThat(java.nio.file.Files.readAllBytes(existing)).isEqualTo(expectedBytes)
                assertThat(java.nio.file.Files.readAllBytes(escaped)).isEqualTo(escapedBytes)
                assertThat(entries()).isEqualTo(before)
                assertThat(java.nio.file.Files.readSymbolicLink(directoryLink)).isEqualTo(outside)
                assertThat(java.nio.file.Files.readSymbolicLink(fileLink)).isEqualTo(escaped)
                assertThat(java.nio.file.Files.readSymbolicLink(rootLink)).isEqualTo(repo)
            }
            assertThat(observer.events()).hasSize(3)
            assertThat(observer.events().map { it.sprintStatusUpdated }).containsOnly(false)
        }
    }
}
