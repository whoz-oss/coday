package io.whozoss.agentos.factory

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.tool.ToolContext
import okhttp3.OkHttpClient
import java.util.UUID

/** Source-only contract scenarios. Execution is deliberately left to the maintainer. */
class FactorySubmitStepResultToolSpec : StringSpec({
 "tool is fail-closed outside a Factory-bound case" {
  val tool=FactorySubmitStepResultTool("http://127.0.0.1:3141",OkHttpClient(),jacksonObjectMapper(),FactoryStepResultBindingRegistry())
  val input=FactorySubmitStepResultTool.Input("PASS","ok",claims=FactorySubmitStepResultTool.Claims(emptyList()))
  val result=tool.execute(input,ToolContext(UUID.randomUUID(),null,null,emptyList(),"Worker"))
  result.success shouldBe false
  result.errorType shouldBe "FACTORY_RESULT_CONTEXT_MISSING"
 }
})
