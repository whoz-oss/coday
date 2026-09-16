package io.whozoss.agentos.factory

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolPlugin
import okhttp3.OkHttpClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/** Config-less built-in bridge; agents must explicitly grant FACTORY/publish_projection. */
@Component
class FactoryToolPlugin(
    private val objectMapper: ObjectMapper,
    @Value("\${agentos.factory.base-url:http://localhost:3141}") private val baseUrl: String,
) : ToolPlugin {
    override val integrationType = "FACTORY"
    override val configSchema: JsonNode? = null
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    override fun provideTools(config: JsonNode?, configName: String?, context: ToolContext?): List<StandardTool<*>> =
        listOf(FactoryPublishProjectionTool(baseUrl, httpClient, objectMapper))

    companion object { const val INTEGRATION_TYPE = "FACTORY" }
}
