package io.whozoss.agentos.sdk.caseEvent

import com.fasterxml.jackson.annotation.JsonValue

enum class QuestionType(
    @JsonValue val value: String,
) {
    FREE_TEXT("FREE_TEXT"),
    SINGLE_CHOICE("SINGLE_CHOICE"),
    OAUTH_AUTHORIZE("OAUTH_AUTHORIZE"),
}
