package io.whozoss.factory.forge.plugin

import io.whozoss.factory.forge.domain.ForgeWorkflowAdapter
import io.whozoss.factory.sdk.spi.FactoryProjectionPublishResult
import io.whozoss.factory.sdk.spi.FactoryWorkflowProjectionPublisher
import org.pf4j.Extension

/**
 * PF4J extension projecting a Forge run onto the generic workflow projection.
 *
 * Delegates to [ForgeWorkflowAdapter], which validates the produced projection
 * with the host's `WorkflowProjectionValidator`. When the incoming payload is
 * not a valid Forge run, the contribution is reported as skipped rather than
 * failing the host call.
 */
@Extension
class ForgeWorkflowProjectionPublisher : FactoryWorkflowProjectionPublisher {

    override fun publishProjection(
        namespaceId: String,
        projection: Map<String, Any?>,
    ): FactoryProjectionPublishResult {
        val adapted = ForgeWorkflowAdapter.adapt(projection)
        return if (adapted["ok"] == true) {
            FactoryProjectionPublishResult.PUBLISHED
        } else {
            FactoryProjectionPublishResult.SKIPPED
        }
    }
}
