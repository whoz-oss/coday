package io.whozoss.agentos.tracing

import io.micrometer.tracing.Tracer

/**
 * Executes [block] within a new tracing span, or directly when [tracer] is null.
 *
 * Creates a child span of the current active span (if any), applies the given [tags],
 * executes the block, and finishes the span. Errors are recorded on the span before
 * being rethrown.
 *
 * Passing a null [tracer] is safe: the block runs as-is with no span overhead,
 * which allows call sites to skip null checks when tracing is disabled.
 *
 * OTel context propagation across suspension points within [block] requires
 * [OtelCoroutineContext] to be present in the coroutine context.
 *
 * @param tracer the Micrometer [Tracer] instance, or null when tracing is disabled
 * @param spanName operation name for the span (e.g. "case.execution", "agent.run")
 * @param tags key-value pairs to attach to the span as low-cardinality attributes
 * @param block the work to execute within the span
 */
suspend inline fun <T> withSpan(
    tracer: Tracer?,
    spanName: String,
    tags: Map<String, String> = emptyMap(),
    crossinline block: suspend () -> T,
): T {
    if (tracer == null) return block()
    val span = tracer.nextSpan().name(spanName)
    tags.forEach { (k, v) -> span.tag(k, v) }
    span.start()
    val scope = tracer.withSpan(span)
    return try {
        block()
    } catch (e: Exception) {
        span.error(e)
        throw e
    } finally {
        scope.close()
        span.end()
    }
}

/**
 * Non-suspend variant of [withSpan] for use outside coroutine contexts.
 *
 * @param tracer the Micrometer [Tracer] instance, or null when tracing is disabled
 * @param spanName operation name for the span
 * @param tags key-value pairs to attach to the span as low-cardinality attributes
 * @param block the work to execute within the span
 */
inline fun <T> withSpanSync(
    tracer: Tracer?,
    spanName: String,
    tags: Map<String, String> = emptyMap(),
    block: () -> T,
): T {
    if (tracer == null) return block()
    val span = tracer.nextSpan().name(spanName)
    tags.forEach { (k, v) -> span.tag(k, v) }
    span.start()
    val scope = tracer.withSpan(span)
    return try {
        block()
    } catch (e: Exception) {
        span.error(e)
        throw e
    } finally {
        scope.close()
        span.end()
    }
}
