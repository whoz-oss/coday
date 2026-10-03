package io.whozoss.factory.agentattempt.domain

import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant

/**
 * The structured human question a worker asks while executing a step attempt
 * (Phase 4 ask-step-question).
 *
 * A [StepQuestion] is the validated payload of the dedicated
 * `FACTORY__ask_step_question` worker tool, submitted to
 * `POST /api/factory/agent-step-questions`. It is deliberately NOT a member of
 * [AgentStepResultStatus]: asking a question is not a terminal business verdict
 * (`PASS`/`FAIL`), so it must not consume the single-use result capability nor
 * terminalize the attempt. The dedicated channel keeps the result contract
 * intact and lets the question capability be granted/denied independently.
 */
enum class StepQuestionType(val wire: String) {
    FREE_TEXT("FREE_TEXT"),
    SINGLE_CHOICE("SINGLE_CHOICE"),
    OPEN_CHOICE("OPEN_CHOICE"),
    ;

    companion object {
        fun fromWire(value: String?): StepQuestionType? = entries.firstOrNull { it.wire == value }
    }
}

/** Bounded limits of the step-question schema, enforced by [StepQuestionValidation]. */
object StepQuestionLimits {
    const val PROMPT = 2000
    const val OPTIONS = 20
    const val OPTION_LENGTH = 500
    const val RECIPIENT_ROLE = 128
    const val CONTEXT_HASH = 128

    /** Bound of the human answer text. */
    const val ANSWER = 2000

    /**
     * Byte bound of the resumption context JSON persisted on the successor
     * attempt `N+1` (question + answer + audited human identity + predecessor
     * links). The options list is dropped first when the bound would be
     * exceeded — it stays durably available on the interaction payload.
     */
    const val RESUMPTION_CONTEXT_BYTES = 8192
}

/**
 * One validated step question. [contextHash] is the worker-computed canonical
 * hash of the question content: it anchors the deterministic interaction id, so
 * a retried submission of the same question collapses onto the same durable
 * `HumanInteraction` (idempotent re-ask).
 */
data class StepQuestion(
    val prompt: String,
    val type: StepQuestionType,
    val options: List<String> = emptyList(),
    val recipientRole: String? = null,
    val contextHash: String,
    val expiresAt: Instant? = null,
)

/**
 * Pure validation of the step-question schema.
 *
 * Mirrors the defensive style of [AgentStepResultValidation]: any unknown
 * field, a missing required field or a value exceeding one of
 * [StepQuestionLimits] rejects the question. The validator works on the raw
 * Jackson tree; [parse] maps an already validated node onto the typed model.
 */
object StepQuestionValidation {

    private val QUESTION_FIELDS = setOf("prompt", "type", "options", "recipientRole", "contextHash", "expiresAt")

    /** True when [value] is a structurally valid step question. */
    fun validate(value: JsonNode?): Boolean {
        if (value == null || !value.isObject) return false
        if (value.fieldNames().asSequence().any { it !in QUESTION_FIELDS }) return false

        val prompt = value.get("prompt")?.takeIf { it.isTextual }?.asText() ?: return false
        if (prompt.isEmpty() || prompt.length > StepQuestionLimits.PROMPT) return false

        val type = StepQuestionType.fromWire(value.get("type")?.takeIf { it.isTextual }?.asText()) ?: return false

        val options = value.get("options")
        if (options != null) {
            if (!options.isArray || options.size() > StepQuestionLimits.OPTIONS) return false
            if (options.any { !it.isTextual || it.asText().isEmpty() || it.asText().length > StepQuestionLimits.OPTION_LENGTH }) {
                return false
            }
        }
        if (type == StepQuestionType.SINGLE_CHOICE && (options == null || options.size() == 0)) return false

        val recipientRole = value.get("recipientRole")
        if (recipientRole != null && (!recipientRole.isTextual || recipientRole.asText().isEmpty() ||
                recipientRole.asText().length > StepQuestionLimits.RECIPIENT_ROLE)
        ) {
            return false
        }

        val contextHash = value.get("contextHash")?.takeIf { it.isTextual }?.asText() ?: return false
        if (contextHash.isEmpty() || contextHash.length > StepQuestionLimits.CONTEXT_HASH) return false

        val expiresAt = value.get("expiresAt")
        if (expiresAt != null) {
            if (!expiresAt.isTextual) return false
            if (runCatching { Instant.parse(expiresAt.asText()) }.isFailure) return false
        }

        return true
    }

    /**
     * Maps an already validated question node onto the typed model.
     *
     * The caller must have checked [validate] first; malformed nodes are
     * rejected with an [IllegalArgumentException].
     */
    fun parse(value: JsonNode): StepQuestion {
        require(validate(value)) { "The structured step question is invalid" }
        return StepQuestion(
            prompt = value.get("prompt").asText(),
            type = StepQuestionType.fromWire(value.get("type").asText())!!,
            options = value.get("options")?.map { it.asText() } ?: emptyList(),
            recipientRole = value.get("recipientRole")?.asText(),
            contextHash = value.get("contextHash").asText(),
            expiresAt = value.get("expiresAt")?.asText()?.let(Instant::parse),
        )
    }
}
