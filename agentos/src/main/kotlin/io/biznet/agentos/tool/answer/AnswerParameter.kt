package io.biznet.agentos.tool.answer

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import io.biznet.agentos.tool.Parameter

data class AnswerParameter(
    @param:JsonPropertyDescription(
        """Language in which to respond to the user (should be the same as the user's in their conversation)""",
    )
    val answerLanguage: String,
    @param:JsonPropertyDescription(
        """The answer to provide to the user. It should be formulated with a professional tone, correctly formatted (using Markdown when necessary) and in the same language as answerLanguage.
Remember that internal analysis is not seen by the user. As such, if you refer to one of your processes, you need to explicitly state it, the user having only seen currently the conversationHistory""",
    )
    val answer: String,
) : Parameter
