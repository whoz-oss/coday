package io.whozoss.factory.sdk.spi

import org.pf4j.ExtensionPoint

/**
 * Extension point exposing the logical execution-plugin id of a plugin.
 *
 * A workflow definition may declare an optional top-level
 * `execution: { "plugin": "<id>" }`. The host resolves that id through the
 * registered [FactoryWorkflowExecutionPolicy] extensions (and, as a fallback,
 * the PF4J plugin id) before starting a run, so a definition can only be run
 * when its declared execution plugin is actually present and started.
 *
 * The logical id returned here is intentionally decoupled from the PF4J plugin
 * id: a plugin packaged as `factory-forge-plugin` may declare the execution id
 * `forge`, which is what a definition references.
 */
interface FactoryWorkflowExecutionPolicy : ExtensionPoint {
    fun getPluginId(): String
}
