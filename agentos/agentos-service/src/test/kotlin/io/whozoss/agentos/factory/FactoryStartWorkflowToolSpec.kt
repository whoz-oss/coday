package io.whozoss.agentos.factory

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.caseEvent.CaseStatusEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.ToolContext
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.util.UUID

class FactoryStartWorkflowToolSpec : StringSpec({
 val mapper=jacksonObjectMapper()
 "strict schema and trusted HTTP attribution" {
  var path=""; var body=""; val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0); server.createContext("/"){ ex->path=ex.requestURI.toString();body=ex.requestBody.bufferedReader().readText();val bytes="""{"data":{"workflowId":"wf-1","revision":1,"created":true,"idempotent":false,"governanceMode":"governed","definitionVersion":"1.0.0","definitionHash":"hash","projection":{}}}""".toByteArray();ex.sendResponseHeaders(201,bytes.size.toLong());ex.responseBody.use{it.write(bytes)}};server.start()
  try { val tool=FactoryStartWorkflowTool("http://127.0.0.1:${server.address.port}",OkHttpClient(),mapper,"runtime-configured"); val schema=mapper.readTree(tool.inputSchema); schema.path("additionalProperties").asBoolean() shouldBe false; schema.path("properties").fieldNames().asSequence().toSet() shouldBe setOf("workflowId","workflowType","title")
   val ns=UUID.randomUUID();val case=UUID.randomUUID();val context=ToolContext(ns,UUID.randomUUID(),"actor-external",listOf(CaseStatusEvent(metadata=EntityMetadata(),namespaceId=ns,caseId=case,status=CaseStatus.PENDING)),"ProductEngineer");tool.execute(FactoryStartWorkflowTool.Input("wf-1","bmad-story","Story"),context).success shouldBe true;path shouldBe "/api/factory/workflows/wf-1/start";val sent=mapper.readTree(body);sent.path("workflow").fieldNames().asSequence().toSet() shouldBe setOf("workflowId","workflowType","title");sent.path("execution").path("namespaceId").asText() shouldBe ns.toString();sent.path("execution").path("runtimeId").asText() shouldBe "runtime-configured";sent.path("execution").path("caseId").asText() shouldBe case.toString();sent.path("execution").path("agentId").asText() shouldBe "ProductEngineer";sent.path("execution").path("actorId").asText() shouldBe "actor-external"
  } finally { server.stop(0) }
 }
 "maps created idempotent errors and malformed responses" { val tool=FactoryStartWorkflowTool("http://localhost",OkHttpClient(),mapper,"runtime");tool.parseResponse(201,"""{"data":{"workflowId":"wf","revision":1,"created":true,"idempotent":false,"governanceMode":"governed","definitionVersion":"1.0.0","definitionHash":"h","projection":{}}}""").metadata["created"] shouldBe true;tool.parseResponse(200,"""{"data":{"workflowId":"wf","revision":1,"created":false,"idempotent":true,"governanceMode":"governed","definitionVersion":"1.0.0","definitionHash":"h","projection":{}}}""").metadata["idempotent"] shouldBe true;tool.parseResponse(409,"""{"error":{"code":"WORKFLOW_REMOVED","message":"removed"}}""").errorType shouldBe "WORKFLOW_REMOVED";tool.parseResponse(200,"bad").errorType shouldBe "MALFORMED_FACTORY_RESPONSE" }
 "capability filtering is exact" { val grant=FactoryToolGrantService(FactoryToolPlugin(mapper,"http://localhost","runtime"));val context=ToolContext(UUID.randomUUID(),null,null,emptyList());grant.grantTools(context,mapOf("FACTORY" to listOf("start_workflow"))).map{it.name} shouldBe listOf("FACTORY__start_workflow");grant.grantTools(context,mapOf("FACTORY" to listOf("start_workflow_extra"))).isEmpty() shouldBe true }
})
