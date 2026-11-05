package io.whozoss.factory.forge.bmad

import io.whozoss.factory.sdk.spi.FactoryStepResultValidator
import io.whozoss.factory.sdk.spi.FindingResolution
import io.whozoss.factory.sdk.spi.FindingSeverity
import io.whozoss.factory.sdk.spi.FindingStatus
import io.whozoss.factory.sdk.spi.NewFinding
import io.whozoss.factory.sdk.spi.StepValidationContext
import io.whozoss.factory.sdk.spi.StepValidationVerdict
import io.whozoss.factory.sdk.spi.WatchedFileSnapshot
import org.pf4j.Extension

/**
 * PF4J extension synchronously validating BMAD agent step results and
 * `WorkspaceDelta` proofs before `SessionRunService` seals a step transition
 * (Lot 2 & Lot 4, §5.3 & §6.2).
 *
 * Enforces:
 * 1. **Phantom artifact detection:** `SPEC.md` and `.memlog.md` must exist and
 *    have a changed `sha256After != sha256Before` during `ForgeProductWorker`
 *    (`product-specification`), `ForgeUXDesigner` (`ux-design`), and
 *    `ForgeTechDesigner` (`technical-design`) turns.
 * 2. **Expected amendment section:** the patch of `SPEC.md` must contain the
 *    expected section header (`## Product`, `## UX`, `## Technical`) when a
 *    patch is available.
 * 3. **Write-lock on `SPEC.md`:** any non-authoring worker (`ForgeBuilder`,
 *    `ForgeVerifier`, `ForgeReviewer`, `Searcher`) is forbidden from mutating
 *    `SPEC.md` (`sha256After == sha256Before`).
 * 4. **Grooming report integrity:** `product-grooming` must materialize the
 *    grooming file with a non-null `sha256After`, recorded in `domainState`.
 * 5. **Step file-scope enforcement (`file_paths`):** during `ForgeBuilder`
 *    turns, `changedFilesDuringTurn.keys` must be a subset of the step's
 *    declared `file_paths` (when non-empty).
 * 6. **Structured delivery plan validation (D8):** `technical-design` must
 *    produce a non-empty `delivery_plan.steps[]` list in its structured report.
 */
@Extension
class ForgeBmadStepResultValidator : FactoryStepResultValidator {

    override fun supports(flowType: String, stepId: String, agentName: String): Boolean =
        flowType.startsWith("forge.")

    override fun validate(context: StepValidationContext): StepValidationVerdict {
        val domainState = context.run.domainState
        val specPath = (domainState["spec_path"] as? String)
            ?: (context.fragmentInputs["spec_path"] as? String)
            ?: ForgeBmadBriefContributor.DEFAULT_SPEC_PATH
        val memlogPath = (domainState["memlog_path"] as? String)
            ?: ForgeBmadBriefContributor.DEFAULT_MEMLOG_PATH
        val groomingPath = ((domainState["grooming"] as? Map<*, *>)?.get("path") as? String)
            ?: ForgeBmadBriefContributor.DEFAULT_GROOMING_PATH

        val watched = context.workspaceDelta.watchedFiles

        return when (context.flowType) {
            ForgeBmadCatalogContributor.FLOW_PRODUCT_SPEC ->
                validateSpecAmendment(
                    context = context,
                    specPath = specPath,
                    memlogPath = memlogPath,
                    expectedSection = "## Product",
                    revisionKey = "product",
                )

            ForgeBmadCatalogContributor.FLOW_PRODUCT_APPROVAL -> {
                val groomingSnap = watched[groomingPath]
                if (groomingSnap == null || !groomingSnap.existsAfter || groomingSnap.sha256After.isNullOrBlank()) {
                    StepValidationVerdict.Rejected(
                        reasonCode = "PHANTOM_GROOMING_ARTIFACT",
                        findings = listOf(
                            NewFinding(
                                code = "PHANTOM_GROOMING_ARTIFACT",
                                severity = FindingSeverity.CRITICAL,
                                message = "Grooming report '$groomingPath' was not materialized in workspace.",
                                stepId = context.stepId,
                                filePath = groomingPath,
                            ),
                        ),
                    )
                } else {
                    val nextState = LinkedHashMap(domainState).apply {
                        put("grooming", mapOf("path" to groomingPath, "sha256" to groomingSnap.sha256After))
                    }
                    StepValidationVerdict.Accepted(newDomainState = nextState)
                }
            }

            ForgeBmadCatalogContributor.FLOW_ASSESSMENT -> {
                val readOnlyViolation = checkReadOnlyWorkspace(context)
                if (readOnlyViolation != null) return readOnlyViolation
                val outputs = extractOutputs(context.report)
                val nextState = LinkedHashMap(domainState)
                if (context.stepId == "ux-assessment" || context.agentName == "ForgeUXDesigner") {
                    val uxApplicable = outputs["ux_applicable"] as? Boolean ?: false
                    nextState["ux_applicable"] = uxApplicable
                }
                if (context.stepId == "technical-assessment" || context.agentName == "ForgeTechDesigner") {
                    val trivial = outputs["trivial"] as? Boolean ?: false
                    val techApplicable = outputs["tech_spec_applicable"] as? Boolean ?: !trivial
                    nextState["tech_spec_applicable"] = techApplicable
                    outputs["trivial_scope"]?.let { nextState["trivial_scope"] = it }
                }
                StepValidationVerdict.Accepted(newDomainState = nextState)
            }

            ForgeBmadCatalogContributor.FLOW_UX_DESIGN ->
                validateSpecAmendment(
                    context = context,
                    specPath = specPath,
                    memlogPath = memlogPath,
                    expectedSection = "## UX",
                    revisionKey = "ux",
                )

            ForgeBmadCatalogContributor.FLOW_TECH_DESIGN -> {
                if (context.agentName == "Searcher" || context.stepId == "codebase-research") {
                    val readOnlyViolation = checkReadOnlyWorkspace(context)
                    if (readOnlyViolation != null) return readOnlyViolation
                    return StepValidationVerdict.Accepted()
                }
                val specVerdict = validateSpecAmendment(
                    context = context,
                    specPath = specPath,
                    memlogPath = memlogPath,
                    expectedSection = "## Technical",
                    revisionKey = "tech",
                )
                if (specVerdict is StepValidationVerdict.Rejected) return specVerdict
                val acceptedSpec = specVerdict as StepValidationVerdict.Accepted

                // Validate structured delivery_plan.steps[] (D8)
                val outputs = extractOutputs(context.report)
                @Suppress("UNCHECKED_CAST")
                val deliveryPlan = (outputs["delivery_plan"] as? Map<String, Any?>)
                    ?: (context.report["delivery_plan"] as? Map<String, Any?>)
                val steps = (deliveryPlan?.get("steps") as? List<*>)?.filterIsInstance<Map<*, *>>().orEmpty()
                if (steps.isEmpty()) {
                    return StepValidationVerdict.Rejected(
                        reasonCode = "MISSING_DELIVERY_PLAN_STEPS",
                        findings = listOf(
                            NewFinding(
                                code = "MISSING_DELIVERY_PLAN_STEPS",
                                severity = FindingSeverity.CRITICAL,
                                message = "ForgeTechDesigner must return a non-empty structured delivery_plan.steps[] list.",
                                stepId = context.stepId,
                            ),
                        ),
                    )
                }
                val baseState = LinkedHashMap(acceptedSpec.newDomainState ?: domainState)
                val revisions = LinkedHashMap(
                    @Suppress("UNCHECKED_CAST")
                    (baseState["revisions"] as? Map<String, Any?>).orEmpty(),
                )
                revisions["plan"] = ((revisions["plan"] as? Number)?.toInt() ?: 0) + 1
                baseState["revisions"] = revisions
                baseState["delivery_plan"] = mapOf("steps" to steps)
                StepValidationVerdict.Accepted(
                    newDomainState = baseState,
                    findingResolutions = acceptedSpec.findingResolutions,
                )
            }

            ForgeBmadCatalogContributor.FLOW_IMPLEMENT_STEP,
            ForgeBmadCatalogContributor.FLOW_CORRECTION,
            -> {
                val lockViolation = checkSpecWriteLock(context, specPath, watched[specPath])
                if (lockViolation != null) return lockViolation

                if (context.agentName == "ForgeVerifier") {
                    val readOnlyViolation = checkReadOnlyWorkspace(context)
                    if (readOnlyViolation != null) return readOnlyViolation
                }

                if (context.agentName == "ForgeBuilder") {
                    @Suppress("UNCHECKED_CAST")
                    val planStep = context.fragmentInputs["step"] as? Map<String, Any?>
                    val allowedFiles = (planStep?.get("file_paths") as? List<*>)
                        ?.mapNotNull { it?.toString()?.trim() }
                        ?.filter { it.isNotEmpty() }
                        ?.toSet()
                        .orEmpty()
                    if (allowedFiles.isNotEmpty()) {
                        val touched = context.workspaceDelta.changedFilesDuringTurn.keys
                        val outOfScope = touched.filterNot { it in allowedFiles || it == memlogPath }
                        if (outOfScope.isNotEmpty()) {
                            return StepValidationVerdict.Rejected(
                                reasonCode = "STEP_SCOPE_VIOLATION",
                                findings = listOf(
                                    NewFinding(
                                        code = "STEP_SCOPE_VIOLATION",
                                        severity = FindingSeverity.MAJOR,
                                        message = "ForgeBuilder modified files outside step file_paths: ${outOfScope.joinToString(", ")}",
                                        stepId = context.stepId,
                                        filePath = outOfScope.first(),
                                        details = mapOf("outOfScopeFiles" to outOfScope, "allowedFiles" to allowedFiles.toList()),
                                    ),
                                ),
                            )
                        }
                    }
                }
                StepValidationVerdict.Accepted()
            }

            ForgeBmadCatalogContributor.FLOW_CODE_REVIEW -> {
                val readOnlyViolation = checkReadOnlyWorkspace(context)
                if (readOnlyViolation != null) return readOnlyViolation
                val reportedFindings = extractReportedFindings(context)
                StepValidationVerdict.Accepted(addedFindings = reportedFindings)
            }

            else -> StepValidationVerdict.Accepted()
        }
    }

    private fun validateSpecAmendment(
        context: StepValidationContext,
        specPath: String,
        memlogPath: String,
        expectedSection: String,
        revisionKey: String,
    ): StepValidationVerdict {
        val watched = context.workspaceDelta.watchedFiles
        val specSnap = watched[specPath]
        val memlogSnap = watched[memlogPath]

        if (specSnap == null || !specSnap.existsAfter || specSnap.sha256After.isNullOrBlank() ||
            specSnap.sha256After == specSnap.sha256Before
        ) {
            return StepValidationVerdict.Rejected(
                reasonCode = "PHANTOM_SPEC_ARTIFACT",
                findings = listOf(
                    NewFinding(
                        code = "PHANTOM_SPEC_ARTIFACT",
                        severity = FindingSeverity.CRITICAL,
                        message = "Expected '$specPath' to be created or amended during '${context.stepId}', but its content SHA-256 did not change.",
                        stepId = context.stepId,
                        filePath = specPath,
                    ),
                ),
            )
        }

        if (memlogSnap == null || !memlogSnap.existsAfter || memlogSnap.sha256After.isNullOrBlank() ||
            memlogSnap.sha256After == memlogSnap.sha256Before
        ) {
            return StepValidationVerdict.Rejected(
                reasonCode = "PHANTOM_MEMLOG_ARTIFACT",
                findings = listOf(
                    NewFinding(
                        code = "PHANTOM_MEMLOG_ARTIFACT",
                        severity = FindingSeverity.MAJOR,
                        message = "Expected '$memlogPath' to be updated during '${context.stepId}', but its content SHA-256 did not change.",
                        stepId = context.stepId,
                        filePath = memlogPath,
                    ),
                ),
            )
        }

        if (!specSnap.patchAfter.isNullOrBlank() && !specSnap.patchAfter!!.contains(expectedSection, ignoreCase = true)) {
            return StepValidationVerdict.Rejected(
                reasonCode = "MISSING_EXPECTED_SPEC_SECTION",
                findings = listOf(
                    NewFinding(
                        code = "MISSING_EXPECTED_SPEC_SECTION",
                        severity = FindingSeverity.MAJOR,
                        message = "The patch of '$specPath' does not contain expected section '$expectedSection'.",
                        stepId = context.stepId,
                        filePath = specPath,
                    ),
                ),
            )
        }

        val domainState = LinkedHashMap(context.run.domainState)
        domainState["spec_path"] = specPath
        domainState["memlog_path"] = memlogPath
        val revisions = LinkedHashMap(
            @Suppress("UNCHECKED_CAST")
            (domainState["revisions"] as? Map<String, Any?>).orEmpty(),
        )
        revisions[revisionKey] = ((revisions[revisionKey] as? Number)?.toInt() ?: 0) + 1
        domainState["revisions"] = revisions

        val outputs = extractOutputs(context.report)
        outputs["question_evidence"]?.let { domainState["question_evidence"] = it }

        return StepValidationVerdict.Accepted(newDomainState = domainState)
    }

    private fun checkReadOnlyWorkspace(context: StepValidationContext): StepValidationVerdict.Rejected? {
        val touched = context.workspaceDelta.changedFilesDuringTurn.keys
        if (touched.isEmpty()) return null
        return StepValidationVerdict.Rejected(
            reasonCode = "READ_ONLY_WORKSPACE_VIOLATION",
            findings = listOf(
                NewFinding(
                    code = "READ_ONLY_WORKSPACE_VIOLATION",
                    severity = FindingSeverity.CRITICAL,
                    message = "Read-only agent '${context.agentName}' modified repository files: ${touched.joinToString(", ")}",
                    stepId = context.stepId,
                    filePath = touched.first(),
                    details = mapOf("changedFiles" to touched.toList()),
                ),
            ),
        )
    }

    private fun checkSpecWriteLock(
        context: StepValidationContext,
        specPath: String,
        specSnap: WatchedFileSnapshot?,
    ): StepValidationVerdict.Rejected? {
        val changedInTurn = specPath in context.workspaceDelta.changedFilesDuringTurn
        val shaChanged = specSnap != null && specSnap.sha256Before != specSnap.sha256After
        if (changedInTurn || shaChanged) {
            return StepValidationVerdict.Rejected(
                reasonCode = "SPEC_WRITE_LOCK_VIOLATION",
                findings = listOf(
                    NewFinding(
                        code = "SPEC_WRITE_LOCK_VIOLATION",
                        severity = FindingSeverity.CRITICAL,
                        message = "Agent '${context.agentName}' in step '${context.stepId}' is not authorized to modify '$specPath'.",
                        stepId = context.stepId,
                        filePath = specPath,
                    ),
                ),
            )
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractOutputs(report: Map<String, Any?>): Map<String, Any?> =
        (report["outputs"] as? Map<String, Any?>) ?: report

    private fun extractReportedFindings(context: StepValidationContext): List<NewFinding> {
        val outputs = extractOutputs(context.report)
        val rawFindings = (outputs["findings"] as? List<*>)
            ?: (context.report["findings"] as? List<*>)
            ?: return emptyList()
        return rawFindings.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            val code = (map["code"] as? String)?.takeIf { it.isNotBlank() } ?: "REVIEW_FINDING"
            val message = (map["message"] as? String)?.takeIf { it.isNotBlank() }
                ?: (map["summary"] as? String)?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val rawSev = (map["severity"] as? String)?.uppercase() ?: "MAJOR"
            val severity = runCatching { FindingSeverity.valueOf(rawSev) }.getOrDefault(FindingSeverity.MAJOR)
            NewFinding(
                id = map["id"] as? String,
                code = code,
                severity = severity,
                message = message,
                stepId = context.stepId,
                filePath = map["filePath"] as? String,
            )
        }
    }
}
