package io.whozoss.agentos.plugins.factorybridge

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.plugins.factorybridge.tools.FactorySubmitStepResultTool

class FactorySubmitStepResultWireSpec : StringSpec({
    val mapper = jacksonObjectMapper()

    "actual plugin DTO emits delivery analysis and omits absent nullable fields" {
        val input = FactorySubmitStepResultTool.Input(
            status = "PASS",
            summary = "done",
            artifacts = listOf(FactorySubmitStepResultTool.Artifact("run-brief", content = "# Run brief")),
            claims = FactorySubmitStepResultTool.Claims(
                modifiedFiles = emptyList(),
                deliveryAnalysis = FactorySubmitStepResultTool.DeliveryAnalysis(
                    changeType = "bugfix",
                    ticket = "WZ-33157",
                    title = "Complete RAWeb 5.4 accessible table names",
                    baseBranch = "sprint",
                ),
            ),
            findings = listOf(FactorySubmitStepResultTool.Finding("blocking", "A11Y", "Needs verification")),
        )

        val result = mapper.readTree(mapper.writeValueAsString(input))
        result.path("claims").path("deliveryAnalysis").path("baseBranch").asText() shouldBe "sprint"
        result.path("findings").single().has("file") shouldBe false
        result.path("findings").single().has("line") shouldBe false
    }

    "actual plugin DTO omits absent optional delivery analysis and base branch" {
        val claims = FactorySubmitStepResultTool.Claims(emptyList())
        mapper.readTree(mapper.writeValueAsString(claims)).has("deliveryAnalysis") shouldBe false

        val delivery = FactorySubmitStepResultTool.DeliveryAnalysis("feature", "WZ-1", "Title")
        mapper.readTree(mapper.writeValueAsString(delivery)).has("baseBranch") shouldBe false
    }
})
