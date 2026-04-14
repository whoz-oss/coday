package io.whozoss.agentos.sdk.tool

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.auth.ToolCategory

class StandardToolCategorySpec : StringSpec() {

    private class TestTool : StandardTool<Unit> {
        override val name = "test-tool"
        override val description = "A test tool"
        override val inputSchema = "{}"
        override val version = "1.0"
        override val paramType: Class<Unit>? = null
        override fun execute(input: Unit?): String = "executed"
    }

    private class TestWriteTool : StandardTool<Unit> {
        override val name = "test-write-tool"
        override val description = "A write tool"
        override val inputSchema = "{}"
        override val version = "1.0"
        override val paramType: Class<Unit>? = null
        override val category = ToolCategory.WRITE
        override fun execute(input: Unit?): String = "executed"
    }

    private class TestDestructiveTool : StandardTool<Unit> {
        override val name = "test-destructive-tool"
        override val description = "A destructive tool"
        override val inputSchema = "{}"
        override val version = "1.0"
        override val paramType: Class<Unit>? = null
        override val category = ToolCategory.DESTRUCTIVE
        override fun execute(input: Unit?): String = "executed"
    }

    init {
        "StandardTool without category override defaults to READ_ONLY" {
            val tool = TestTool()
            tool.category shouldBe ToolCategory.READ_ONLY
        }

        "StandardTool with category override WRITE returns WRITE" {
            val tool = TestWriteTool()
            tool.category shouldBe ToolCategory.WRITE
        }

        "StandardTool with category override DESTRUCTIVE returns DESTRUCTIVE" {
            val tool = TestDestructiveTool()
            tool.category shouldBe ToolCategory.DESTRUCTIVE
        }
    }
}
