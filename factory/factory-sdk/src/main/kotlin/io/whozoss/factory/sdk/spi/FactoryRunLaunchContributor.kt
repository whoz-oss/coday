package io.whozoss.factory.sdk.spi

import org.pf4j.ExtensionPoint

/**
 * Extension point notified when the host launches a run.
 *
 * The default is a no-op so a plugin only overrides [onRunLaunch] when it needs
 * to react to a launch (e.g. to push a notification or seed an external store).
 */
interface FactoryRunLaunchContributor : ExtensionPoint {
    fun onRunLaunch(
        runId: String,
        payload: Map<String, Any?>,
    ) {
        // Safe default: no-op.
    }
}
