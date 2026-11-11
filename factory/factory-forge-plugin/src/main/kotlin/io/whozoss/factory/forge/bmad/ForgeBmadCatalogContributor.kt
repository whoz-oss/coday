package io.whozoss.factory.forge.bmad

import io.whozoss.factory.sdk.spi.FactoryFlowCatalogContributor
import org.pf4j.Extension

/**
 * PF4J extension contributing the 10 static BMAD flow fragments of
 * `forge-controller-v1` (Lot 4, §4).
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

        val ALL_FLOW_JSONS: List<String> = listOf(
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
    }
}
