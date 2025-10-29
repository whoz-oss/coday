package io.biznet.agentos.provider

import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.metadata.DefaultChatGenerationMetadata
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.api.OpenAiApi
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.Map

@RestController
class AiController(
    @param:Qualifier("openAiChatClient") private val openAiChatClient: ChatClient,
    @param:Qualifier("anthropicChatClient") private val anthropicChatClient: ChatClient,
    @param:Qualifier("vllmChatClient") private val vllmChatClient: ChatClient,
) {
    @GetMapping("/ai/generate")
    fun generate(
        @RequestParam(defaultValue = "Tell me a joke") message: String,
        @RequestParam(defaultValue = "openai") model: String?,
    ): MutableMap<String?, String?> {
        val response =
            chooseClient(model)
                .prompt()
                .user(message)
                .call()
                .content()
        return Map.of<String?, String?>("generation", response)
    }

    @GetMapping("/ai/logprobs")
    fun metaData(
        @RequestParam(defaultValue = "Tell me a joke") message: String,
        @RequestParam(defaultValue = "openai") model: String?,
    ): String {
        val test =
            OpenAiChatOptions
                .builder()
                .logprobs(true)
                .topLogprobs(3)
                .build()

        val response =
            chooseClient(model)
                .prompt(Prompt(message, test))
                .call()

        val t = (response.chatResponse()!!.result.metadata as DefaultChatGenerationMetadata).get<OpenAiApi.LogProbs>("logprobs")

        return t.content.joinToString("\n") { it.token + "->" + it.topLogprobs().joinToString("|") { it.token + " (${it.logprob})" } }
    }

    @GetMapping("/ai/choice")
    fun generateEnumChoice(
        @RequestParam(defaultValue = "Tell me a joke") message: String,
        @RequestParam(defaultValue = "openai") model: String?,
    ): MyChoice {
        val t =
            this
                .chooseClient(model)
                .prompt()
                .user(message)
                .call()
        return t.entity(MyChoice::class.java)!!
    }

    fun chooseClient(type: String?): ChatClient =
        if ("anthropic".equals(type, ignoreCase = true)) {
            this.anthropicChatClient
        } else if ("vllm".equals(type, ignoreCase = true)) {
            this.vllmChatClient
        } else {
            this.openAiChatClient
        }
}

enum class ColorChoice {
    Red,
    Green,
    Blue,
}

data class MyChoice(
    val chosenColor: ColorChoice?,
)
