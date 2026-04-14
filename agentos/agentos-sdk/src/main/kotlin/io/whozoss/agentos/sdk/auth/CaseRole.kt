package io.whozoss.agentos.sdk.auth

enum class CaseRole(val level: Int) {
    OBSERVER(1),
    PARTICIPANT(2),
    OWNER(3),
    ;

    fun satisfies(required: CaseRole): Boolean = this.level >= required.level
}
