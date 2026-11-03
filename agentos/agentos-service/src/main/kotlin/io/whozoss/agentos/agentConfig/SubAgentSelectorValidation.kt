package io.whozoss.agentos.agentConfig

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/**
 * Rejects the one value a [AgentConfig.subAgents] selector can never usefully hold: an entity id.
 *
 * The selectors are glob patterns matched against the **name** of every agent the invoking user can
 * reach ([io.whozoss.agentos.agent.AgentServiceImpl.buildDelegationTools]). An id matches no name,
 * and the failure is silent by construction: an agent whose selectors resolve to nobody gets no
 * [io.whozoss.agentos.delegation.DelegationTool] at all, so the model reports that it has no way to
 * delegate rather than that its configuration is wrong. Nothing on the write path contradicted the
 * id, nothing on the read path mentioned it, and the only trace was a server-side warning — which
 * is how a configuration session ends with delegation quietly disabled.
 *
 * Writing ids here is the expected mistake, not a far-fetched one: every other reference an agent
 * configures is an id, the surrounding payload carries `id` and `namespaceId`, and the selectors
 * are the one field keyed by name. So the write is refused with the correction in the message,
 * which is what lets a configuring agent fix its own call instead of accepting a broken config.
 *
 * Only the HTTP write path is guarded. File-based configs are parsed by
 * [FilesystemAgentConfigRepository] while a namespace loads, where refusing one field would cost
 * the whole namespace its agents; an id there still resolves to nobody and warns.
 */
internal object SubAgentSelectorValidation {
    /** Canonical dashed form, the shape [java.util.UUID.toString] produces. */
    private val ENTITY_ID = Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")

    /**
     * Throws 400 when any entry of [subAgents] is an entity id, naming the offending entries.
     *
     * Blank entries are not this object's business: [io.whozoss.agentos.sdk.util.StringUtils
     * .nullOrNotBlankItems] already drops them on both write paths.
     */
    fun requireNameSelectors(subAgents: List<String>?) {
        val ids = subAgents?.filter { ENTITY_ID.matches(it.trim()) }.orEmpty()
        if (ids.isEmpty()) return
        throw ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "subAgents holds ${ids.size} entity id(s) (${ids.take(ID_SAMPLE).joinToString(", ") { "'$it'" }}). " +
                "The field takes glob patterns matched against the name of each agent, never its id: " +
                "an id matches no agent and leaves this agent unable to delegate. " +
                "Pass the name of each sub-agent, or a pattern over names such as '*' for all of them.",
        )
    }

    /** Enough of the offending entries to recognise the call, not the whole payload back. */
    private const val ID_SAMPLE = 10
}
