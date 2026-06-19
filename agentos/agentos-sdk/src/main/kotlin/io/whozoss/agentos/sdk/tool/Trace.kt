package io.whozoss.agentos.sdk.tool

/**
 * Marks a method as a tracing boundary.
 *
 * Applied to [StandardTool.executeWithJson] to signal that this entry point
 * is the intended span root for tool-call tracing. Instrumentation infrastructure
 * (e.g. Micrometer Tracing in agentos-service) can use this annotation as a
 * marker when creating spans programmatically.
 *
 * This is a compile-time marker only ([AnnotationRetention.SOURCE]) — it carries
 * no runtime overhead and does not require any tracing dependency in the SDK.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class Trace
