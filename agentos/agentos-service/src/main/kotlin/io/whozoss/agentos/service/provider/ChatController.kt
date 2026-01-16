package io.whozoss.agentos.service.provider

import io.whozoss.agentos.service.chatclient.ChatClientProvider
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.Map

@RestController
class AiController(
    private val chatClientProvider: ChatClientProvider,
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
data class ModelConfig(val providerId: String, val apiKey: String?, val model: String?)