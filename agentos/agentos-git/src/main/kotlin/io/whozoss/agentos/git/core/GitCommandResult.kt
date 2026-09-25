package io.whozoss.agentos.git.core

import java.time.Duration

/**
 * Outcome of a [GitInvocation].
 *
 * A non-zero exit code is [Completed], not a failure of the runner: callers decide whether it is
 * expected (`git rev-parse --verify` on a missing ref) or an error worth surfacing.
 */
sealed interface GitCommandResult {
    /** The process ran to completion; [exitCode] may still be non-zero. */
    data class Completed(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val truncated: Boolean = false,
    ) : GitCommandResult {
        val successful: Boolean get() = exitCode == 0
    }

    /** The process exceeded its timeout and was forcibly terminated. */
    data class TimedOut(
        val timeout: Duration,
    ) : GitCommandResult

    /** The process could not be started, or its streams could not be read. */
    data class Failed(
        val message: String,
    ) : GitCommandResult
}

/**
 * Raised by [GitCommandRunner.runOrThrow] when a git command does not complete successfully.
 *
 * The message carries git's stderr, which is operator-facing diagnostic text. Secrets never
 * reach it: they are passed through the environment, so git echoes at most a URL without
 * user info.
 */
class GitCommandException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
