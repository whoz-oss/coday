package io.whozoss.factory.forge.bmad

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.sdk.spi.FactoryStepResultValidator
import io.whozoss.factory.sdk.spi.FindingResolution
import io.whozoss.factory.sdk.spi.FindingSeverity
import io.whozoss.factory.sdk.spi.FindingStatus
import io.whozoss.factory.sdk.spi.NewFinding
import io.whozoss.factory.sdk.spi.StepValidationContext
import io.whozoss.factory.sdk.spi.StepValidationVerdict
import io.whozoss.factory.sdk.spi.WatchedFileSnapshot
import org.pf4j.Extension
import java.nio.file.Path

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
 * 3. **Write-lock on `SPEC.md`:** implementation, verification and review steps
 *    cannot mutate `SPEC.md`, regardless of the assigned agent name.
 * 4. **Grooming report integrity:** `product-grooming` must materialize the
 *    grooming file with a non-null `sha256After`, recorded in `domainState`.
 * 5. **Step file-scope enforcement (`file_paths`):** implementation and
 *    correction steps constrain changed files to the declared scope, regardless
 *    of the assigned agent name (when non-empty).
 * 6. **Structured delivery plan validation (D8):** `technical-design` must
 *    produce a non-empty `delivery_plan.steps[]` list in its structured report.
 */
@Extension
class ForgeBmadStepResultValidator : FactoryStepResultValidator {

    private val mapper = ObjectMapper()

    override fun supports(flowType: String, stepId: String, agentName: String): Boolean =
        flowType.startsWith("forge.")

    override fun requiresTrustedWorkspaceBaseline(flowType: String, stepId: String, agentName: String): Boolean =
        flowType == "forge.delivery"

    override fun requiresDispatchEvidence(flowType: String, stepId: String): Boolean =
        flowType == "forge.delivery" && stepId == "implementation-build"

    override fun validate(context: StepValidationContext): StepValidationVerdict {
        val businessOutput = ForgeBusinessOutputArtifact.read(context.report)
        if (businessOutput is ForgeBusinessOutputArtifact.ReadResult.Invalid) {
            return StepValidationVerdict.Rejected(
                reasonCode = "INVALID_FORGE_OUTPUT_ARTIFACT",
                findings = listOf(NewFinding(
                    code = "INVALID_FORGE_OUTPUT_ARTIFACT",
                    severity = FindingSeverity.CRITICAL,
                    message = businessOutput.reason,
                    stepId = context.stepId,
                )),
            )
        }
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
            "forge.delivery" -> {
                if (context.stepId == "delivery-preflight") {
                    validateDeliveryPreflight(context)
                } else {
                    validatePersistedDeliveryRouting(context)?.let { return it }
                    when (context.stepId) {
                        "design-assessment" -> validateDeliveryAssessment(context)
                "implementation-planning" -> validateDeliveryImplementationPlan(context)
                "qa-design" -> validateDeliveryVerificationPlan(context)
                "packet-assembly" -> validateObservedPacket(context)
                "implementation-build" -> validateDeliveryBuild(context)
                "implementation-verification" -> validateDeliveryVerification(context)
                        "implementation-review" -> validateDeliveryReview(context)
                        else -> StepValidationVerdict.Accepted()
                    }
                }
            }

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
                val required = if (context.stepId == "ux-assessment") "ux_applicable" else "tech_spec_applicable"
                if (outputs[required] !is Boolean) {
                    return StepValidationVerdict.Rejected(
                        reasonCode = "MISSING_ASSESSMENT_OUTPUT",
                        findings = listOf(NewFinding(
                            code = "MISSING_ASSESSMENT_OUTPUT",
                            severity = FindingSeverity.CRITICAL,
                            message = "Forge assessment requires a Boolean $required output.",
                            stepId = context.stepId,
                        )),
                    )
                }
                val nextState = LinkedHashMap(domainState)
                if (context.stepId == "ux-assessment") {
                    val uxApplicable = outputs["ux_applicable"] as? Boolean ?: false
                    nextState["ux_applicable"] = uxApplicable
                }
                if (context.stepId == "technical-assessment") {
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
                if (context.stepId == "codebase-research") {
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
                val deliveryPlan = outputs["delivery_plan"] as? Map<String, Any?>
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
                val ids = steps.map { it["id"] as? String }
                val malformed = steps.any { step ->
                    val paths = step["file_paths"] as? List<*>
                    (step["id"] as? String).isNullOrBlank() ||
                        (step["title"] as? String).isNullOrBlank() ||
                        (step["verification"] as? String).isNullOrBlank() ||
                        paths.isNullOrEmpty() || paths.any { path ->
                            path !is String || path.isBlank() || path.startsWith('/') ||
                                path.split('/').any { segment -> segment == ".." || segment == "." }
                        }
                } || ids.size != ids.distinct().size
                if (malformed) {
                    return StepValidationVerdict.Rejected(
                        reasonCode = "INVALID_DELIVERY_PLAN_STEP",
                        findings = listOf(NewFinding(
                            code = "INVALID_DELIVERY_PLAN_STEP",
                            severity = FindingSeverity.CRITICAL,
                            message = "Each delivery step needs a distinct id, title, relative file_paths and verification command.",
                            stepId = context.stepId,
                        )),
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

                if (context.run.runType == "forge.delivery" && context.stepId !in setOf(
                        "step-implementation", "step-correction", "step-verification",
                    )
                ) {
                    return StepValidationVerdict.Rejected(
                        reasonCode = "UNKNOWN_DELIVERY_STEP",
                        findings = listOf(NewFinding(
                            code = "UNKNOWN_DELIVERY_STEP",
                            severity = FindingSeverity.CRITICAL,
                            message = "Step '${context.stepId}' has no delivery role guard in '${context.flowType}'.",
                            stepId = context.stepId,
                        )),
                    )
                }

                if (context.stepId == "step-verification") {
                    val readOnlyViolation = checkReadOnlyWorkspace(context)
                    if (readOnlyViolation != null) return readOnlyViolation
                }

                if (context.stepId == "step-implementation" || context.stepId == "step-correction") {
                    @Suppress("UNCHECKED_CAST")
                    val planStep = context.fragmentInputs["step"] as? Map<String, Any?>
                    val rawPaths = planStep?.get("file_paths") as? List<*>
                    val allowedFiles = rawPaths
                        ?.mapNotNull { it?.toString()?.trim() }
                        ?.filter { it.isNotEmpty() }
                        ?.toSet()
                        .orEmpty()
                    if (context.run.runType == "forge.delivery" &&
                        (rawPaths.isNullOrEmpty() || rawPaths.any { it !is String || !safeRelativePath(it) } ||
                            allowedFiles.size != rawPaths.size)
                    ) {
                        return StepValidationVerdict.Rejected(
                            reasonCode = "MISSING_STEP_SCOPE",
                            findings = listOf(NewFinding(
                                code = "MISSING_STEP_SCOPE",
                                severity = FindingSeverity.CRITICAL,
                                message = "Delivery step '${context.stepId}' requires distinct, safe relative file_paths before code changes.",
                                stepId = context.stepId,
                            )),
                        )
                    }
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
                                        message = "Step '${context.stepId}' modified files outside step file_paths: ${outOfScope.joinToString(", ")}",
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
        if (context.flowType == ForgeBmadCatalogContributor.FLOW_PRODUCT_SPEC) {
            val brief = (outputs["run_brief"] as? String)?.takeIf { it.isNotBlank() && it.length <= 16_000 }
                ?: return StepValidationVerdict.Rejected(
                    reasonCode = "MISSING_RUN_BRIEF",
                    findings = listOf(NewFinding(
                        code = "MISSING_RUN_BRIEF",
                        severity = FindingSeverity.MAJOR,
                        message = "Product specification must provide a bounded run_brief output for downstream fragments.",
                        stepId = context.stepId,
                    )),
                )
            domainState["run_brief"] = brief
        }
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

    private fun validateDeliveryPreflight(context: StepValidationContext): StepValidationVerdict {
        checkReadOnlyWorkspace(context)?.let { return it }
        if (context.workspaceDelta.captureStatus != io.whozoss.factory.sdk.spi.WorkspaceCaptureStatus.COMPLETE) {
            return rejectRepositoryRouting(context, "CASE_BINDING_UNAVAILABLE", "A complete trusted Case workspace baseline is required before writes.")
        }
        val route = extractOutputs(context.report)["repository_routing"] as? Map<*, *>
            ?: return rejectRepositoryRouting(context, "REPOSITORY_ROUTING_MISSING", "Preflight must return repository_routing before any repository write.")
        val source = route["pinned_source"] as? Map<*, *>
        val caseWrite = route["case_write"] as? Map<*, *>
        val workstream = route["workstream_context"] as? Map<*, *>
        val artifacts = route["artifact_paths"] as? List<*>
        val caseRevision = context.workspaceDelta.headShaBefore
        val sourceLocation = source?.get("location") as? String
        val sourceRepository = source?.get("repository") as? String
        val sourceWorkingPath = source?.get("working_path") as? String
        val sourceRelativePath = source?.get("relative_path") as? String
        val sourcePath = source?.get("path") as? String
        val sourceRevision = source?.get("revision") as? String
        val coverage = source?.get("coverage") as? String
        val caseRepository = caseWrite?.get("repository") as? String
        val caseWorkingPath = caseWrite?.get("working_path") as? String
        val caseRelativePath = caseWrite?.get("relative_path") as? String
        val reportedCaseRevision = caseWrite?.get("revision") as? String
        val workstreamSource = workstream?.get("source") as? String
        val workstreamRepository = workstream?.get("repository") as? String
        val workstreamCoverage = workstream?.get("coverage") as? String
        val caseContextStatus = workstream?.get("case_context_status") as? String
        val workstreamLocation = workstream?.get("location") as? String
        val workstreamWorkingPath = workstream?.get("working_path") as? String
        val workstreamRelativePath = workstream?.get("relative_path") as? String
        val projectPath = workstream?.get("project_path") as? String
        if (listOf(sourceRevision, reportedCaseRevision, caseRevision).any { it?.matches(Regex("^[0-9a-f]{64}$")) == true }) {
            return rejectRepositoryRouting(
                context, "UNSUPPORTED_GIT_OBJECT_FORMAT",
                "This Forge packet protocol currently supports SHA-1 Git object IDs only; the SHA-256 checkout is blocked before artifact writes.",
            )
        }
        val safeSourceRoute = sourceRepository in setOf("WHOZ", "CODAY") &&
            sourceLocation in setOf("CASE_EXCHANGE", "NAMESPACE_EXCHANGE") &&
            safeAbsoluteCheckoutPath(sourceWorkingPath) &&
            sourceRelativePath?.let(::safeExchangeRelativePath) == true &&
            sourcePath?.let(::safeRelativePath) == true &&
            sourceRevision?.matches(Regex("^[0-9a-f]{40}$")) == true &&
            coverage == "complete" && source?.get("modified") == false
        val safeCaseRoute = caseRepository in setOf("WHOZ", "CODAY") &&
            caseWrite?.get("location") == "CASE_EXCHANGE" && safeAbsoluteCheckoutPath(caseWorkingPath) && caseRelativePath == "repo" &&
            caseRevision?.matches(Regex("^[0-9a-f]{40}$")) == true && reportedCaseRevision == caseRevision
        val safeWorkstream = projectPath == "forge/bmad/workstreams/forge" &&
            safeAbsoluteCheckoutPath(workstreamWorkingPath) && workstreamRelativePath?.let(::safeExchangeRelativePath) == true &&
            ((workstreamSource == "case" && workstreamRepository == caseRepository && workstreamCoverage == "complete" &&
                caseContextStatus == "valid" && workstreamLocation == "CASE_EXCHANGE" && workstreamRelativePath == "repo" &&
                workstreamWorkingPath == caseWorkingPath) ||
                (workstreamSource == "whoz_namespace" && workstreamRepository == "WHOZ" && workstreamCoverage == "complete" &&
                    caseContextStatus in setOf("missing-runtime", "missing-registry") &&
                    workstreamLocation == "NAMESPACE_EXCHANGE" && workstreamWorkingPath != caseWorkingPath &&
                    workstreamRelativePath?.let(::safeExchangeRelativePath) == true))
        val artifactMappings = artifacts.orEmpty().mapNotNull { it as? Map<*, *> }
        val safeArtifacts = artifacts != null && artifacts.isNotEmpty() && artifactMappings.size == artifacts.size &&
            artifactMappings.all { item ->
                val logical = item["logical_path"] as? String
                val casePath = item["case_path"] as? String
                logical?.let(::safeRelativePath) == true && casePath == "repo/$logical" &&
                    logical.startsWith("$projectPath/")
            }
        if (!safeSourceRoute || !safeCaseRoute || !safeWorkstream || !safeArtifacts) {
            return rejectRepositoryRouting(context, "INVALID_REPOSITORY_ROUTING", "Preflight repository routes, revisions, coverage or mapped paths are incomplete or unsafe.")
        }
        val trustedCase = context.externalEvidence["case_write"] as? Map<*, *>
            ?: return rejectRepositoryRouting(context, "CASE_BINDING_UNAVAILABLE", "Factory could not independently observe the Run-bound Case checkout.")
        if (trustedCase["repository"] != caseRepository || trustedCase["location"] != "CASE_EXCHANGE" ||
            trustedCase["relative_path"] != "repo" || trustedCase["working_path"] != caseWorkingPath ||
            trustedCase["revision"] != caseRevision
        ) return rejectRepositoryRouting(context, "CASE_BINDING_MISMATCH", "The reported Case route does not match the Run-bound Git root and revision.")
        val sourceEvidenceMatches = when (sourceLocation) {
            "CASE_EXCHANGE" -> sourceRepository == caseRepository && sourceWorkingPath == caseWorkingPath &&
                sourceRelativePath == caseRelativePath && sourceRevision == caseRevision
            "NAMESPACE_EXCHANGE" -> hasCheckoutToolEvidence(context, source!!)
            else -> false
        }
        if (!sourceEvidenceMatches) return rejectRepositoryRouting(
            context, "PINNED_SOURCE_EVIDENCE_MISSING",
            "The pinned source must match the trusted Case binding or a complete, clean find-checkouts result before any write.",
        )
        val previous = context.run.domainState["repositoryRouting"] as? Map<*, *>
        if (previous != null) {
            val previousSource = previous["pinned_source"] as? Map<*, *>
            val previousCase = previous["case_write"] as? Map<*, *>
            if (previousSource != source ||
                previousCase?.get("repository") != caseRepository || previousCase?.get("location") != "CASE_EXCHANGE" ||
                previousCase?.get("working_path") != caseWorkingPath || previousCase?.get("relative_path") != "repo" ||
                previous["workstream_context"] != workstream || previous["artifact_paths"] != artifacts) {
                return rejectRepositoryRouting(context, "REPOSITORY_BINDING_CHANGED", "A retry must revalidate the same pinned source, workstream root and Case checkout.")
            }
            if (previousCase?.get("revision") != reportedCaseRevision || reportedCaseRevision != caseRevision) {
                return rejectRepositoryRouting(context, "CASE_REVISION_DRIFT", "The Case checkout revision changed since preflight; bind and verify the current revision before writing.")
            }
        }
        if (context.workspaceDelta.headShaAfter != caseRevision) {
            return rejectRepositoryRouting(context, "CASE_REVISION_DRIFT", "The Case revision changed during preflight; no repository write may proceed.")
        }
        val logicalPaths = artifactMappings.mapNotNull { it["logical_path"] as? String }.distinct()
        val persisted = LinkedHashMap(context.run.domainState).apply {
            put("repositoryRouting", route)
            put("repositoryRoutingArtifactPaths", logicalPaths)
        }
        return StepValidationVerdict.Accepted(newDomainState = persisted)
    }

    private fun validatePersistedDeliveryRouting(context: StepValidationContext): StepValidationVerdict.Rejected? {
        val route = context.run.domainState["repositoryRouting"] as? Map<*, *>
            ?: return rejectRepositoryRouting(context, "REPOSITORY_ROUTING_MISSING", "The verified Case write binding is missing; rerun preflight before repository access.")
        val caseWrite = route["case_write"] as? Map<*, *>
        if (caseWrite?.get("location") != "CASE_EXCHANGE" || caseWrite["relative_path"] != "repo" ||
            caseWrite["repository"] !in setOf("WHOZ", "CODAY") ||
            (caseWrite["revision"] as? String)?.matches(Regex("^[0-9a-f]{40}$")) != true
        ) return rejectRepositoryRouting(context, "CASE_BINDING_INVALID", "The persisted Case repo/ binding is invalid; stop before writing.")
        if (context.workspaceDelta.captureStatus != io.whozoss.factory.sdk.spi.WorkspaceCaptureStatus.COMPLETE) {
            return rejectRepositoryRouting(context, "CASE_BINDING_UNAVAILABLE", "A complete Case workspace capture is required before repository access.")
        }
        val expectedRevision = caseWrite["revision"] as String
        val observedBefore = context.workspaceDelta.headShaBefore
        if (observedBefore != expectedRevision) {
            return rejectRepositoryRouting(context, "CASE_REVISION_DRIFT", "Expected Case revision $expectedRevision but observed ${observedBefore ?: "unavailable"}; revalidate the binding before writing.")
        }
        val trustedCase = if (context.stepId !in setOf("implementation-build", "packet-approval")) {
            context.externalEvidence["case_write"] as? Map<*, *>
                ?: return rejectRepositoryRouting(context, "CASE_BINDING_UNAVAILABLE", "Factory did not independently revalidate the Case root after this step.")
        } else null
        if (trustedCase != null) {
            if (trustedCase["repository"] != caseWrite["repository"] || trustedCase["location"] != "CASE_EXCHANGE" ||
                trustedCase["working_path"] != caseWrite["working_path"] || trustedCase["relative_path"] != "repo"
            ) return rejectRepositoryRouting(context, "CASE_BINDING_MISMATCH", "The Case root or repository changed since preflight.")
        }
        val observedAfter = context.workspaceDelta.headShaAfter
        if (observedAfter == null || trustedCase != null && trustedCase["revision"] != observedAfter) {
            return rejectRepositoryRouting(context, "CASE_REVISION_DRIFT", "Factory's post-step Case revision does not match the captured workspace revision.")
        }
        if (context.stepId !in setOf("packet-assembly", "implementation-build") && observedAfter != null && observedAfter != observedBefore) {
            return rejectRepositoryRouting(context, "CASE_REVISION_DRIFT", "The Case revision changed during '${context.stepId}'; only packet assembly or Build may advance it.")
        }
        if (context.stepId in setOf("product-design", "ux-design", "technical-design", "implementation-planning", "qa-design", "packet-assembly")) {
            val mapped = ((route["artifact_paths"] as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList())
                .mapNotNull { (it["case_path"] as? String)?.removePrefix("repo/") }
                .toSet()
            val outside = context.workspaceDelta.changedFilesDuringTurn.keys.filterNot { changed ->
                mapped.any { target -> changed == target || changed.startsWith("$target/") }
            }
            if (outside.isNotEmpty()) {
                return rejectRepositoryRouting(context, "CASE_ARTIFACT_PATH_VIOLATION", "Forge artifacts must be written only to preflight-mapped Case repo/ paths: ${outside.joinToString(", ")}.")
            }
        }
        return null
    }

    private fun rejectRepositoryRouting(context: StepValidationContext, code: String, message: String) =
        StepValidationVerdict.Rejected(
            reasonCode = code,
            findings = listOf(NewFinding(code = code, severity = FindingSeverity.CRITICAL, message = message, stepId = context.stepId)),
        )

    private fun safeExchangeRelativePath(path: String): Boolean = path == "." || safeRelativePath(path)

    private fun safeAbsoluteCheckoutPath(path: String?): Boolean = path?.let {
        runCatching { Path.of(it).isAbsolute && Path.of(it).normalize().toString() == it }.getOrDefault(false)
    } == true

    private fun hasCheckoutToolEvidence(context: StepValidationContext, source: Map<*, *>): Boolean {
        val expected = listOf(
            source["repository"], source["location"], source["working_path"], source["relative_path"],
            source["revision"], "complete", false,
        ).map { it?.toString() ?: return false }
        return context.toolEvents.any { event ->
            event.toolName.contains("find-checkouts", ignoreCase = true) &&
                runCatching { mapper.writeValueAsString(mapOf("summary" to event.summary, "payload" to event.payload)) }
                    .getOrDefault("").let { evidence -> expected.all(evidence::contains) }
        }
    }

    private fun validateDeliveryAssessment(context: StepValidationContext): StepValidationVerdict {
        checkReadOnlyWorkspace(context)?.let { return it }
        val outputs = (ForgeBusinessOutputArtifact.read(context.report) as? ForgeBusinessOutputArtifact.ReadResult.Valid)
            ?.values
        val decisions = outputs?.get("applicability") as? Map<*, *>
        val kinds = setOf("detailed_spec", "ux_spec", "technical_spec", "qa_spec")
        val valid = decisions != null && decisions.keys == kinds && decisions.values.all { value ->
            val decision = value as? Map<*, *>
            decision?.keys == setOf("applicable", "reason") &&
                decision["applicable"] is Boolean &&
                (decision["reason"] as? String)?.let { it.isNotBlank() && it.length <= 4_000 } == true
        }
        if (valid != true) {
            return StepValidationVerdict.Rejected(
                reasonCode = "INVALID_DESIGN_APPLICABILITY",
                findings = listOf(NewFinding(
                    code = "INVALID_DESIGN_APPLICABILITY",
                    severity = FindingSeverity.CRITICAL,
                    message = "Design assessment requires explicit applicable and reason values for detailed, UX, technical and QA specifications in one Forge business-output artifact.",
                    stepId = context.stepId,
                )),
            )
        }
        val nextState = LinkedHashMap(context.run.domainState)
        nextState["applicability"] = decisions
        val references = outputs.get("evidence_refs") as? List<*>
        if (references != null && references.size <= 8 && references.all { it is String && it.isNotBlank() && it.length <= 1_000 }) {
            nextState["designEvidenceRefs"] = references
        }
        return StepValidationVerdict.Accepted(newDomainState = nextState)
    }

    private fun validateDeliveryImplementationPlan(context: StepValidationContext): StepValidationVerdict {
        val plan = extractOutputs(context.report)["delivery_plan"] as? Map<*, *>
        val steps = plan?.get("steps") as? List<*>
        if (steps.isNullOrEmpty()) return rejectDeliveryPlan(context, "MISSING_DELIVERY_PLAN_STEPS")
        val ids = mutableSetOf<String>()
        val valid = steps.all { raw ->
            val step = raw as? Map<*, *> ?: return@all false
            val id = step["id"] as? String
            val paths = step["file_paths"] as? List<*>
            val dependency = step["depends_on"]
            val validDependency = step.containsKey("depends_on") &&
                (dependency == null || dependency is String && dependency in ids)
            val validId = !id.isNullOrBlank() && id.length <= 64 && id.matches(Regex("[A-Za-z][A-Za-z0-9_-]*")) &&
                id !in ids
            if (validId) ids.add(id)
            validId &&
                step["stack"] in setOf("frontend", "backend") && validDependency &&
                !(step["title"] as? String).isNullOrBlank() &&
                !(step["verification"] as? String).isNullOrBlank() &&
                !paths.isNullOrEmpty() && paths.all { it is String && safeRelativePath(it) } &&
                paths.size == paths.distinct().size
        }
        return if (valid) StepValidationVerdict.Accepted(newDomainState = LinkedHashMap(context.run.domainState).apply {
            put("delivery_plan", plan)
        })
        else rejectDeliveryPlan(context, "INVALID_DELIVERY_PLAN_STEP")
    }

    private fun validateDeliveryVerificationPlan(context: StepValidationContext): StepValidationVerdict {
        val content = extractOutputs(context.report)["verification_plan"] as? String
        return if (content != null && content.isNotBlank() && content.length <= 32_000)
            StepValidationVerdict.Accepted(newDomainState = LinkedHashMap(context.run.domainState).apply {
                put("verification_plan", content)
            })
        else rejectDeliveryPlan(context, "EMPTY_VERIFICATION_PLAN")
    }

    private fun validateObservedPacket(context: StepValidationContext): StepValidationVerdict {
        val evidence = context.externalEvidence
        val repository = evidence["repository"] as? String
        val commit = evidence["commit"] as? String
        val manifestSha = evidence["manifestSha256"] as? String
        val canonicalManifest = evidence["canonicalManifest"] as? String
        if (repository.isNullOrBlank() || commit?.matches(Regex("^[0-9a-f]{40}$")) != true ||
            manifestSha?.matches(Regex("^[0-9a-f]{64}$")) != true || canonicalManifest.isNullOrBlank()
        ) return StepValidationVerdict.Rejected("PACKET_GIT_EVIDENCE_MISSING", listOf(NewFinding(
            code = "PACKET_GIT_EVIDENCE_MISSING", severity = FindingSeverity.MAJOR,
            message = "The design packet has no trusted Git observation.", stepId = context.stepId,
        )))
        val packet = mapOf(
            "repository" to repository,
            "commit" to commit,
            "manifestSha256" to manifestSha,
            "canonicalManifest" to canonicalManifest,
        ) + (evidence["packetGeneration"] as? String)?.let { mapOf("packetGeneration" to it) }.orEmpty()
        val clearedTechnicalPauses = context.run.findings
            .filter { it.id.startsWith("technical-pause:") && it.stepId == context.stepId && it.status == FindingStatus.OPEN }
            .associate { it.id to FindingResolution(FindingStatus.RESOLVED, "Pinned Git packet observed again") }
        val nextState = LinkedHashMap(context.run.domainState).apply { put("approvedPacketCandidate", packet) }
        val routing = nextState["repositoryRouting"] as? Map<*, *>
        val currentCase = routing?.get("case_write") as? Map<*, *>
        if (currentCase != null && context.workspaceDelta.headShaAfter == commit) {
            nextState["repositoryRouting"] = LinkedHashMap(routing).apply {
                put("case_write", LinkedHashMap(currentCase).apply { put("revision", commit) })
            }
        } else if (currentCase != null) {
            return rejectRepositoryRouting(context, "CASE_REVISION_DRIFT", "Packet commit does not match the observed Case checkout revision.")
        }
        return StepValidationVerdict.Accepted(
            newDomainState = nextState,
            findingResolutions = clearedTechnicalPauses,
        )
    }

    private fun validateDeliveryBuild(context: StepValidationContext): StepValidationVerdict {
        if (context.agentName != "ForgeDeveloper") return StepValidationVerdict.Rejected("INVALID_BUILD_COORDINATOR")
        val plan = context.run.domainState["delivery_plan"] as? Map<*, *>
        val planned = plan?.get("steps") as? List<*>
        val packet = context.run.domainState["approvedPacketCandidate"] as? Map<*, *>
        val manifestSha = packet?.get("manifestSha256") as? String
        if (planned.isNullOrEmpty() || manifestSha.isNullOrBlank()) {
            return StepValidationVerdict.Rejected("BUILD_INPUT_UNAVAILABLE")
        }
        val identity = mapOf("repository" to packet["repository"], "commit" to packet["commit"],
            "manifestSha256" to manifestSha)
        if (identity.any { (key, value) -> value == null || context.externalEvidence[key] != value }) {
            return StepValidationVerdict.Rejected("APPROVED_PACKET_GIT_UNAVAILABLE")
        }
        val approvals = context.run.humanDecisions.filter {
            it["stepId"] == "packet-approval" && it["outcome"] == "pass" &&
                (it["facts"] as? Map<*, *>)?.get("gate") == "packet-approval"
        }
        val approvalFacts = approvals.singleOrNull()?.get("facts") as? Map<*, *>
        val approvalSource = approvals.singleOrNull()?.get("source") as? Map<*, *>
        if (approvalFacts == null || approvalSource?.get("kind") != "factory-human" ||
            (approvalSource["actorId"] as? String).isNullOrBlank() ||
            (approvalFacts["decidedAt"] as? String).isNullOrBlank() ||
            approvalFacts["gate"] != "packet-approval" || identity.any { (key, value) -> approvalFacts[key] != value }
        ) return StepValidationVerdict.Rejected("PACKET_APPROVAL_MISSING_OR_STALE")
        val report = extractOutputs(context.report)["build_report"] as? Map<*, *>
            ?: return StepValidationVerdict.Rejected("INVALID_BUILD_REPORT")
        val items = report["items"] as? List<*>
        val workCommit = report["work_commit"] as? String
        if (report["manifest_sha256"] != manifestSha ||
            workCommit?.matches(Regex("^[0-9a-f]{40}$")) != true ||
            items?.size != planned.size
        ) return StepValidationVerdict.Rejected("INVALID_BUILD_REPORT")
        val allowed = mutableSetOf<String>()
        for (index in planned.indices) {
            val step = planned[index] as? Map<*, *> ?: return StepValidationVerdict.Rejected("INVALID_BUILD_PLAN")
            val receipt = items[index] as? Map<*, *> ?: return StepValidationVerdict.Rejected("INVALID_BUILD_REPORT")
            val scope = (step["file_paths"] as? List<*>)?.filterIsInstance<String>()?.toSet()
                ?: return StepValidationVerdict.Rejected("INVALID_BUILD_PLAN")
            val changed = receipt["changed_files"] as? List<*>
            if (receipt["step_id"] != step["id"] || receipt["stack"] != step["stack"] ||
                receipt["status"] != "PASS" || (receipt["summary"] as? String).isNullOrBlank() ||
                changed.isNullOrEmpty() || changed.any { it !is String || !safeRelativePath(it) || it !in scope } ||
                changed.size != changed.distinct().size
            ) return StepValidationVerdict.Rejected("INVALID_BUILD_REPORT")
            allowed.addAll(scope)
        }
        if (context.workspaceDelta.changedFilesDuringTurn.keys.any { it !in allowed }) {
            return StepValidationVerdict.Rejected("BUILD_FILE_SCOPE_VIOLATION")
        }
        if (context.workspaceDelta.headShaAfter != workCommit) {
            return rejectRepositoryRouting(context, "CASE_REVISION_DRIFT", "Build work_commit must equal the observed Case checkout revision after Build.")
        }
        return StepValidationVerdict.Accepted(newDomainState = LinkedHashMap(context.run.domainState).apply {
            put("build_report", report)
            val routing = this["repositoryRouting"] as? Map<*, *>
            val currentCase = routing?.get("case_write") as? Map<*, *>
            if (currentCase != null) put("repositoryRouting", LinkedHashMap(routing).apply {
                put("case_write", LinkedHashMap(currentCase).apply { put("revision", workCommit) })
            })
        })
    }

    private fun validateDeliveryVerification(context: StepValidationContext): StepValidationVerdict {
        if (context.agentName != "ForgeVerifier") return StepValidationVerdict.Rejected("INVALID_VERIFICATION_COORDINATOR")
        checkReadOnlyWorkspace(context)?.let { return it }
        val planned = ((context.run.domainState["delivery_plan"] as? Map<*, *>)?.get("steps") as? List<*>)
        val built = context.run.domainState["build_report"] as? Map<*, *>
        val workCommit = built?.get("work_commit") as? String
        val report = extractOutputs(context.report)["verification_report"] as? Map<*, *>
        val items = report?.get("items") as? List<*>
        if (planned.isNullOrEmpty() || workCommit.isNullOrBlank() || report?.get("work_commit") != workCommit ||
            items?.size != planned.size
        ) return StepValidationVerdict.Rejected("INVALID_VERIFICATION_REPORT")
        for (index in planned.indices) {
            val step = planned[index] as? Map<*, *> ?: return StepValidationVerdict.Rejected("INVALID_VERIFICATION_PLAN")
            val receipt = items[index] as? Map<*, *> ?: return StepValidationVerdict.Rejected("INVALID_VERIFICATION_REPORT")
            if (receipt["step_id"] != step["id"] || receipt["stack"] != step["stack"] ||
                receipt["status"] != "PASS" || receipt["green"] != true ||
                receipt["command"] != step["verification"] ||
                (receipt["case_id"] as? String).isNullOrBlank() ||
                (receipt["summary"] as? String).isNullOrBlank()
            ) return StepValidationVerdict.Rejected("INVALID_VERIFICATION_REPORT")
        }
        return StepValidationVerdict.Accepted(newDomainState = LinkedHashMap(context.run.domainState).apply {
            put("verification_report", report)
        })
    }

    private fun validateDeliveryReview(context: StepValidationContext): StepValidationVerdict {
        if (context.agentName != "Reviewer") return StepValidationVerdict.Rejected("INVALID_REVIEWER")
        checkReadOnlyWorkspace(context)?.let { return it }
        val workCommit = (context.run.domainState["build_report"] as? Map<*, *>)?.get("work_commit") as? String
        val verification = context.run.domainState["verification_report"] as? Map<*, *>
        if (workCommit.isNullOrBlank() || verification?.get("work_commit") != workCommit) {
            return StepValidationVerdict.Rejected("REVIEW_INPUT_UNAVAILABLE")
        }
        val report = extractOutputs(context.report)["review_report"] as? Map<*, *>
            ?: return StepValidationVerdict.Rejected("INVALID_REVIEW_REPORT")
        val votes = report["votes"] as? Map<*, *>
        val findings = report["findings"] as? List<*>
        val lenses = listOf("clarity_correctness", "scope_architecture", "security_permissions", "edge_cases_resilience")
        if (report["status"] != "PASS" || report["work_commit"] != workCommit ||
            votes?.keys?.toSet() != lenses.toSet() || lenses.any { votes[it] != "PASS" } ||
            findings == null || findings.any { it !is Map<*, *> ||
                (it["severity"] as? String)?.lowercase() !in setOf("minor", "info") ||
                (it["description"] as? String).isNullOrBlank() }
        ) return StepValidationVerdict.Rejected("INVALID_REVIEW_REPORT")
        return StepValidationVerdict.Accepted(newDomainState = LinkedHashMap(context.run.domainState).apply {
            put("review_report", report)
        })
    }

    private fun rejectDeliveryPlan(context: StepValidationContext, code: String): StepValidationVerdict.Rejected =
        StepValidationVerdict.Rejected(
            reasonCode = code,
            findings = listOf(NewFinding(
                code = code,
                severity = FindingSeverity.CRITICAL,
                message = "Delivery step '${context.stepId}' requires a nonempty, valid plan in forge.business-outputs.v1.",
                stepId = context.stepId,
            )),
        )

    private fun safeRelativePath(path: String): Boolean = path.isNotBlank() && path == path.trim() &&
        !path.startsWith('/') && !path.contains('\\') && !path.contains('\u0000') &&
        path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }

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

    private fun extractOutputs(report: Map<String, Any?>): Map<String, Any?> =
        ForgeBusinessOutputArtifact.values(report)

    private fun extractReportedFindings(context: StepValidationContext): List<NewFinding> {
        val rawFindings = context.report["findings"] as? List<*> ?: return emptyList()
        return rawFindings.mapNotNull { entry ->
            val map = runCatching { mapper.convertValue(entry, Map::class.java) }.getOrNull()
                ?: return@mapNotNull null
            val code = (map["code"] as? String)?.takeIf { it.isNotBlank() } ?: "REVIEW_FINDING"
            val message = (map["message"] as? String)?.takeIf { it.isNotBlank() }
                ?: (map["summary"] as? String)?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val severity = when ((map["severity"] as? String)?.uppercase()) {
                "BLOCKING", "CRITICAL" -> FindingSeverity.CRITICAL
                "ERROR", "MAJOR" -> FindingSeverity.MAJOR
                "WARNING", "MINOR" -> FindingSeverity.MINOR
                "INFO" -> FindingSeverity.INFO
                else -> FindingSeverity.MAJOR
            }
            NewFinding(
                id = map["id"] as? String,
                code = code,
                severity = severity,
                message = message,
                stepId = context.stepId,
                filePath = (map["file"] ?: map["filePath"]) as? String,
            )
        }
    }
}
