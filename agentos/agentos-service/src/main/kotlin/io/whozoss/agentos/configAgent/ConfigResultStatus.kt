package io.whozoss.agentos.configAgent

/** Outcome of one operation in an apply report. [isSuccess] lets the apply go on to the next one. */
enum class ConfigResultStatus(
    val isSuccess: Boolean,
) {
    APPLIED(true),
    ALREADY_APPLIED(true),
    DENIED(false),
    NOT_FOUND(false),
    CONFLICT(false),
    INVALID(false),
    FAILED(false),
    NOT_RUN(false),
}
