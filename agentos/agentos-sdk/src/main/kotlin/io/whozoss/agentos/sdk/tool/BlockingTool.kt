package io.whozoss.agentos.sdk.tool

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * Template of a [StandardTool] whose work blocks the calling thread: waiting on a child process, a socket, a file
 * lock...
 *
 * [execute] is `suspend`, but a blocking body called straight from it would hold a dispatcher thread for as long as it
 * waits and never see the cancellation of the run: stopping the case would leave the work going on until its natural
 * end. This class runs [executeBlocking] on [Dispatchers.IO] with [runInterruptible]: when the run is cancelled, the
 * waiting thread is interrupted and the cancellation reaches the caller, whatever the body returns once interrupted.
 *
 * A subclass therefore:
 * - implements [executeBlocking], plain blocking code that returns its [ToolExecutionResult];
 * - treats [InterruptedException] as "stop now": release what it started (kill the child process, close the
 *   connection) in a `finally` or in the handler, and let the interruption go on (re-set the interrupt flag, or
 *   rethrow). The result it returns after an interruption is discarded.
 *
 * ```kotlin
 * class SleepTool : BlockingTool<Input>() {
 *     // name, description, inputSchema, version, paramType...
 *
 *     override fun executeBlocking(input: Input?, context: ToolContext): ToolExecutionResult {
 *         val process = ProcessBuilder("sleep", "1").start()
 *         try {
 *             process.waitFor()
 *         } finally {
 *             process.destroyForcibly()
 *         }
 *         return ToolExecutionResult.success("done")
 *     }
 * }
 * ```
 *
 * Not for tools that suspend by nature (HTTP client with coroutines...): they implement [StandardTool.execute] directly.
 */
abstract class BlockingTool<T> : StandardTool<T> {
    final override suspend fun execute(
        input: T?,
        context: ToolContext,
    ): ToolExecutionResult = runInterruptible(Dispatchers.IO) { executeBlocking(input, context) }

    /**
     * The blocking body of the tool, run on [Dispatchers.IO] and interrupted when the run is cancelled.
     * Same contract as [StandardTool.execute]: errors the agent should see are returned with
     * [ToolExecutionResult.error].
     */
    protected abstract fun executeBlocking(
        input: T?,
        context: ToolContext,
    ): ToolExecutionResult
}
