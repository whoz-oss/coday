package io.whozoss.agentos.plugins.http

import com.fasterxml.jackson.databind.JsonNode
import io.whozoss.agentos.plugins.http.auth.AuthHeaderSpec
import io.whozoss.agentos.plugins.http.cache.Catalogue
import io.whozoss.agentos.plugins.http.cache.CatalogueOutcome
import io.whozoss.agentos.plugins.http.config.HttpApiConfigParser
import io.whozoss.agentos.plugins.http.openapi.ToolNaming
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolPlugin
import mu.KLogging
import org.pf4j.Extension
import java.util.UUID

/**
 * Tool provider for the `HTTP_API` integration type: one [HttpApiTool] per curated operation of the
 * OpenAPI document named by the config.
 *
 * [provideTools] parses the config, obtains the catalogue from the plugin-wide cache (fetching and curating
 * the document only when needed), resolves the credential of the bound Auth Setting once for the run and
 * builds the tools. Every failure is logged, recorded in the [LastFailureRegistry] (scoped to the namespace
 * of the run) for [describeNamespace] and answered with an empty list: nothing escapes, not even a plugin
 * that is not started.
 *
 * [describeNamespace] is called for every config of a namespace with a context that carries no credential
 * provider: it answers from the cache and the registry only, without network access.
 */
@Extension
class HttpApiToolProvider @JvmOverloads constructor(
    private val services: () -> HttpApiPluginServices = { HttpApiPluginHolder.services },
) : ToolPlugin {

    override val integrationType: String = INTEGRATION_TYPE

    override val configSchema: JsonNode = HttpApiConfigSchema.schema

    override fun provideTools(config: JsonNode?, configName: String?, context: ToolContext?): List<StandardTool<*>> {
        if (config == null || config.isNull) {
            logger.warn { "$INTEGRATION_TYPE integration '$configName': no config provided, no tools exposed" }
            return emptyList()
        }
        if (configName == null) {
            logger.error { "$INTEGRATION_TYPE integration without a name: no tools exposed" }
            return emptyList()
        }
        val services = startedServices() ?: return emptyList()
        val namespaceId = context?.namespaceId
        return try {
            tools(services, config, configName, context)
        } catch (e: Exception) {
            failed(services, namespaceId, configName = configName, reason = UNEXPECTED_ERROR, cause = e)
        }
    }

    private fun tools(
        services: HttpApiPluginServices,
        config: JsonNode,
        configName: String,
        context: ToolContext?,
    ): List<StandardTool<*>> {
        val namespaceId = context?.namespaceId
        ToolNaming.configNameProblem(configName)?.let { problem ->
            return failed(services, namespaceId, configName = configName, reason = problem)
        }
        val parsed = try {
            HttpApiConfigParser.parse(config, services.urlPolicy)
        } catch (e: IllegalArgumentException) {
            return failed(services, namespaceId, configName = configName, reason = e.message ?: "invalid config")
        }
        val catalogue = when (val outcome = services.catalogueCache.getOrLoad(configName, parsed)) {
            is CatalogueOutcome.Failed ->
                return failed(services, namespaceId, configName = configName, reason = outcome.reason)
            is CatalogueOutcome.Ready -> {
                val reason = outcome.staleReason
                if (reason == null) {
                    services.failures.clear(namespaceId, configName)
                } else {
                    logger.warn {
                        "$INTEGRATION_TYPE integration '$configName': $reason, serving the previous catalogue"
                    }
                    services.failures.record(namespaceId, configName = configName, message = reason)
                }
                outcome.catalogue
            }
        }
        val provider = context?.credentialProvider
        if (provider == null && catalogue.apiKeyPlacementInEffect) {
            logger.warn {
                "$INTEGRATION_TYPE integration '$configName': an API key placement is in effect (config or document " +
                    "security scheme) but no credential provider is bound to this run (no Auth Setting, or no user " +
                    "identity): calls are sent unauthenticated"
            }
        }
        val authSpec = AuthHeaderSpec.from(provider?.invoke(), catalogue.auth, providerBound = provider != null)
        val runtime = HttpApiRuntime(
            configName = configName,
            baseUrl = catalogue.baseUrl,
            authSpec = authSpec,
            defaultHeaders = parsed.defaultHeaders,
            timeoutSeconds = parsed.timeoutSeconds,
            semaphore = services.limiters.limiterFor(namespaceId, configName, parsed.maxConcurrentCalls),
            urlPolicy = services.urlPolicy,
            client = services.client,
        )
        logger.info {
            "$INTEGRATION_TYPE integration '$configName': providing ${catalogue.operations.size} tool(s), " +
                "auth=$authSpec"
        }
        return catalogue.operations.map { HttpApiTool(it, runtime) }
    }

    /** Logs the failure (with [cause] when there is one), records [reason] for the namespace and exposes nothing. */
    private fun failed(
        services: HttpApiPluginServices,
        namespaceId: UUID?,
        configName: String,
        reason: String,
        cause: Exception? = null,
    ): List<StandardTool<*>> {
        val line = "$INTEGRATION_TYPE integration '$configName': $reason: no tools exposed"
        if (cause == null) logger.error { line } else logger.error(cause) { "$line: ${cause.message}" }
        services.failures.record(namespaceId, configName = configName, message = reason)
        return emptyList()
    }

    override suspend fun describeNamespace(config: JsonNode?, configName: String?, context: ToolContext?): String? {
        if (config == null || config.isNull || configName == null) return null
        val services = startedServices() ?: return null
        val cached = try {
            services.catalogueCache.peek(HttpApiConfigParser.parse(config, services.urlPolicy))
        } catch (e: IllegalArgumentException) {
            null
        }
        val failure = services.failures.get(context?.namespaceId, configName)
        val line = when {
            cached != null -> describe(configName, cached, failure)
            failure != null -> "Integration $configName ($INTEGRATION_TYPE): not available ($failure): no tools exposed"
            else -> null
        }
        return line?.take(MAX_DESCRIPTION_LENGTH)
    }

    /** The plugin services, or null (logged) when the plugin is not started: nothing escapes to the service. */
    private fun startedServices(): HttpApiPluginServices? =
        try {
            services()
        } catch (e: IllegalStateException) {
            logger.error(e) { "$INTEGRATION_TYPE plugin is not started: no tools exposed" }
            null
        }

    private fun describe(configName: String, catalogue: Catalogue, refreshFailure: String?): String {
        val document = listOfNotNull(catalogue.title, catalogue.version)
            .joinToString(" ")
            .ifEmpty { "OpenAPI document" }
        val mode = (if (catalogue.readOnly) "read-only" else "read/write") +
            (if (catalogue.apiKeyPlacementInEffect) ", api key" else "")
        val count = catalogue.operations.size
        val stale = refreshFailure?.let { " (document refresh failing: $it)" }.orEmpty()
        return "Integration $configName ($INTEGRATION_TYPE): $document — $count operations exposed ($mode)$stale"
    }

    companion object : KLogging() {
        const val INTEGRATION_TYPE = "HTTP_API"
        private const val MAX_DESCRIPTION_LENGTH = 300
        private const val UNEXPECTED_ERROR = "unexpected error while preparing the tools, see the service logs"
    }
}
