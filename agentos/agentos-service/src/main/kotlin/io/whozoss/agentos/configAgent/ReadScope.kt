package io.whozoss.agentos.configAgent

/** Which layers a `read` list covers. */
enum class ReadScope(
    val includesPlatform: Boolean,
    val includesNamespace: Boolean,
    val includesMine: Boolean,
) {
    ALL(true, true, true),
    PLATFORM(true, false, false),
    NAMESPACE(false, true, false),
    MINE(false, false, true),
}
