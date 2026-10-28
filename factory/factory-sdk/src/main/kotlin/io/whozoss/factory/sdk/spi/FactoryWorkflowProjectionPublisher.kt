package io.whozoss.factory.sdk.spi

import org.pf4j.ExtensionPoint

/**
 * Outcome of a plugin projection contribution attempt.
 */
enum class FactoryProjectionPublishResult {
    /** The projection was accepted and published by the plugin. */
    PUBLISHED,

    /** The plugin handled the call but deliberately did nothing. */
    SKIPPED,

    /** The plugin does not implement projection publishing. */
    UNSUPPORTED,
}

/**
 * Extension point allowing a plugin to project workflow state to an external
 * system.
 *
 * The default is a safe [FactoryProjectionPublishResult.UNSUPPORTED]: a plugin
 * that only contributes routes is never required to implement projections.
 */
interface FactoryWorkflowProjectionPublisher : ExtensionPoint {
    fun publishProjection(
        namespaceId: String,
        projection: Map<String, Any?>,
    ): FactoryProjectionPublishResult = FactoryProjectionPublishResult.UNSUPPORTED
}
