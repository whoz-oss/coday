package io.whozoss.factory.forge.bmad

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.sdk.spi.BriefContext
import io.whozoss.factory.sdk.spi.BriefContribution
import io.whozoss.factory.sdk.spi.FactoryBriefContributor
import org.pf4j.Extension

/**
 * PF4J extension enriching agent briefs with BMAD instructions and declaring
 * `watchedPaths` (`SPEC.md`, `.memlog.md`, grooming report) so the Factory core
 * captures their before/after content SHA-256 and diff (Lot 3 & Lot 4, §5.4).
 */
@Extension
class ForgeBmadBriefContributor : FactoryBriefContributor {

    override fun supports(flowType: String, stepId: String, agentName: String): Boolean =
        flowType.startsWith("forge.")

    override fun contribute(context: BriefContext): BriefContribution {
        val domainState = context.run.domainState
        val specPath = (domainState["spec_path"] as? String)
            ?: (context.fragmentInputs["spec_path"] as? String)
            ?: DEFAULT_SPEC_PATH
        val memlogPath = (domainState["memlog_path"] as? String) ?: DEFAULT_MEMLOG_PATH
        val groomingPath = ((domainState["grooming"] as? Map<*, *>)?.get("path") as? String)
            ?: DEFAULT_GROOMING_PATH

        val sections = mutableListOf<String>()
        // Direct delivery proves its approved documents from pinned Git objects at
        // packet assembly. AgentOS may run in a different container, so local
        // watched-file reads cannot attest the intermediate working tree.
        val watched = if (context.flowType == "forge.delivery") linkedSetOf<String>()
            else linkedSetOf(specPath, memlogPath)

        (domainState["run_brief"] as? String)?.let { brief ->
            sections += "## Durable Forge run brief\n$brief"
        }

        if (context.flowType == "forge.delivery") {
            val repositoryRouting = domainState["repositoryRouting"] as? Map<*, *>
            if (context.stepId == "delivery-preflight") {
                sections += """
                    ## Forge repository routing preflight
                    - Read the pinned source through its exact Case or Namespace route and report its repository, exchange location, actual relativePath (including `.` at exchange root), source file path relative to that Git root, full 40-character revision, and complete discovery coverage.
                    - Resolve `forge` with the existing `bmad-workstream-context` script, first using the Case project root. Use Whoz Namespace fallback only when the Case BMAD runtime or Forge registry is absent; require exactly one complete, valid Whoz checkout. Never mask invalid local configuration.
                    - Return `repository_routing` in `forge.business-outputs.v1`: `pinned_source {repository, location, working_path, relative_path, path, revision, coverage, modified}`, `case_write {repository, location: CASE_EXCHANGE, working_path, relative_path: repo, revision}`, `workstream_context {source: case|whoz_namespace, repository, coverage, case_context_status: valid|missing-runtime|missing-registry, location, working_path, relative_path, project_path: forge/bmad/workstreams/forge}`, and `artifact_paths[] {logical_path, case_path}`. Each `working_path` is the exact absolute Git root. Namespace sources require `coverage: complete`, `modified: false`, and a matching observed `find-checkouts` tool result; a Case source must match the trusted Case root and revision exactly. Every `case_path` must equal `repo/<logical_path>`.
                    - The Case Git checkout under `repo/` is the only write route for designs, plans, tests, packet and code. Namespace access is read-only. Stop before any write on an unavailable or ambiguous route, incomplete discovery, changed binding, malformed path, or path escape.
                """.trimIndent()
            } else if (repositoryRouting != null) {
                sections += """
                    ## Bound Forge repository routes
                    Persisted routing binding: ${ObjectMapper().writeValueAsString(repositoryRouting)}
                    - Read the pinned source only from the recorded exchange, checkout relativePath, file path and full revision.
                    - Route every write through CASE_EXCHANGE `repo/<mapped path>` at the persisted Case checkout. Revalidate the Case root and expected revision before writing. Do not write through Namespace tools or substitute another checkout.
                """.trimIndent()
            } else {
                sections += "Forge repository routing is unavailable. Do not read or write repository files; report the missing preflight binding."
            }
            when (context.stepId) {
                "implementation-planning" -> sections += "Write the implementation plan document as one JSON object equal to the submitted delivery_plan, with no surrounding Markdown."
                "qa-design" -> sections += "Write the verification plan document with exactly the UTF-8 bytes of submitted verification_plan, including its final newline if present."
                "packet-assembly" -> sections += "Copy the four accepted design applicability decisions into the manifest verbatim, including reasons. Do not rewrite either plan."
            }
            val decisionKey = when (context.stepId) {
                "product-design" -> "detailed_spec"
                "ux-design" -> "ux_spec"
                "technical-design" -> "technical_spec"
                "qa-design" -> "qa_spec"
                else -> null
            }
            val decision = (domainState["applicability"] as? Map<*, *>)?.get(decisionKey) as? Map<*, *>
            if (decisionKey != null && decision?.get("applicable") is Boolean) {
                sections += "Design applicability for $decisionKey: ${decision["applicable"]}. Reason: ${decision["reason"]}"
            }
            val references = (domainState["designEvidenceRefs"] as? List<*>)?.filterIsInstance<String>().orEmpty()
            if (references.isNotEmpty() && context.stepId in setOf(
                    "product-design", "ux-design", "technical-design", "implementation-planning", "qa-design", "packet-assembly",
                )) {
                sections += "Pinned design sources:\n${references.joinToString("\n") { "- $it" }}"
            }
            if (context.stepId in setOf("implementation-build", "implementation-verification", "implementation-review")) {
                val packet = domainState["approvedPacketCandidate"] as? Map<*, *>
                val plan = domainState["delivery_plan"] as? Map<*, *>
                val approval = context.run.humanDecisions.lastOrNull {
                    val facts = it["facts"] as? Map<*, *>
                    val source = it["source"] as? Map<*, *>
                    it["stepId"] == "packet-approval" && it["outcome"] == "pass" &&
                        facts?.get("gate") == "packet-approval" &&
                        facts["repository"] == packet?.get("repository") &&
                        facts["commit"] == packet?.get("commit") &&
                        facts["manifestSha256"] == packet?.get("manifestSha256") &&
                        facts["packetGeneration"] == packet?.get("packetGeneration") &&
                        source?.get("kind") == "factory-human"
                }
                if (packet != null && plan != null && approval != null) {
                    sections += buildString {
                        appendLine("## Approved Forge delivery inputs")
                        appendLine("Packet repository: ${packet["repository"]}")
                        appendLine("Packet commit: ${packet["commit"]}")
                        appendLine("Manifest SHA-256: ${packet["manifestSha256"]}")
                        packet["packetGeneration"]?.let { appendLine("Packet generation: $it") }
                        appendLine("The packet commit pins the six source documents. The manifest may be committed later because it names that source commit; verify its canonical digest against the approved digest, not its presence in the source commit.")
                        appendLine("Packet approval: ${ObjectMapper().writeValueAsString(approval)}")
                        appendLine("Accepted delivery_plan: ${ObjectMapper().writeValueAsString(plan)}")
                        if (context.stepId == "implementation-build") {
                            appendLine("Write authorization: the approved Build may edit only its selected Case Git worktree and the plan's file_paths. Use a local work branch; do not push.")
                        }
                        (domainState["build_report"] as? Map<*, *>)?.let {
                            appendLine("Accepted build_report: ${ObjectMapper().writeValueAsString(it)}")
                        }
                        (domainState["verification_report"] as? Map<*, *>)?.let {
                            appendLine("Accepted verification_report: ${ObjectMapper().writeValueAsString(it)}")
                        }
                    }.trimEnd()
                }
            }
        }

        when (context.flowType) {
            ForgeBmadCatalogContributor.FLOW_PRODUCT_SPEC -> {
                sections += """
                    ## Forge BMAD — Product Specification Contract
                    - Author or amend `$specPath` (Product section) and record reasoning in `$memlogPath`.
                    - Both `$specPath` and `$memlogPath` are watched by the Factory; an unchanged file will be rejected as a phantom artifact.
                """.trimIndent()
            }

            ForgeBmadCatalogContributor.FLOW_PRODUCT_APPROVAL -> {
                watched += groomingPath
                sections += """
                    ## Forge BMAD — Product Grooming Contract
                    - Materialize the grooming report at `$groomingPath` for `$specPath`.
                    - The SHA-256 of `$groomingPath` is frozen at presentation and verified at human approval.
                """.trimIndent()
            }

            ForgeBmadCatalogContributor.FLOW_ASSESSMENT -> {
                sections += """
                    ## Forge BMAD — Read-Only Assessment Contract
                    - Inspect `$specPath` in read-only mode. Do NOT modify `$specPath` or any repository file.
                    - For UX assessment (`ForgeUXDesigner`), return `ux_applicable: Boolean` in the Forge business-output artifact.
                    - For Technical assessment (`ForgeTechDesigner`), return `tech_spec_applicable: Boolean` (or `trivial: Boolean` with `trivial_scope: { file_paths: [...], verification: "..." }`) in that artifact.
                """.trimIndent()
            }

            ForgeBmadCatalogContributor.FLOW_UX_DESIGN -> {
                sections += """
                    ## Forge BMAD — UX Specification Amendment Contract
                    - Amend the `## UX` section of `$specPath` and update `$memlogPath`.
                """.trimIndent()
            }

            ForgeBmadCatalogContributor.FLOW_TECH_DESIGN -> {
                if (context.stepId == "codebase-research") {
                    sections += """
                        ## Forge BMAD — Codebase Research Contract (Read-Only)
                        - Research the codebase for `$specPath`. Do NOT modify any file (`$specPath` write-lock is enforced).
                    """.trimIndent()
                } else {
                    sections += """
                        ## Forge BMAD — Technical Design & Structured Delivery Plan Contract
                        - Amend the `## Technical` section of `$specPath` and update `$memlogPath`.
                        - Return the structured delivery plan in the Forge business-output artifact as `delivery_plan.steps[]` (each step with `id`, `title`, `file_paths`, and `verification`). Do NOT write a separate `DELIVERY_PLAN.md` file (D8).
                    """.trimIndent()
                }
            }

            ForgeBmadCatalogContributor.FLOW_IMPLEMENT_STEP,
            ForgeBmadCatalogContributor.FLOW_CORRECTION,
            -> {
                val stepIndex = context.fragmentInputs["step_index"] ?: 0
                @Suppress("UNCHECKED_CAST")
                val planStep = context.fragmentInputs["step"] as? Map<String, Any?>
                val allowedFiles = (planStep?.get("file_paths") as? List<*>)?.filterIsInstance<String>().orEmpty()
                if (context.stepId == "step-implementation" || context.stepId == "step-correction") {
                    sections += buildString {
                        appendLine("## Forge BMAD — Unified Builder Contract (Step #$stepIndex)")
                        appendLine("- Use the appropriate `front` or `back` skill according to the target files.")
                        appendLine("- Do NOT modify `$specPath` (write-lock enforced by Factory validator).")
                        if (allowedFiles.isNotEmpty()) {
                            appendLine("- Allowed file scope for this step: ${allowedFiles.joinToString(", ")}")
                        }
                        val findings = context.run.findings.filter { it.status.name == "OPEN" }
                        if (findings.isNotEmpty()) {
                            appendLine("- Open findings to resolve:")
                            findings.forEach { f -> appendLine("  - [${f.severity}] ${f.code}: ${f.message}") }
                        }
                    }.trimEnd()
                } else {
                    sections += """
                        ## Forge BMAD — Step Verifier Contract (Read-Only)
                        - Verify step #$stepIndex against `$specPath`. Do NOT modify any file.
                    """.trimIndent()
                }
            }

            ForgeBmadCatalogContributor.FLOW_CODE_REVIEW -> {
                sections += """
                    ## Forge BMAD — Adversarial Code Review Contract (Read-Only)
                    - Review the implementation against `$specPath` across all 4 axes. Do NOT modify any file.
                """.trimIndent()
            }
        }

        return BriefContribution(
            sections = sections,
            watchedPaths = watched.toList(),
        )
    }

    companion object {
        const val DEFAULT_SPEC_PATH = "SPEC.md"
        const val DEFAULT_MEMLOG_PATH = ".memlog.md"
        const val DEFAULT_GROOMING_PATH = "GROOMING.md"
    }
}
