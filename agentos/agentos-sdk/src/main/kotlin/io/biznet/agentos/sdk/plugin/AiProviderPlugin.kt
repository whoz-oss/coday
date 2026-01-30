package io.biznet.agentos.sdk.plugin

import io.biznet.agentos.sdk.model.AiProvider
import org.pf4j.ExtensionPoint

interface AiProviderPlugin : ExtensionPoint {
    fun getPluginId(): String

    fun getAiProviders(): List<AiProvider>

    fun getDescription(): String = ""

    fun initialize() {}

    fun destroy() {}
}
