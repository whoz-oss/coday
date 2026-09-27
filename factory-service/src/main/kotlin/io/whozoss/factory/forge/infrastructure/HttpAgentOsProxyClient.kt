package io.whozoss.factory.forge.infrastructure

import io.whozoss.factory.forge.port.AgentOsProxyClient
import io.whozoss.factory.forge.port.AgentOsUnavailableException
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpStatusCode
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException

/**
 * HTTP adapter for the AgentOS proxy.
 *
 * Port of `factory/dashboard/agentos-proxy.mjs`. Propagates the trusted
 * `X-External-User-Id` header; an AgentOS 404 resolves to `null`, any other
 * failure to [AgentOsUnavailableException] (502).
 */
class HttpAgentOsProxyClient(
    builder: RestClient.Builder,
    baseUrl: String,
) : AgentOsProxyClient {

    private val client: RestClient = builder.baseUrl(baseUrl).build()

    @Suppress("UNCHECKED_CAST")
    private fun <T> relay(path: String, namespaceUserId: String?, type: ParameterizedTypeReference<T>): T {
        try {
            val spec = client.get().uri(path)
            if (!namespaceUserId.isNullOrBlank()) spec.header("X-External-User-Id", namespaceUserId)
            return spec.retrieve().body(type) as T
        } catch (error: RestClientResponseException) {
            if (error.statusCode == HttpStatusCode.valueOf(404)) throw AgentOsNotFound()
            throw AgentOsUnavailableException("AgentOS ${error.statusCode.value()}", error)
        } catch (error: AgentOsNotFound) {
            throw error
        } catch (error: Exception) {
            throw AgentOsUnavailableException(error.message ?: error.toString(), error)
        }
    }

    private class AgentOsNotFound : RuntimeException()

    override fun fetchAgents(namespaceId: String, externalUserId: String?): Any? =
        relay("/api/agent-configs/by-parentId/$namespaceId", externalUserId, object : ParameterizedTypeReference<Any>() {})

    override fun fetchNamespace(namespaceId: String, externalUserId: String?): Map<String, Any?>? =
        try {
            relay(
                "/api/namespaces/$namespaceId",
                externalUserId,
                object : ParameterizedTypeReference<Map<String, Any?>>() {},
            )
        } catch (_: AgentOsNotFound) {
            null
        }

    override fun fetchCaseEvents(caseId: String, externalUserId: String?): Any? =
        relay("/api/case-events/by-parentId/$caseId", externalUserId, object : ParameterizedTypeReference<Any>() {})

    override fun resolveRepoRoot(namespaceId: String, externalUserId: String?): String? {
        val namespace = fetchNamespace(namespaceId, externalUserId) ?: return null
        val configPath = namespace["configPath"] as? String
        if (configPath.isNullOrBlank()) return null
        val trimmed = configPath.replace(Regex("/+$"), "")
        val parent = java.nio.file.Path.of(trimmed).parent ?: return null
        return parent.toString()
    }

    override fun resolveRunStoreRoot(namespaceId: String, externalUserId: String?): String? {
        val repoRoot = resolveRepoRoot(namespaceId, externalUserId) ?: return null
        return java.nio.file.Path.of(repoRoot, "forge", "factory-runs").toString()
    }
}
