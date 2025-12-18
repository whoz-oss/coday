package io.biznet.agentos.tools.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.jsonSchema.JsonSchemaGenerator
import io.biznet.agentos.tools.service.AgentTools
import io.biznet.agentos.tools.service.DateTimeTools
import io.biznet.agentos.tools.service.ToolRegistry
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.support.ToolCallbacks
import org.springframework.ai.tool.execution.DefaultToolCallResultConverter
import org.springframework.ai.tool.execution.ToolCallResultConverter
import org.springframework.ai.tool.method.MethodToolCallback
import org.springframework.ai.tool.support.ToolDefinitions
import org.springframework.util.ReflectionUtils
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.lang.reflect.Type


@RestController()
@RequestMapping("/api/tools")
class TestToolsController(val chatClients: List<ChatClient>, val toolRegistry: ToolRegistry, val objectMapper: ObjectMapper) {

    @GetMapping("/test")
    fun test(@RequestParam(required = false) promptContent: String?): String? {
        val chatClient = chatClients.first()
        val tools = toolRegistry.findTools()
        val toolCallbacks = (tools.map {
            val method =if (it.paramType == null) {
                ReflectionUtils.findMethod(it::class.java, "execute")!!
            } else {
                ReflectionUtils.findMethod(it::class.java, "execute", it.paramType)!!
            }
            val schemaGenerator: JsonSchemaGenerator  = JsonSchemaGenerator(objectMapper)
            val inputSchema = schemaGenerator.generateSchema(method.parameterTypes[0])
            val jsonInputSchema = objectMapper.writeValueAsString(inputSchema)
            MethodToolCallback.builder()
                .toolDefinition(
                    ToolDefinitions.builder(method)
                        .description(it.description)
                        .name(it.name)
                        .inputSchema(it.inputSchema)
                        .build()
                )
                .toolMethod(method)
                .toolObject(it)
                .toolCallResultConverter(object : ToolCallResultConverter {
                    val defaultConverter = DefaultToolCallResultConverter()
                    override fun convert(result: Any?, returnType: Type?): String {
                        println("result: $result")
                        println("returnType: $returnType")
                        return defaultConverter.convert(result, returnType)
                    }
                })
                .build()
        }) + ToolCallbacks.from(DateTimeTools(), AgentTools())
        val prompt = chatClient.prompt(promptContent ?: "quelles sont les outils dont tu disposes ?")
            .toolCallbacks(toolCallbacks)


        return """<pre>${prompt.call().chatClientResponse().chatResponse?.result?.output?.text}</pre>"""
    }
}