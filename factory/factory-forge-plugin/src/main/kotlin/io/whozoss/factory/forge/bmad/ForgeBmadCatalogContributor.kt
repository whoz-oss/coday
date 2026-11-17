package io.whozoss.factory.forge.bmad

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.whozoss.factory.sdk.spi.FactoryFlowCatalogContributor
import org.pf4j.Extension

/**
 * PF4J extension contributing the 10 static BMAD flow fragments of
 * `forge-bmad` runs.
 *
 * Notice that implementation and correction use the unified `ForgeBuilder` and
 * `ForgeVerifier` agents (D8), keeping the catalog minimal and 100% static.
 */
@Extension
class ForgeBmadCatalogContributor : FactoryFlowCatalogContributor {

    override fun flows(): List<String> = ALL_FLOW_JSONS

    companion object {
        const val FLOW_PRODUCT_SPEC = "forge.product-spec"
        const val FLOW_PRODUCT_APPROVAL = "forge.product-approval"
        const val FLOW_ASSESSMENT = "forge.assessment"
        const val FLOW_UX_DESIGN = "forge.ux-design"
        const val FLOW_TECH_DESIGN = "forge.tech-design"
        const val FLOW_SPEC_APPROVAL = "forge.spec-approval"
        const val FLOW_IMPLEMENT_STEP = "forge.implement-step"
        const val FLOW_CORRECTION = "forge.correction"
        const val FLOW_CODE_REVIEW = "forge.code-review"
        const val FLOW_ACCEPTANCE = "forge.acceptance"

        private val LEGACY_FLOW_JSONS: List<String> = listOf(
            // 1. forge.product-spec
            """
            {
              "schemaVersion": "1",
              "workflowType": "forge.product-spec",
              "version": "1.0.0",
              "title": "Forge BMAD — Product Specification",
              "execution": { "plugin": "forge" },
              "steps": [
                {
                  "id": "product-specification",
                  "name": "Product specification",
                  "responsibility": { "kind": "agent", "name": "ForgeProductWorker" },
                  "dependsOn": []
                }
              ]
            }
            """.trimIndent(),

            // 2. forge.product-approval
            """
            {
              "schemaVersion": "1",
              "workflowType": "forge.product-approval",
              "version": "1.0.0",
              "title": "Forge BMAD — Product Grooming & Approval",
              "execution": { "plugin": "forge" },
              "steps": [
                {
                  "id": "product-grooming",
                  "name": "Product grooming report",
                  "responsibility": { "kind": "agent", "name": "ForgeProductWorker" },
                  "dependsOn": []
                },
                {
                  "id": "product-approval",
                  "name": "Product approval",
                  "responsibility": { "kind": "human", "name": "Product owner" },
                  "dependsOn": ["product-grooming"]
                }
              ]
            }
            """.trimIndent(),

            // 3. forge.assessment
            """
            {
              "schemaVersion": "1",
              "workflowType": "forge.assessment",
              "version": "1.0.0",
              "title": "Forge BMAD — UX & Technical Assessment",
              "execution": { "plugin": "forge" },
              "steps": [
                {
                  "id": "ux-assessment",
                  "name": "UX assessment",
                  "responsibility": { "kind": "agent", "name": "ForgeUXDesigner" },
                  "dependsOn": []
                },
                {
                  "id": "technical-assessment",
                  "name": "Technical assessment",
                  "responsibility": { "kind": "agent", "name": "ForgeTechDesigner" },
                  "dependsOn": []
                }
              ]
            }
            """.trimIndent(),

            // 4. forge.ux-design
            """
            {
              "schemaVersion": "1",
              "workflowType": "forge.ux-design",
              "version": "1.0.0",
              "title": "Forge BMAD — UX Specification Amendment",
              "execution": { "plugin": "forge" },
              "steps": [
                {
                  "id": "ux-design",
                  "name": "UX specification amendment",
                  "responsibility": { "kind": "agent", "name": "ForgeUXDesigner" },
                  "dependsOn": []
                }
              ]
            }
            """.trimIndent(),

            // 5. forge.tech-design
            """
            {
              "schemaVersion": "1",
              "workflowType": "forge.tech-design",
              "version": "1.0.0",
              "title": "Forge BMAD — Codebase Research & Technical Design",
              "execution": { "plugin": "forge" },
              "steps": [
                {
                  "id": "codebase-research",
                  "name": "Codebase research for technical design",
                  "responsibility": { "kind": "agent", "name": "Searcher" },
                  "dependsOn": []
                },
                {
                  "id": "technical-design",
                  "name": "Technical specification and ordered delivery steps",
                  "responsibility": { "kind": "agent", "name": "ForgeTechDesigner" },
                  "dependsOn": ["codebase-research"]
                }
              ]
            }
            """.trimIndent(),

            // 6. forge.spec-approval
            """
            {
              "schemaVersion": "1",
              "workflowType": "forge.spec-approval",
              "version": "1.0.0",
              "title": "Forge BMAD — Combined Specification Approval",
              "execution": { "plugin": "forge" },
              "steps": [
                {
                  "id": "specification-approval",
                  "name": "Combined specification approval",
                  "responsibility": { "kind": "human", "name": "Product and technical authority" },
                  "dependsOn": []
                }
              ]
            }
            """.trimIndent(),

            // 7. forge.implement-step (Unified ForgeBuilder + parameterized code verify + ForgeVerifier, D8)
            """
            {
              "schemaVersion": "1",
              "workflowType": "forge.implement-step",
              "version": "1.0.0",
              "title": "Forge BMAD — Delivery Step Implementation & Verification",
              "execution": { "plugin": "forge" },
              "steps": [
                {
                  "id": "step-implementation",
                  "name": "Delivery step implementation",
                  "responsibility": { "kind": "agent", "name": "ForgeBuilder" },
                  "dependsOn": []
                },
                {
                  "id": "step-build-test",
                  "name": "Delivery step build and test verification",
                  "responsibility": { "kind": "code", "name": "from-inputs" },
                  "dependsOn": ["step-implementation"]
                },
                {
                  "id": "step-verification",
                  "name": "Delivery step verification",
                  "responsibility": { "kind": "agent", "name": "ForgeVerifier" },
                  "dependsOn": ["step-build-test"]
                }
              ]
            }
            """.trimIndent(),

            // 8. forge.correction (Unified ForgeBuilder + parameterized code verify + ForgeVerifier, D8)
            """
            {
              "schemaVersion": "1",
              "workflowType": "forge.correction",
              "version": "1.0.0",
              "title": "Forge BMAD — Bounded Step Correction & Re-verification",
              "execution": { "plugin": "forge" },
              "steps": [
                {
                  "id": "step-correction",
                  "name": "Delivery step correction",
                  "responsibility": { "kind": "agent", "name": "ForgeBuilder" },
                  "dependsOn": []
                },
                {
                  "id": "step-build-test",
                  "name": "Delivery step build and test verification",
                  "responsibility": { "kind": "code", "name": "from-inputs" },
                  "dependsOn": ["step-correction"]
                },
                {
                  "id": "step-verification",
                  "name": "Delivery step verification",
                  "responsibility": { "kind": "agent", "name": "ForgeVerifier" },
                  "dependsOn": ["step-build-test"]
                }
              ]
            }
            """.trimIndent(),

            // 9. forge.code-review
            """
            {
              "schemaVersion": "1",
              "workflowType": "forge.code-review",
              "version": "1.0.0",
              "title": "Forge BMAD — Adversarial Code Review",
              "execution": { "plugin": "forge" },
              "steps": [
                {
                  "id": "code-review",
                  "name": "Adversarial code review",
                  "responsibility": { "kind": "agent", "name": "ForgeReviewer" },
                  "dependsOn": []
                }
              ]
            }
            """.trimIndent(),

            // 10. forge.acceptance (Option C: Human functional approval in v1, D11)
            """
            {
              "schemaVersion": "1",
              "workflowType": "forge.acceptance",
              "version": "1.0.0",
              "title": "Forge BMAD — Functional Acceptance Approval",
              "execution": { "plugin": "forge" },
              "steps": [
                {
                  "id": "functional-approval",
                  "name": "Functional approval evidence",
                  "responsibility": { "kind": "human", "name": "Functional authority" },
                  "dependsOn": []
                }
              ]
            }
            """.trimIndent(),
        )

        /** The Factory-owned contract replaces the old controller-specific catalog. */
        private val mapper = ObjectMapper()
        val ALL_FLOW_JSONS: List<String> = LEGACY_FLOW_JSONS.map(::factoryContractVersion)

        private fun factoryContractVersion(raw: String): String {
            val definition = mapper.readTree(raw) as ObjectNode
            val flowType = definition.path("workflowType").asText()
            definition.put("version", "1.1.0")
            definition.path("steps").forEach { stepNode ->
                val step = stepNode as ObjectNode
                val stepId = step.path("id").asText()
                val responsibility = step.path("responsibility") as ObjectNode
                if (responsibility.path("kind").asText() == "agent") {
                    val name = responsibility.path("name").asText()
                    if (name.startsWith("Forge")) responsibility.put("name", "${name}Factory")
                }
                val contract = stepContract(flowType, stepId)
                step.set<com.fasterxml.jackson.databind.JsonNode>("entry", mapper.valueToTree(contract.first))
                step.put("mission", contract.second)
                step.set<com.fasterxml.jackson.databind.JsonNode>("exit", mapper.valueToTree(contract.third))
            }
            return mapper.writeValueAsString(definition)
        }

        private fun stepContract(flow: String, step: String): Triple<List<String>, String, List<String>> {
            val runBrief = "Read the durable run-brief and preserve its objective, scope, acceptance criteria, constraints and open questions."
            val spec = "Read the approved Whoz specification and its revision from the spec workspace; do not infer it from CONFIG_PATH."
            val code = "Use the code workspace binding and its verified diff base; do not edit the spec workspace."
            val result = "Submit one Factory result with evidence, findings and step-specific structured outputs; claims.modifiedFiles are declarations, not diff proof."
            return when (flow to step) {
                FLOW_PRODUCT_SPEC to "product-specification" -> Triple(
                    listOf("Read the original request, ticket evidence and both workspace bindings."),
                    "Create the run-brief and apply the product intent and acceptance-criteria amendment through bmad-spec in the Whoz spec workspace.",
                    listOf("Provide a bounded run_brief string in result.outputs with objective, scope, acceptance criteria, constraints and open questions; Factory persists it for later fragments.", "Provide the product specification revision and amendment provenance.", result),
                )
                FLOW_PRODUCT_APPROVAL to "product-grooming" -> Triple(
                    listOf(runBrief, "Read recorded grooming decisions and the product spec revision."),
                    "Materialize a traceable grooming report without inventing human decisions.",
                    listOf("Provide the report path, evidence references and unresolved questions.", result),
                )
                FLOW_PRODUCT_APPROVAL to "product-approval" -> Triple(
                    listOf(runBrief, "Review the grooming report and exact product spec revision."),
                    "Approve or reject product intent and acceptance criteria only.",
                    listOf("Record the human decision against the presented spec revision and evidence."),
                )
                FLOW_ASSESSMENT to "ux-assessment" -> Triple(
                    listOf(runBrief, spec), "Assess UX necessity as none, compact or full with evidence.",
                    listOf("Provide structured ux_applicable assessment and its reasons.", result),
                )
                FLOW_ASSESSMENT to "technical-assessment" -> Triple(
                    listOf(runBrief, spec), "Assess technical design necessity as trivial or spec-required with evidence.",
                    listOf("Provide structured tech_spec_applicable assessment and its reasons.", result),
                )
                FLOW_UX_DESIGN to "ux-design" -> Triple(
                    listOf(runBrief, spec, "Read the accepted UX assessment."),
                    "Apply the UX contract amendment through bmad-spec in the Whoz spec workspace.",
                    listOf("Provide the new UX spec revision, provenance and any unresolved findings.", result),
                )
                FLOW_TECH_DESIGN to "codebase-research" -> Triple(
                    listOf(runBrief, spec, code), "Research the selected codebase for the technical design.",
                    listOf("Provide evidence-backed codebase findings without changing either repository.", result),
                )
                FLOW_TECH_DESIGN to "technical-design" -> Triple(
                    listOf(runBrief, spec, "Read codebase research and accepted assessment."),
                    "Apply the technical amendment through bmad-spec and define ordered, bounded delivery steps.",
                    listOf("Provide the technical spec revision and structured delivery_plan.steps.", result),
                )
                FLOW_SPEC_APPROVAL to "specification-approval" -> Triple(
                    listOf(runBrief, "Review exact product, UX and technical spec revisions and open findings."),
                    "Approve or reject the combined specification and delivery plan.",
                    listOf("Record the human decision against the presented revisions and evidence."),
                )
                FLOW_IMPLEMENT_STEP to "step-implementation", FLOW_CORRECTION to "step-correction" -> Triple(
                    listOf(runBrief, spec, code, "Read the current delivery step and, for corrections, the accepted findings."),
                    "Implement only the current delivery step in the code workspace; resolve correction findings when present.",
                    listOf("Provide changed-file claims, implementation evidence and remaining findings.", result),
                )
                FLOW_IMPLEMENT_STEP to "step-build-test", FLOW_CORRECTION to "step-build-test" -> Triple(
                    listOf("Read the current delivery step, code workspace binding and repository-specific verification commands."),
                    "Run the declared build and tests in the code workspace.",
                    listOf("Persist command, exit status and output as verification evidence."),
                )
                FLOW_IMPLEMENT_STEP to "step-verification", FLOW_CORRECTION to "step-verification" -> Triple(
                    listOf(runBrief, spec, code, "Read implementation diff and build/test evidence."),
                    "Independently verify the current step against its acceptance criteria and actual diff.",
                    listOf("Provide PASS or actionable findings backed by observed evidence.", result),
                )
                FLOW_CODE_REVIEW to "code-review" -> Triple(
                    listOf(runBrief, spec, code, "Read exact diff, verification evidence and unresolved findings."),
                    "Review correctness, scope, security and resilience without granting approval.",
                    listOf("Provide lens votes and classified findings; a clean review alone is not approval.", result),
                )
                FLOW_ACCEPTANCE to "functional-approval" -> Triple(
                    listOf(runBrief, "Review approved spec revisions, verified diff, review and test evidence."),
                    "Approve or reject functional acceptance of the delivered feature.",
                    listOf("Record the human decision against the presented evidence and revisions."),
                )
                else -> error("Missing Factory step contract for $flow/$step")
            }
        }
    }
}
