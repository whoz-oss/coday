package io.whozoss.agentos.plugins.git

import io.whozoss.agentos.git.core.GitCommandException
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult

/** Shared naming and error reporting of the GIT tools. Refusals reach the agent as tool errors. */
internal abstract class GitTool<T>(
    prefix: String,
    toolName: String,
) : StandardTool<T> {
    final override val name: String = "${prefix}__$toolName"

    final override val version: String = "1.0.0"

    final override suspend fun execute(input: T?, context: ToolContext): ToolExecutionResult =
        try {
            ToolExecutionResult.success(run(input))
        } catch (e: GitToolException) {
            ToolExecutionResult.error(output = e.message.orEmpty(), errorType = "GIT_REFUSED", errorMessage = e.message)
        } catch (e: GitCommandException) {
            val message = "Git refused the operation: ${e.message.orEmpty().take(1_000)}"
            ToolExecutionResult.error(output = message, errorType = "GIT_FAILED", errorMessage = message)
        }

    protected abstract fun run(input: T?): String

    protected fun GitWorkspace.requireBranch(action: String): String =
        branch() ?: throw GitToolException("HEAD is detached: create a branch with the create-branch tool before you $action")
}
