package io.whozoss.agentos.plugins.datetime

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class GetCurrentDateTimeToolTest : StringSpec({
    "should return current datetime in UTC by default" {
        val tool = GetCurrentDateTimeTool()
        val result = tool.execute(null)

        result shouldContain "success"
        result shouldContain "datetime"
        result shouldContain "UTC"
    }

    "should return datetime in specified timezone" {
        val tool = GetCurrentDateTimeTool()
        val input = GetCurrentDateTimeTool.Input(timezone = "America/New_York")

        val result = tool.execute(input)

        result shouldContain "America/New_York"
        result shouldContain "datetime"
        result shouldContain "\"success\":true"
    }

    "should return error for invalid timezone" {
        val tool = GetCurrentDateTimeTool()
        val input = GetCurrentDateTimeTool.Input(timezone = "Invalid/Timezone")

        val result = tool.execute(input)

        result shouldContain "\"success\":false"
        result shouldContain "error"
        result shouldContain "Invalid timezone"
    }

    "should have correct metadata" {
        val tool = GetCurrentDateTimeTool()

        tool.name shouldBe "GetCurrentDateTime"
        tool.version shouldBe "1.0.0"
        tool.inputSchema shouldContain "timezone"
    }
})
