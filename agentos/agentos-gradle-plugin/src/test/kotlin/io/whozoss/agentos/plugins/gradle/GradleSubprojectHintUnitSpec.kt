package io.whozoss.agentos.plugins.gradle

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

class GradleSubprojectHintUnitSpec :
    StringSpec({

        val runner = GradleRunner(File("/fake"))
        val tools =
            mapOf(
                "gradle_tests" to GradleTestTool(runner),
                "gradle_compile" to GradleCompileTool(runner),
                "gradle_dependency_insight" to GradleDependencyInsightTool(runner),
            )

        tools.forEach { (toolName, tool) ->
            "$toolName tells the agent how to name a subproject, in its description and in its schema" {
                tool.name shouldBe toolName
                tool.description shouldContain GradleRunner.SUBPROJECT_NAME_HINT
                tool.inputSchema shouldContain GradleRunner.SUBPROJECT_NAME_HINT
            }
        }
    })
