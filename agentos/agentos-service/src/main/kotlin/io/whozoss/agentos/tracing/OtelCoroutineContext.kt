package io.whozoss.agentos.tracing

import io.opentelemetry.context.Context
import io.opentelemetry.context.Scope
import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Propagates OpenTelemetry context across coroutine suspension points.
 *
 * Kotlin coroutines may resume on a different thread after each suspension point.
 * OpenTelemetry stores the active span in a ThreadLocal, which is lost on thread switches.
 * This [ThreadContextElement] captures the OTel context when the coroutine suspends and
 * restores it on resume, ensuring spans remain correctly parented across the entire
 * coroutine execution.
 *
 * Usage: include in the [CoroutineContext] when launching coroutines that should
 * inherit the current trace:
 * ```
 * scope.launch(Dispatchers.IO + OtelCoroutineContext()) {
 *     // OTel context is preserved across suspension points here
 * }
 * ```
 */
class OtelCoroutineContext(
    private val otelContext: Context = Context.current(),
) : ThreadContextElement<Scope> {
    companion object Key : CoroutineContext.Key<OtelCoroutineContext>

    override val key: CoroutineContext.Key<OtelCoroutineContext> = Key

    override fun updateThreadContext(context: CoroutineContext): Scope = otelContext.makeCurrent()

    override fun restoreThreadContext(
        context: CoroutineContext,
        oldState: Scope,
    ) = oldState.close()
}
