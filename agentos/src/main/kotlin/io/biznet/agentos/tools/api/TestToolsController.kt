package io.biznet.agentos.tools.api

import io.biznet.agentos.tools.service.ToolRegistry
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.tool.method.MethodToolCallback
import org.springframework.ai.tool.support.ToolDefinitions
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController


@RestController()
@RequestMapping("/api/tools")
class TestToolsController(val chatClients: List<ChatClient>, val toolRegistry: ToolRegistry) {

    @GetMapping("/test")
    fun test(@RequestParam(required = false) promptContent: String?): String? {
        val chatClient = chatClients.first()
        val tools = toolRegistry.findTools()
        val prompt = chatClient.prompt(promptContent ?: "quelles sont les outils dont tu disposes ?").toolCallbacks(
            tools.map {
                val method = it::class.java.methods.first { method -> method.name == "execute" }
                MethodToolCallback.builder()
                    .toolDefinition(
                        ToolDefinitions.builder(method)
                            .description(it.description)
                            .name(it.name)
                            .build()
                    )
                    .toolMethod(method)
                    .toolObject(it)
                    .build()
            }
        )
        return """<pre>${prompt.call().chatClientResponse().chatResponse?.result?.output?.text}</pre>"""
    }
}