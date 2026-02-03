package io.whozoss.agentos.sdk.aiprovider

import org.pf4j.ExtensionPoint

interface AiProviderPlugin: ExtensionPoint {
    fun getPluginId(): String

    fun getAiProviders(): List<AiProvider>

    fun getDescription(): String = ""

    fun initialize() {}

    fun destroy() {}
}