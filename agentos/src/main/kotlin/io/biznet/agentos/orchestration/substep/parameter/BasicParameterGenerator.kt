package io.biznet.agentos.orchestration.substep.parameter

import io.biznet.agentos.thought.Intention
import io.biznet.agentos.thought.ParameterGeneration
import io.biznet.agentos.thought.Channel
import io.biznet.agentos.thought.ToolChoiceGeneration
import io.biznet.agentos.tool.Functionality
import io.biznet.agentos.tool.Parameter
import org.springframework.ai.chat.client.ChatClient
import org.springframework.stereotype.Service

@Service
class BasicParameterGenerator<T : Parameter>(
  val parameterGenerationService: ParameterGenerationService,
) : ParameterGenerator<T> {
  override fun generate(
      channel: Channel,
      intention: Intention,
      toolGeneration: ToolChoiceGeneration,
      functionality: Functionality,
      clazz: Class<T>,
      chatClient: ChatClient,
      additionalContext: String?,
  ): ParameterGeneration {
    return parameterGenerationService.generate(
        channel = channel,
        intention = intention,
        toolGeneration = toolGeneration,
        functionality = functionality,
        clazz = clazz,
        chatClient = chatClient,
        additionalContext = additionalContext,
      )
  }
}
