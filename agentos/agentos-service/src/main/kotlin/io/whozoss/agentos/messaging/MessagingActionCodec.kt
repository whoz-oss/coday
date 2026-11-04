package io.whozoss.agentos.messaging

import java.util.UUID

/**
 * Encodes the [MessagingAction]s that come back to AgentOS into the short payload a button carries, and back.
 *
 * Telegram caps that payload at 64 bytes: the longest form, `q:<uuid>:<index>`, takes 41. A payload is never
 * trusted: whatever it decodes to is checked again against the clicking user's rights when it is handled.
 */
object MessagingActionCodec {
    private const val ANSWER = "q"
    private const val OPEN_CASE = "c"
    private const val SEPARATOR = ":"

    /** The payload of [action]. [MessagingAction.OpenUrl] has none: the platform opens it itself. */
    fun encode(action: MessagingAction): String? =
        when (action) {
            is MessagingAction.AnswerQuestion -> join(ANSWER, action.questionId, action.optionIndex)
            is MessagingAction.OpenCase -> join(OPEN_CASE, action.caseId)
            is MessagingAction.OpenUrl -> null
        }

    /** The action [payload] encodes, or null when it encodes none. */
    fun decode(payload: String?): MessagingAction? =
        payload?.split(SEPARATOR)?.let { parts ->
            runCatching {
                when {
                    parts.size == 3 && parts[0] == ANSWER ->
                        MessagingAction.AnswerQuestion(questionId = UUID.fromString(parts[1]), optionIndex = parts[2].toInt())
                    parts.size == 2 && parts[0] == OPEN_CASE -> MessagingAction.OpenCase(UUID.fromString(parts[1]))
                    else -> null
                }
            }.getOrNull()
        }

    private fun join(vararg parts: Any): String = parts.joinToString(SEPARATOR)
}
