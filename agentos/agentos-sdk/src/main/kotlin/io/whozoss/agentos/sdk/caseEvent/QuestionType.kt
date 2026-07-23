package io.whozoss.agentos.sdk.caseEvent

import com.fasterxml.jackson.annotation.JsonValue

enum class QuestionType(
    @JsonValue val value: String,
) {
    FREE_TEXT("FREE_TEXT"),
    SINGLE_CHOICE("SINGLE_CHOICE"),
    /**
     * The user may either pick one of the provided [QuestionEvent.options] or enter free text.
     * Requires [QuestionEvent.options] to be non-null and non-empty.
     */
    OPEN_CHOICE("OPEN_CHOICE"),
    OAUTH_AUTHORIZE("OAUTH_AUTHORIZE"),
}
