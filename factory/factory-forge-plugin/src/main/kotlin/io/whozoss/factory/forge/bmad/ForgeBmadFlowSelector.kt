package io.whozoss.factory.forge.bmad

import io.whozoss.factory.sdk.spi.FactoryFlowSelector
import io.whozoss.factory.sdk.spi.FindingResolution
import io.whozoss.factory.sdk.spi.FindingSeverity
import io.whozoss.factory.sdk.spi.FindingStatus
import io.whozoss.factory.sdk.spi.FlowDecision
import io.whozoss.factory.sdk.spi.FragmentOutcome
import io.whozoss.factory.sdk.spi.NewFinding
import io.whozoss.factory.sdk.spi.RunUpdate
import io.whozoss.factory.sdk.spi.RunView
import org.pf4j.Extension

/**
 * Deterministic 2-phase (`reduce` + `next`) selector driving `forge-controller-v1`
 * runs across the 10 static BMAD flow fragments (Lot 4, §4 & §7.2).
 *
 * Enforces:
 * - Product spec -> Product approval (with rejection loop)
 * - Assessment -> conditional UX design / Tech design / Trivial fast-track (D9)
 * - Combined Spec approval -> N-step delivery plan unfolding (`forge.implement-step`)
 * - Bounded step corrections (`forge.correction`) on build/test or verifier failure
 * - Adversarial Code Review -> Acceptance -> Strict completeness check (D10: all
 *   evidence aligned with current spec/plan revisions AND zero open CRITICAL/MAJOR findings).
 */
@Extension
class ForgeBmadFlowSelector : FactoryFlowSelector {

    override fun runTypes(): Set<String> = SUPPORTED_RUN_TYPES

    override fun selectorVersion(): String = SELECTOR_VERSION

    override fun start(run: RunView): Pair<RunUpdate, FlowDecision> {
        val initialDomainState = linkedMapOf<String, Any?>(
            "schemaVersion" to 1,
            "spec_path" to (run.domainState["spec_path"] ?: ForgeBmadBriefContributor.DEFAULT_SPEC_PATH),
            "memlog_path" to (run.domainState["memlog_path"] ?: ForgeBmadBriefContributor.DEFAULT_MEMLOG_PATH),
            "revisions" to linkedMapOf(
                "product" to 0,
                "ux" to 0,
                "tech" to 0,
                "plan" to 0,
                "product_approved" to 0,
                "ux_approved" to 0,
                "tech_approved" to 0,
            ),
            "ux_applicable" to false,
            "tech_spec_applicable" to true,
            "current_step_index" to 0,
            "step_evidence" to emptyMap<String, Any?>(),
        )
        return RunUpdate(newDomainState = initialDomainState) to FlowDecision.Apply(
            flowType = ForgeBmadCatalogContributor.FLOW_PRODUCT_SPEC,
            inputs = buildMap {
                if (run.ticket != null) put("ticket", run.ticket)
                put("spec_path", initialDomainState["spec_path"])
            },
            reason = "Initial product specification fragment",
            loopKey = "product-spec",
            maxIterations = 3,
        )
    }

    override fun reduce(run: RunView, outcome: FragmentOutcome): RunUpdate {
        val state = LinkedHashMap(run.domainState)
        val revisions = LinkedHashMap(revisionsOf(state))
        val currentStepIndex = (state["current_step_index"] as? Number)?.toInt() ?: 0
        val fragmentInputs = run.fragments.lastOrNull { it.fragmentId == outcome.fragmentId }?.inputs.orEmpty()
        val outcomeStepIndex = (fragmentInputs["step_index"] as? Number)?.toInt() ?: currentStepIndex

        return when (outcome.flowType) {
            ForgeBmadCatalogContributor.FLOW_PRODUCT_SPEC -> {
                if (outcome.status == STATUS_COMPLETED) {
                    if (((revisions["product"] as? Number)?.toInt() ?: 0) == 0) {
                        revisions["product"] = 1
                    }
                    state["revisions"] = revisions
                }
                RunUpdate(newDomainState = state)
            }

            ForgeBmadCatalogContributor.FLOW_PRODUCT_APPROVAL -> {
                if (outcome.status == STATUS_COMPLETED) {
                    revisions["product_approved"] = (revisions["product"] as? Number)?.toInt() ?: 1
                    state["revisions"] = revisions
                    state["product_approval_rejected"] = false
                } else {
                    state["product_approval_rejected"] = true
                }
                RunUpdate(newDomainState = state)
            }

            ForgeBmadCatalogContributor.FLOW_ASSESSMENT -> {
                if (outcome.status == STATUS_COMPLETED) {
                    for (stepOut in outcome.stepOutcomes) {
                        val rep = extractOutputs(stepOut.report)
                        if (stepOut.stepId == "ux-assessment" && rep.containsKey("ux_applicable")) {
                            state["ux_applicable"] = rep["ux_applicable"] as? Boolean ?: false
                        }
                        if (stepOut.stepId == "technical-assessment") {
                            val trivial = rep["trivial"] as? Boolean ?: false
                            val techApplicable = rep["tech_spec_applicable"] as? Boolean ?: !trivial
                            state["tech_spec_applicable"] = techApplicable
                            rep["trivial_scope"]?.let { state["trivial_scope"] = it }
                            if (!techApplicable) {
                                // Synthesize the single delivery step for the trivial fast-track (D9)
                                @Suppress("UNCHECKED_CAST")
                                val trivialScope = (rep["trivial_scope"] as? Map<String, Any?>)
                                    ?: (state["trivial_scope"] as? Map<String, Any?>)
                                    ?: emptyMap()
                                val filePaths = (trivialScope["file_paths"] as? List<*>)?.filterIsInstance<String>().orEmpty()
                                val verification = (trivialScope["verification"] as? String) ?: "default-verify"
                                state["delivery_plan"] = mapOf(
                                    "steps" to listOf(
                                        mapOf(
                                            "id" to "trivial-step-0",
                                            "title" to "Trivial implementation",
                                            "file_paths" to filePaths,
                                            "verification" to verification,
                                        ),
                                    ),
                                )
                                revisions["plan"] = 1
                                state["revisions"] = revisions
                                state["current_step_index"] = 0
                            }
                        }
                    }
                }
                RunUpdate(newDomainState = state)
            }

            ForgeBmadCatalogContributor.FLOW_UX_DESIGN -> {
                if (outcome.status == STATUS_COMPLETED) {
                    if (((revisions["ux"] as? Number)?.toInt() ?: 0) == 0) {
                        revisions["ux"] = 1
                    }
                    state["revisions"] = revisions
                }
                RunUpdate(newDomainState = state)
            }

            ForgeBmadCatalogContributor.FLOW_TECH_DESIGN -> {
                if (outcome.status == STATUS_COMPLETED) {
                    val techStep = outcome.stepOutcomes.lastOrNull { it.stepId == "technical-design" }
                    val rep = techStep?.let { extractOutputs(it.report) }.orEmpty()
                    @Suppress("UNCHECKED_CAST")
                    val plan = (rep["delivery_plan"] as? Map<String, Any?>)
                        ?: (state["delivery_plan"] as? Map<String, Any?>)
                    if (plan != null) {
                        state["delivery_plan"] = plan
                    }
                    if (((revisions["tech"] as? Number)?.toInt() ?: 0) == 0) {
                        revisions["tech"] = 1
                    }
                    if (((revisions["plan"] as? Number)?.toInt() ?: 0) == 0) {
                        revisions["plan"] = 1
                    }
                    state["revisions"] = revisions
                    state["current_step_index"] = 0
                }
                RunUpdate(newDomainState = state)
            }

            ForgeBmadCatalogContributor.FLOW_SPEC_APPROVAL -> {
                if (outcome.status == STATUS_COMPLETED) {
                    revisions["ux_approved"] = (revisions["ux"] as? Number)?.toInt() ?: 0
                    revisions["tech_approved"] = (revisions["tech"] as? Number)?.toInt() ?: 0
                    state["revisions"] = revisions
                    state["spec_approval_rejected"] = false
                } else {
                    state["spec_approval_rejected"] = true
                }
                RunUpdate(newDomainState = state)
            }

            ForgeBmadCatalogContributor.FLOW_IMPLEMENT_STEP,
            ForgeBmadCatalogContributor.FLOW_CORRECTION,
            -> {
                val stepEvidence = LinkedHashMap(
                    @Suppress("UNCHECKED_CAST")
                    (state["step_evidence"] as? Map<String, Any?>).orEmpty(),
                )
                val planSteps = planStepsOf(state)
                if (outcomeStepIndex !in planSteps.indices) {
                    return RunUpdate(
                        addedFindings = listOf(
                            NewFinding(
                                code = "INVALID_DELIVERY_STEP_INDEX",
                                severity = FindingSeverity.CRITICAL,
                                message = "Fragment '${outcome.fragmentId}' reported step index $outcomeStepIndex outside delivery plan bounds.",
                                stepId = "step-verification",
                                details = mapOf("stepIndex" to outcomeStepIndex, "planSize" to planSteps.size),
                            ),
                        ),
                    )
                }
                if (outcome.status == STATUS_COMPLETED) {
                    stepEvidence[outcomeStepIndex.toString()] = mapOf(
                        "status" to "passed",
                        "fragmentId" to outcome.fragmentId,
                        "productRev" to ((revisions["product"] as? Number)?.toInt() ?: 0),
                        "uxRev" to ((revisions["ux"] as? Number)?.toInt() ?: 0),
                        "techRev" to ((revisions["tech"] as? Number)?.toInt() ?: 0),
                        "planRev" to ((revisions["plan"] as? Number)?.toInt() ?: 0),
                    )
                    state["step_evidence"] = stepEvidence
                    state["last_step_status"] = "passed"
                    state["last_completed_step_index"] = outcomeStepIndex
                    state["current_step_index"] = maxOf(currentStepIndex, outcomeStepIndex + 1)
                    // Any implementation mutation invalidates downstream implementation-derived evidence.
                    state.remove("review_evidence")
                    state.remove("acceptance_evidence")

                    // Only the successful read-only verifier can explicitly acknowledge findings.
                    // The Builder must never resolve findings merely by claiming their ids.
                    val verifierOutcome = outcome.stepOutcomes.lastOrNull {
                        it.stepId == "step-verification" && it.status == STATUS_COMPLETED
                    }
                    val resolvedFindingIds = verifierOutcome
                        ?.let(::extractOutputsFromStep)
                        ?.get("resolved_finding_ids")
                        ?.let { it as? List<*> }
                        ?.filterIsInstance<String>()
                        .orEmpty()
                        .toSet()
                    val resolved = run.findings
                        .filter { it.status == FindingStatus.OPEN && it.id in resolvedFindingIds }
                        .associate { finding ->
                            finding.id to FindingResolution(
                                FindingStatus.RESOLVED,
                                "Explicitly resolved and verified in ${outcome.fragmentId}",
                            )
                        }
                    RunUpdate(newDomainState = state, findingResolutions = resolved)
                } else {
                    state["last_step_status"] = "failed"
                    val failedStep = outcome.stepOutcomes.firstOrNull { it.status == STATUS_FAILED }
                    val added = if (failedStep != null && run.findings.none { it.status == FindingStatus.OPEN && it.stepId == failedStep.stepId }) {
                        listOf(
                            NewFinding(
                                code = failedStep.errorCode ?: "STEP_VERIFICATION_FAILED",
                                severity = FindingSeverity.MAJOR,
                                message = failedStep.errorMessage ?: "Step '${failedStep.stepId}' failed in '${outcome.flowType}'.",
                                stepId = failedStep.stepId,
                            ),
                        )
                    } else {
                        emptyList()
                    }
                    RunUpdate(newDomainState = state, addedFindings = added)
                }
            }

            ForgeBmadCatalogContributor.FLOW_CODE_REVIEW -> {
                val reportedBlockingFindings = outcome.stepOutcomes.any { step ->
                    val findings = extractOutputs(step.report)["findings"] as? List<*> ?: emptyList<Any?>()
                    findings.any { raw ->
                        val severity = (raw as? Map<*, *>)?.get("severity")?.toString()?.uppercase()
                        severity == FindingSeverity.CRITICAL.name || severity == FindingSeverity.MAJOR.name
                    }
                }
                if (outcome.status == STATUS_COMPLETED && !reportedBlockingFindings) {
                    state["review_evidence"] = mapOf(
                        "status" to "passed",
                        "fragmentId" to outcome.fragmentId,
                        "productRev" to ((revisions["product"] as? Number)?.toInt() ?: 0),
                        "uxRev" to ((revisions["ux"] as? Number)?.toInt() ?: 0),
                        "techRev" to ((revisions["tech"] as? Number)?.toInt() ?: 0),
                        "planRev" to ((revisions["plan"] as? Number)?.toInt() ?: 0),
                    )
                } else {
                    state.remove("review_evidence")
                    state.remove("acceptance_evidence")
                }
                RunUpdate(newDomainState = state)
            }

            ForgeBmadCatalogContributor.FLOW_ACCEPTANCE -> {
                if (outcome.status == STATUS_COMPLETED) {
                    state["acceptance_evidence"] = mapOf(
                        "status" to "passed",
                        "fragmentId" to outcome.fragmentId,
                        "productRev" to ((revisions["product"] as? Number)?.toInt() ?: 0),
                        "uxRev" to ((revisions["ux"] as? Number)?.toInt() ?: 0),
                        "techRev" to ((revisions["tech"] as? Number)?.toInt() ?: 0),
                        "planRev" to ((revisions["plan"] as? Number)?.toInt() ?: 0),
                    )
                } else {
                    state.remove("acceptance_evidence")
                }
                RunUpdate(newDomainState = state)
            }

            else -> RunUpdate()
        }
    }

    override fun next(run: RunView): FlowDecision {
        val state = run.domainState
        val specPath = (state["spec_path"] as? String) ?: ForgeBmadBriefContributor.DEFAULT_SPEC_PATH
        val uxApplicable = state["ux_applicable"] as? Boolean ?: false
        val techApplicable = state["tech_spec_applicable"] as? Boolean ?: true
        val planSteps = planStepsOf(state)
        val currentStepIndex = (state["current_step_index"] as? Number)?.toInt() ?: 0
        val lastFragment = run.fragments.lastOrNull()
            ?: return FlowDecision.Apply(
                flowType = ForgeBmadCatalogContributor.FLOW_PRODUCT_SPEC,
                inputs = mapOf("spec_path" to specPath, "ticket" to run.ticket),
                reason = "Initial product specification",
                loopKey = "product-spec",
                maxIterations = 3,
            )

        return when (lastFragment.flowType) {
            ForgeBmadCatalogContributor.FLOW_PRODUCT_SPEC -> {
                if (lastFragment.status == STATUS_COMPLETED) {
                    FlowDecision.Apply(
                        flowType = ForgeBmadCatalogContributor.FLOW_PRODUCT_APPROVAL,
                        inputs = mapOf("spec_path" to specPath),
                        reason = "Product specification validated; requesting grooming and product approval",
                    )
                } else {
                    FlowDecision.Apply(
                        flowType = ForgeBmadCatalogContributor.FLOW_PRODUCT_SPEC,
                        inputs = mapOf("spec_path" to specPath, "ticket" to run.ticket),
                        reason = "Product specification failed validation; retrying",
                        loopKey = "product-spec",
                        maxIterations = 3,
                    )
                }
            }

            ForgeBmadCatalogContributor.FLOW_PRODUCT_APPROVAL -> {
                if (lastFragment.status == STATUS_COMPLETED) {
                    FlowDecision.Apply(
                        flowType = ForgeBmadCatalogContributor.FLOW_ASSESSMENT,
                        inputs = mapOf("spec_path" to specPath),
                        reason = "Product specification approved; launching UX and Technical assessments",
                    )
                } else {
                    FlowDecision.Apply(
                        flowType = ForgeBmadCatalogContributor.FLOW_PRODUCT_SPEC,
                        inputs = mapOf("spec_path" to specPath, "ticket" to run.ticket),
                        reason = "Product approval rejected; returning to product specification",
                        loopKey = "product-spec",
                        maxIterations = 3,
                    )
                }
            }

            ForgeBmadCatalogContributor.FLOW_ASSESSMENT -> {
                if (lastFragment.status == STATUS_ESCALATION_RESOLVED) {
                    FlowDecision.Apply(
                        flowType = ForgeBmadCatalogContributor.FLOW_ASSESSMENT,
                        inputs = mapOf("spec_path" to specPath),
                        reason = "Human requested retry of UX/Technical assessment",
                        loopKey = "assessment",
                        maxIterations = 3,
                    )
                } else if (lastFragment.status != STATUS_COMPLETED) {
                    FlowDecision.EscalateHuman(
                        question = "UX/Technical assessment failed. How do you want to proceed?",
                        options = listOf("retry", "abort"),
                        reason = "ASSESSMENT_FAILED",
                    )
                } else if (uxApplicable) {
                    FlowDecision.Apply(
                        flowType = ForgeBmadCatalogContributor.FLOW_UX_DESIGN,
                        inputs = mapOf("spec_path" to specPath),
                        reason = "UX assessment marked ux_applicable=true; launching UX design",
                        loopKey = "ux-design",
                        maxIterations = 3,
                    )
                } else if (techApplicable) {
                    FlowDecision.Apply(
                        flowType = ForgeBmadCatalogContributor.FLOW_TECH_DESIGN,
                        inputs = mapOf("spec_path" to specPath),
                        reason = "No UX needed and tech_spec_applicable=true; launching technical design",
                        loopKey = "tech-design",
                        maxIterations = 3,
                    )
                } else {
                    // Trivial fast-track (D9): skip UX design, tech design and spec approval!
                    val step0 = planSteps.firstOrNull().orEmpty()
                    FlowDecision.Apply(
                        flowType = ForgeBmadCatalogContributor.FLOW_IMPLEMENT_STEP,
                        inputs = buildStepInputs(specPath, 0, step0),
                        reason = "Trivial assessment (no UX, no tech spec); fast-tracking directly to single implementation step (D9)",
                    )
                }
            }

            ForgeBmadCatalogContributor.FLOW_UX_DESIGN -> {
                if (lastFragment.status != STATUS_COMPLETED) {
                    FlowDecision.Apply(
                        flowType = ForgeBmadCatalogContributor.FLOW_UX_DESIGN,
                        inputs = mapOf("spec_path" to specPath),
                        reason = "UX design failed validation; retrying",
                        loopKey = "ux-design",
                        maxIterations = 3,
                    )
                } else if (techApplicable) {
                    FlowDecision.Apply(
                        flowType = ForgeBmadCatalogContributor.FLOW_TECH_DESIGN,
                        inputs = mapOf("spec_path" to specPath),
                        reason = "UX design completed; launching technical design",
                        loopKey = "tech-design",
                        maxIterations = 3,
                    )
                } else {
                    FlowDecision.Apply(
                        flowType = ForgeBmadCatalogContributor.FLOW_SPEC_APPROVAL,
                        inputs = mapOf("spec_path" to specPath),
                        reason = "UX design completed and no tech spec required; requesting specification approval",
                    )
                }
            }

            ForgeBmadCatalogContributor.FLOW_TECH_DESIGN -> {
                if (lastFragment.status == STATUS_COMPLETED && planSteps.isNotEmpty()) {
                    FlowDecision.Apply(
                        flowType = ForgeBmadCatalogContributor.FLOW_SPEC_APPROVAL,
                        inputs = mapOf("spec_path" to specPath),
                        reason = "Technical design and delivery plan (${planSteps.size} steps) validated; requesting specification approval",
                    )
                } else {
                    FlowDecision.Apply(
                        flowType = ForgeBmadCatalogContributor.FLOW_TECH_DESIGN,
                        inputs = mapOf("spec_path" to specPath),
                        reason = "Technical design failed validation; retrying",
                        loopKey = "tech-design",
                        maxIterations = 3,
                    )
                }
            }

            ForgeBmadCatalogContributor.FLOW_SPEC_APPROVAL -> {
                if (lastFragment.status == STATUS_COMPLETED) {
                    val step0 = planSteps.getOrNull(0).orEmpty()
                    FlowDecision.Apply(
                        flowType = ForgeBmadCatalogContributor.FLOW_IMPLEMENT_STEP,
                        inputs = buildStepInputs(specPath, 0, step0),
                        reason = "Specification approved; launching delivery step 1/${planSteps.size}",
                    )
                } else {
                    val targetFlow = if (techApplicable) {
                        ForgeBmadCatalogContributor.FLOW_TECH_DESIGN
                    } else {
                        ForgeBmadCatalogContributor.FLOW_UX_DESIGN
                    }
                    FlowDecision.Apply(
                        flowType = targetFlow,
                        inputs = mapOf("spec_path" to specPath),
                        reason = "Specification approval rejected; returning to $targetFlow",
                        loopKey = "spec-rework",
                        maxIterations = 3,
                    )
                }
            }

            ForgeBmadCatalogContributor.FLOW_IMPLEMENT_STEP,
            ForgeBmadCatalogContributor.FLOW_CORRECTION,
            -> {
                if (lastFragment.status == STATUS_COMPLETED && state["last_step_status"] == "passed") {
                    if (currentStepIndex < planSteps.size) {
                        val nextStep = planSteps[currentStepIndex]
                        FlowDecision.Apply(
                            flowType = ForgeBmadCatalogContributor.FLOW_IMPLEMENT_STEP,
                            inputs = buildStepInputs(specPath, currentStepIndex, nextStep),
                            reason = "Step $currentStepIndex completed; advancing to delivery step ${currentStepIndex + 1}/${planSteps.size}",
                        )
                    } else {
                        FlowDecision.Apply(
                            flowType = ForgeBmadCatalogContributor.FLOW_CODE_REVIEW,
                            inputs = mapOf("spec_path" to specPath, "delivery_plan" to state["delivery_plan"]),
                            reason = "All ${planSteps.size} delivery step(s) implemented and verified; launching adversarial code review",
                        )
                    }
                } else {
                    val failedStepIndex = currentStepIndex.coerceAtMost((planSteps.size - 1).coerceAtLeast(0))
                    val stepObj = planSteps.getOrNull(failedStepIndex).orEmpty()
                    FlowDecision.Apply(
                        flowType = ForgeBmadCatalogContributor.FLOW_CORRECTION,
                        inputs = buildStepInputs(specPath, failedStepIndex, stepObj),
                        reason = "Step $failedStepIndex failed verification; scheduling bounded correction",
                        loopKey = "correction:step-$failedStepIndex",
                        maxIterations = 3,
                    )
                }
            }

            ForgeBmadCatalogContributor.FLOW_CODE_REVIEW -> {
                val openBlockingFindings = run.findings.filter {
                    it.status == FindingStatus.OPEN &&
                        it.severity in setOf(FindingSeverity.CRITICAL, FindingSeverity.MAJOR)
                }
                if (lastFragment.status != STATUS_COMPLETED || openBlockingFindings.isNotEmpty()) {
                    val lastIdx = (planSteps.size - 1).coerceAtLeast(0)
                    val stepObj = planSteps.getOrNull(lastIdx).orEmpty()
                    FlowDecision.Apply(
                        flowType = ForgeBmadCatalogContributor.FLOW_CORRECTION,
                        inputs = buildStepInputs(specPath, lastIdx, stepObj),
                        reason = "Code review raised ${openBlockingFindings.size} blocking finding(s); scheduling bounded correction",
                        loopKey = "review-fix",
                        maxIterations = 2,
                    )
                } else {
                    FlowDecision.Apply(
                        flowType = ForgeBmadCatalogContributor.FLOW_ACCEPTANCE,
                        inputs = mapOf("spec_path" to specPath),
                        reason = "Code review passed with zero open critical/major findings; requesting functional acceptance",
                    )
                }
            }

            ForgeBmadCatalogContributor.FLOW_ACCEPTANCE -> {
                if (lastFragment.status != STATUS_COMPLETED) {
                    val lastIdx = (planSteps.size - 1).coerceAtLeast(0)
                    val stepObj = planSteps.getOrNull(lastIdx).orEmpty()
                    FlowDecision.Apply(
                        flowType = ForgeBmadCatalogContributor.FLOW_CORRECTION,
                        inputs = buildStepInputs(specPath, lastIdx, stepObj),
                        reason = "Functional acceptance rejected; scheduling bounded correction",
                        loopKey = "acceptance-fix",
                        maxIterations = 2,
                    )
                } else {
                    checkCompleteness(run)
                }
            }

            else -> FlowDecision.EscalateHuman(
                question = "Unexpected fragment '${lastFragment.flowType}'. How do you want to proceed?",
                options = listOf("retry", "abort"),
                reason = "UNKNOWN_FRAGMENT_FLOW_TYPE",
            )
        }
    }

    /**
     * Enforces D10 completeness before returning [FlowDecision.Complete]:
     * 1. Zero `OPEN` findings of severity `CRITICAL` or `MAJOR`.
     * 2. Every required step evidence, review evidence and acceptance evidence
     *    exists and matches the current revisions of `product`, `ux`, `tech`, and `plan`.
     */
    private fun checkCompleteness(run: RunView): FlowDecision {
        val openBlocking = run.findings.filter {
            it.status == FindingStatus.OPEN &&
                it.severity in setOf(FindingSeverity.CRITICAL, FindingSeverity.MAJOR)
        }
        if (openBlocking.isNotEmpty()) {
            return FlowDecision.EscalateHuman(
                question = "Cannot complete run: ${openBlocking.size} CRITICAL/MAJOR finding(s) remain OPEN.",
                options = listOf("retry", "abort"),
                reason = "COMPLETENESS_OPEN_FINDINGS",
            )
        }

        val state = run.domainState
        val revisions = revisionsOf(state)
        val curProduct = (revisions["product"] as? Number)?.toInt() ?: 0
        val curUx = (revisions["ux"] as? Number)?.toInt() ?: 0
        val curTech = (revisions["tech"] as? Number)?.toInt() ?: 0
        val curPlan = (revisions["plan"] as? Number)?.toInt() ?: 0

        val planSteps = planStepsOf(state)
        if (planSteps.isEmpty()) {
            return FlowDecision.EscalateHuman(
                question = "Cannot complete run: delivery plan is missing or empty.",
                options = listOf("retry", "abort"),
                reason = "MISSING_DELIVERY_PLAN",
            )
        }
        if (curProduct <= 0 || curPlan <= 0) {
            return FlowDecision.EscalateHuman(
                question = "Cannot complete run: product specification or delivery plan revision is missing.",
                options = listOf("retry", "abort"),
                reason = "MISSING_REQUIRED_REVISIONS",
            )
        }

        val uxApplicable = state["ux_applicable"] as? Boolean ?: false
        val techApplicable = state["tech_spec_applicable"] as? Boolean ?: true
        val productApproved = (revisions["product_approved"] as? Number)?.toInt()
        val uxApproved = (revisions["ux_approved"] as? Number)?.toInt()
        val techApproved = (revisions["tech_approved"] as? Number)?.toInt()
        if (productApproved == null || productApproved != curProduct ||
            (uxApplicable && (curUx <= 0 || uxApproved == null || uxApproved != curUx)) ||
            (techApplicable && (curTech <= 0 || techApproved == null || techApproved != curTech))
        ) {
            return FlowDecision.EscalateHuman(
                question = "Cannot complete run: required product/specification approvals are missing or stale.",
                options = listOf("retry", "abort"),
                reason = "STALE_APPROVAL_EVIDENCE",
            )
        }

        @Suppress("UNCHECKED_CAST")
        val stepEvidence = state["step_evidence"] as? Map<String, Any?> ?: emptyMap()
        for (stepIndex in planSteps.indices) {
            @Suppress("UNCHECKED_CAST")
            val evidence = stepEvidence[stepIndex.toString()] as? Map<String, Any?>
            if (evidence == null || evidence["status"] != "passed" ||
                !evidenceMatchesRevisions(evidence, curProduct, curUx, curTech, curPlan)
            ) {
                return FlowDecision.EscalateHuman(
                    question = "Delivery step $stepIndex evidence is missing, failed or stale.",
                    options = listOf("retry", "abort"),
                    reason = "STALE_STEP_EVIDENCE:$stepIndex",
                )
            }
        }

        @Suppress("UNCHECKED_CAST")
        val reviewEv = state["review_evidence"] as? Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val acceptEv = state["acceptance_evidence"] as? Map<String, Any?>

        if (reviewEv == null || reviewEv["status"] != "passed" || !evidenceMatchesRevisions(reviewEv, curProduct, curUx, curTech, curPlan)) {
            return FlowDecision.EscalateHuman(
                question = "Code review evidence is missing or stale relative to current spec/plan revisions.",
                options = listOf("retry", "abort"),
                reason = "STALE_REVIEW_EVIDENCE",
            )
        }
        if (acceptEv == null || acceptEv["status"] != "passed" || !evidenceMatchesRevisions(acceptEv, curProduct, curUx, curTech, curPlan)) {
            return FlowDecision.EscalateHuman(
                question = "Acceptance evidence is missing or stale relative to current spec/plan revisions.",
                options = listOf("retry", "abort"),
                reason = "STALE_ACCEPTANCE_EVIDENCE",
            )
        }

        return FlowDecision.Complete(
            reason = "All BMAD steps, code review and acceptance completed on aligned revisions with zero open blocking findings.",
        )
    }

    private fun evidenceMatchesRevisions(
        evidence: Map<String, Any?>,
        productRev: Int,
        uxRev: Int,
        techRev: Int,
        planRev: Int,
    ): Boolean =
        ((evidence["productRev"] as? Number)?.toInt() ?: 0) == productRev &&
            ((evidence["uxRev"] as? Number)?.toInt() ?: 0) == uxRev &&
            ((evidence["techRev"] as? Number)?.toInt() ?: 0) == techRev &&
            ((evidence["planRev"] as? Number)?.toInt() ?: 0) == planRev

    private fun buildStepInputs(
        specPath: String,
        stepIndex: Int,
        step: Map<String, Any?>,
    ): Map<String, Any?> {
        val verification = (step["verification"] as? String)
            ?: ((step["verifications"] as? List<*>)?.firstOrNull()?.toString())
            ?: "default-verify"
        return mapOf(
            "spec_path" to specPath,
            "step_index" to stepIndex,
            "step" to step,
            "verifications" to mapOf("step-build-test" to verification),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun revisionsOf(state: Map<String, Any?>): Map<String, Any?> =
        (state["revisions"] as? Map<String, Any?>).orEmpty()

    @Suppress("UNCHECKED_CAST")
    private fun planStepsOf(state: Map<String, Any?>): List<Map<String, Any?>> {
        val plan = state["delivery_plan"] as? Map<String, Any?> ?: return emptyList()
        return (plan["steps"] as? List<*>)
            ?.mapNotNull { entry -> (entry as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } }
            .orEmpty()
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractOutputs(report: Map<String, Any?>): Map<String, Any?> =
        (report["outputs"] as? Map<String, Any?>) ?: report

    private fun extractOutputsFromStep(step: io.whozoss.factory.sdk.spi.StepOutcomeView): Map<String, Any?> =
        extractOutputs(step.report)

    companion object {
        const val SELECTOR_VERSION = "1.0.0"
        val SUPPORTED_RUN_TYPES = setOf("forge-controller-v1", "forge-bmad")
        private const val STATUS_COMPLETED = "completed"
        private const val STATUS_FAILED = "failed"
        private const val STATUS_ESCALATION_RESOLVED = "ESCALATION_RESOLVED"
    }
}
