package io.whozoss.factory.sdk.spi

import org.pf4j.ExtensionPoint

/**
 * PF4J extension point allowing a plugin to contribute short workflow DAG
 * definitions (fragments) in JSON format to the Factory catalog.
 */
interface FactoryFlowCatalogContributor : ExtensionPoint {
    fun flows(): List<String> = emptyList()
}
