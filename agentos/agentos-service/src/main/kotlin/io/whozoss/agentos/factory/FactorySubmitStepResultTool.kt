package io.whozoss.agentos.factory

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Case-scoped worker result channel. Identity and bearer capability are never model inputs. */
class FactorySubmitStepResultTool(private val baseUrl:String,private val http:OkHttpClient,private val mapper:ObjectMapper,private val bindings:FactoryStepResultBindingRegistry):StandardTool<FactorySubmitStepResultTool.Input>{
 data class Artifact(val kind:String,val encoding:String="markdown",val content:String)
 data class Claims(val modifiedFiles:List<String>)
 data class Finding(val severity:String,val code:String,val summary:String,val file:String?=null,val line:Int?=null)
 data class Input(val status:String,val summary:String,val artifacts:List<Artifact> = emptyList(),val claims:Claims,val findings:List<Finding> = emptyList())
 override val name="FACTORY__submit_step_result";override val description="Submit the authoritative structured result for this Factory step attempt. Attempt identity is injected by the runtime.";override val version="1.0.0";override val paramType=Input::class.java
 override val inputSchema="""{"type":"object","additionalProperties":false,"properties":{"status":{"enum":["PASS","FAIL"]},"summary":{"type":"string","minLength":1,"maxLength":2000},"artifacts":{"type":"array","maxItems":8,"items":{"type":"object","additionalProperties":false,"properties":{"kind":{"type":"string","maxLength":128},"encoding":{"const":"markdown"},"content":{"type":"string","minLength":1,"maxLength":262144}},"required":["kind","encoding","content"]}},"claims":{"type":"object","additionalProperties":false,"properties":{"modifiedFiles":{"type":"array","maxItems":1000,"items":{"type":"string","maxLength":1024}}},"required":["modifiedFiles"]},"findings":{"type":"array","maxItems":100,"items":{"type":"object","additionalProperties":false,"properties":{"severity":{"enum":["info","warning","error","blocking"]},"code":{"type":"string","maxLength":128},"summary":{"type":"string","maxLength":1000},"file":{"type":"string","maxLength":1024},"line":{"type":"integer","minimum":1}},"required":["severity","code","summary"]}}},"required":["status","summary","claims"]}"""
 override suspend fun execute(input:Input?,context:ToolContext):ToolExecutionResult{
  if(input==null)return failure("RESULT_SCHEMA_INVALID","A structured result is required.")
  val caseId=context.caseEvents.mapNotNull{it.caseId}.distinct().singleOrNull()?:return failure("FACTORY_RESULT_CONTEXT_MISSING","This case has no Factory result capability.")
  val agent=context.agentName?:return failure("FACTORY_RESULT_CONTEXT_MISSING","This case has no Factory result capability.")
  val binding=bindings.acquire(caseId,context.namespaceId,agent)?:return failure("FACTORY_RESULT_CONTEXT_MISSING","This case has no available Factory result capability.")
  val body=mapper.writeValueAsString(mapOf("attemptId" to binding.attemptId,"result" to input))
  val request=Request.Builder().url("${baseUrl.trimEnd('/')}/api/factory/agent-step-results").header("Authorization","Bearer ${binding.capabilityToken}").header("X-AgentOS-Case-Id",caseId.toString()).header("X-AgentOS-Agent-Name",agent).post(body.toRequestBody("application/json".toMediaType())).build()
  return try{withContext(Dispatchers.IO){http.newCall(request).execute().use{r->
   val root=runCatching{mapper.readTree(r.body?.string())}.getOrNull()
   val code=root?.path("error")?.path("code")?.asText("FACTORY_REQUEST_FAILED")?:"FACTORY_REQUEST_FAILED"
   when{
    r.isSuccessful->{bindings.acknowledge(binding);ToolExecutionResult.success(mapper.writeValueAsString(root?.path("data")))}
    r.code>=500||code=="RESULT_SCHEMA_INVALID"->{bindings.release(binding);failure(code,"Factory rejected the result: $code (HTTP ${r.code}). Correct the structured arguments and retry.")}
    else->{bindings.invalidate(binding);failure(code,"Factory rejected the result: $code (HTTP ${r.code}).")}
   }
  }}}catch(_:Exception){bindings.release(binding);failure("FACTORY_UNAVAILABLE","Factory is unavailable; the result may be retried.")}
 }
 private fun failure(code:String,message:String)=ToolExecutionResult.error(message,errorType=code,errorMessage=message)
}
