package io.whozoss.agentos.git

/** The pull request of a workspace branch, as the forge reported it. */
enum class PrState {
    DRAFT,
    OPEN,
    MERGED,
    CLOSED_UNMERGED,

    /** No PR is associated with this branch, as far as the forge could tell. */
    NONE,

    /** Nothing could be observed. Never to be read as "no PR" or "closed". */
    UNKNOWN,
}
