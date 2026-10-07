package io.whozoss.agentos.git.core

/** The only push option a managed push accepts: replace a branch only if it is still where it was seen. */
object GitPushLease {
    const val OPTION: String = "--force-with-lease="

    /** Replace [branch] on the remote only if it is still at [expected], or absent when [expected] is empty. */
    fun of(branch: String, expected: String): String = "$OPTION${GitRefs.head(branch)}:$expected"
}
