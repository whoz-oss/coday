package io.biznet.agentos.tool.answer
import io.biznet.agentos.tool.ExecutionResult
import io.biznet.agentos.tool.Functionality
import io.biznet.agentos.tool.Tool
import io.biznet.agentos.orchestration.substep.parameter.BasicParameterGenerator
import io.biznet.agentos.orchestration.substep.parameter.InsideParameterContext
import io.biznet.agentos.orchestration.substep.parameter.NoInsideParameter
import org.springframework.stereotype.Service

@Service
class AnswerTool(
    override val parameterGenerator: BasicParameterGenerator<AnswerParameter>,
) : Tool<AnswerParameter, NoInsideParameter> {
  override val functionality: Functionality = Functionality(
      name = "Answer",
      description = "Respond in the user's language (identified from conversation history). The response must detail the complete process undertaken and all findings used to fulfill the request.",
  )
    override val additionalContext: String? = null

  override fun execute(
      parameter: AnswerParameter,
      insideParameter: NoInsideParameter,
  ): ExecutionResult = ExecutionResult(parameter.answer, true)

  override fun getInsideParameter(insideParameterContext: InsideParameterContext): NoInsideParameter = NoInsideParameter
}
