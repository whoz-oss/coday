package io.whozoss.agentos.sdk.auth

enum class NamespaceRole(val level: Int) {
    VIEWER(1),
    MEMBER(2),
    ADMIN(3),
    OWNER(4),
    ;

    fun satisfies(required: NamespaceRole): Boolean = this.level >= required.level
}
