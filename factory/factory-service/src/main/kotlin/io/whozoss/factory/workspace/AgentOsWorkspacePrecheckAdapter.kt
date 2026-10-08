package io.whozoss.factory.workspace

import io.whozoss.factory.proxy.AgentOsProxyClient
import mu.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Read-only [WorkspacePrecheckPort] backed by the AgentOS HTTP proxy.
 *
 * It consumes only existing AgentOS surfaces:
 *  - namespace accessibility -> `GET /api/namespaces/{namespaceId}` (a 404 maps
 *    to "inaccessible"; any transport error is treated as inaccessible so the
 *    pre-check fails closed with an actionable error);
 *  - Git association/checkout -> `GET /api/namespaces/{namespaceId}/git`
 *    (`NamespaceGitResource`); a 404 means the GIT plugin/association surface is
 *    unavailable;
 *  - available agents -> `GET /api/agent-configs/by-parentId/{namespaceId}`.
 *
 * It NEVER writes. Genuinely unavailable data is surfaced as `null` so the
 * caller records it as unverified rather than assuming it satisfied.
 */
@Component
class AgentOsWorkspacePrecheckAdapter(
    private val proxy: AgentOsProxyClient,
) : WorkspacePrecheckPort {

    private val logger = KotlinLogging.logger {}

    override fun isNamespaceAccessible(namespaceId: String, callerExternalUserId: String?): Boolean =
        runCatching { proxy.fetchNamespace(namespaceId, callerExternalUserId) != null }
            .onFailure { logger.debug(it) { "Namespace '$namespaceId' could not be read; treating it as inaccessible" } }
            .getOrDefault(false)

    override fun namespaceGitState(namespaceId: String, callerExternalUserId: String?): NamespaceGitState? {
        val raw = runCatching { proxy.fetchNamespaceGit(namespaceId, callerExternalUserId) }
            .onFailure { logger.debug(it) { "Namespace Git state for '$namespaceId' is unavailable" } }
            .getOrNull()
            ?: return null
        return NamespaceGitState(
            gitAvailable = true,
            associated = raw["associated"] as? Boolean ?: false,
            checkoutStatus = raw["checkoutStatus"] as? String,
            failureReason = raw["checkoutFailureReason"] as? String,
        )
    }

    override fun availableAgents(namespaceId: String, callerExternalUserId: String?): List<String>? {
        val raw = runCatching { proxy.fetchAgents(namespaceId, callerExternalUserId) }
            .onFailure { logger.debug(it) { "Agent list for namespace '$namespaceId' is unavailable" } }
            .getOrNull()
            ?: return null
        val items = raw as? List<*> ?: return null
        return items.mapNotNull { element ->
            val map = element as? Map<*, *> ?: return@mapNotNull null
            if (map["enabled"] as? Boolean == false) return@mapNotNull null
            (map["name"] as? String)?.takeIf { it.isNotBlank() }
        }
    }
}
