package io.biznet.agentos.provider

import io.biznet.agentos.chatclient.ChatClientProvider
import io.biznet.agentos.orchestration.Orchestrator
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.Map

@RestController
class AiController(
    private val chatClientProvider: ChatClientProvider,
    private val orchestrator: Orchestrator
) {


    @PostMapping("/ai/generate")
    fun generate(
        @RequestBody query: Query,
    ): MutableMap<String?, String?> {
        val response = chatClientProvider.getChatClient(query.modelConfig)!!
                .prompt()
                .user(query.message)
                .call()
                .content()
        return Map.of<String?, String?>("generation", response)
    }

}


data class Query(val message: String, val modelConfig: ModelConfig)