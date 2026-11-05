package io.whozoss.factory.forge.bmad

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
        val watched = linkedSetOf(specPath, memlogPath)

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
                    - For UX assessment (`ForgeUXDesigner`), return `ux_applicable: Boolean` in your report outputs.
                    - For Technical assessment (`ForgeTechDesigner`), return `tech_spec_applicable: Boolean` (or `trivial: Boolean` with `trivial_scope: { file_paths: [...], verification: "..." }`).
                """.trimIndent()
            }

            ForgeBmadCatalogContributor.FLOW_UX_DESIGN -> {
                sections += """
                    ## Forge BMAD — UX Specification Amendment Contract
                    - Amend the `## UX` section of `$specPath` and update `$memlogPath`.
                """.trimIndent()
            }

            ForgeBmadCatalogContributor.FLOW_TECH_DESIGN -> {
                if (context.agentName == "Searcher") {
                    sections += """
                        ## Forge BMAD — Codebase Research Contract (Read-Only)
                        - Research the codebase for `$specPath`. Do NOT modify any file (`$specPath` write-lock is enforced).
                    """.trimIndent()
                } else {
                    sections += """
                        ## Forge BMAD — Technical Design & Structured Delivery Plan Contract
                        - Amend the `## Technical` section of `$specPath` and update `$memlogPath`.
                        - Return the structured delivery plan in your report outputs as `delivery_plan.steps[]` (each step with `id`, `title`, `file_paths`, and `verification`). Do NOT write a separate `DELIVERY_PLAN.md` file (D8).
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
                if (context.agentName == "ForgeBuilder") {
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
