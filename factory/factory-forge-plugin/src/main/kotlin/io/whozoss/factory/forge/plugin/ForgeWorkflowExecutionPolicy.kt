package io.whozoss.factory.forge.plugin

import io.whozoss.factory.sdk.spi.FactoryWorkflowExecutionPolicy
import org.pf4j.Extension

/**
 * Declares `forge` as the logical execution-plugin id of the Forge plugin, so a
 * workflow definition whose top-level `execution.plugin` is `forge` can be
 * resolved by the host before it starts a run.
 */
@Extension
class ForgeWorkflowExecutionPolicy : FactoryWorkflowExecutionPolicy {
    override fun getPluginId(): String = "forge"
}
