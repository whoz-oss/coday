package io.biznet.agentos.tool

import io.biznet.agentos.orchestration.substep.parameter.InsideParameter
import io.biznet.agentos.orchestration.substep.parameter.InsideParameterContext
import io.biznet.agentos.orchestration.substep.parameter.ParameterGenerator
import io.biznet.agentos.thought.Intention
import io.biznet.agentos.thought.ParameterGeneration
import io.biznet.agentos.thought.Chanel
import io.biznet.agentos.thought.ToolChoiceGeneration
import org.springframework.ai.chat.client.ChatClient
import java.lang.reflect.ParameterizedType
import kotlin.reflect.KClass

interface Tool<T : Parameter, I : InsideParameter> {
    val parameterClass: Class<T>
        get() = resolveGenericClass(Tool::class, 0)
    val functionality: Functionality
    val parameterGenerator: ParameterGenerator<T>
    val additionalContext: String?

    fun execute(
        parameter: T,
        insideParameter: I,
    ): ExecutionResult

    fun generateParameter(
        chanel: Chanel,
        intention: Intention,
        toolGeneration: ToolChoiceGeneration,
        chatClient: ChatClient,
    ): ParameterGeneration =
        parameterGenerator.generate(
            chanel = chanel,
            intention = intention,
            toolGeneration = toolGeneration,
            functionality = functionality,
            clazz = parameterClass,
            chatClient = chatClient,
            additionalContext = additionalContext,
        )

    fun getInsideParameter(insideParameterContext: InsideParameterContext): I
}

@Suppress("UNCHECKED_CAST")
fun <T> Any.resolveGenericClass(
    targetInterface: KClass<*>,
    typeArgumentIndex: Int,
): Class<T> {
    // Find the specific interface `targetInterface` from the list of implemented interfaces
    val parameterizedType =
        javaClass.genericInterfaces
            .filterIsInstance<ParameterizedType>()
            .firstOrNull { it.rawType == targetInterface.java }

    // Check if the interface was found and has the requested type argument
    if (parameterizedType != null && parameterizedType.actualTypeArguments.size > typeArgumentIndex) {
        return parameterizedType.actualTypeArguments[typeArgumentIndex] as Class<T>
    }

    // Fallback or error handling if the generic type cannot be determined
    throw IllegalStateException(
        "Cannot determine generic type at index $typeArgumentIndex for ${targetInterface.simpleName} in class ${javaClass.name}",
    )
}
