package io.whozoss.agentos.agentConfig

import io.whozoss.agentos.exception.BadRequestException

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
 * The refusal itself is reused rather than reimplemented: the config agent's plan stage asks for
 * the offending entries with [idSelectors] and [newIdSelectors] and reports [correction] as a
 * planning error, so a proposal carrying an id is refused before it is frozen and shown to anyone.
 * Without that, the same id is only caught here, once the user has clicked Apply, and the failure
 * stops the rest of their plan.
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
     * For a create, where there is no stored list to compare against and so nothing an id can be
     * inherited from. Blank entries are not this object's business:
     * [io.whozoss.agentos.sdk.util.StringUtils.nullOrNotBlankItems] already drops them.
     */
    fun requireNameSelectors(subAgents: List<String>?) = refuse(idSelectors(subAgents))

    /**
     * Throws 400 when [subAgents] introduces an entity id that [stored] did not already hold.
     *
     * An update replaces the whole resource, so every caller sends the selectors back as they were
     * read: the UI from its own rows, and the config agent because
     * [io.whozoss.agentos.configAgent.ApiBeanConfigWriter] reads the DTO, applies the one field
     * its plan targets and PUTs the result. Refusing the whole list would therefore make an agent
     * whose stored selectors already hold an id — the very state this guard exists to stop
     * producing — impossible to edit at all, including for a change that never touches delegation,
     * and would abort the rest of a configuration plan with it.
     *
     * So the guard refuses what this write *adds*, and leaves what it merely carries over. A stored
     * id stays visible instead: the form keeps it as a flagged row the user can delete, and the run
     * warns about it by name.
     */
    fun requireNoNewIdSelectors(
        subAgents: List<String>?,
        stored: List<String>?,
    ) = refuse(newIdSelectors(subAgents, stored))

    /** The entries of [subAgents] that are entity ids, as they were written. */
    fun idSelectors(subAgents: List<String>?): List<String> = entityIds(subAgents)

    /** The entity ids [subAgents] introduces: those [stored] does not already carry. */
    fun newIdSelectors(
        subAgents: List<String>?,
        stored: List<String>?,
    ): List<String> {
        val carriedOver = stored.orEmpty().map { it.trim().lowercase() }.toSet()
        return entityIds(subAgents).filterNot { it.trim().lowercase() in carriedOver }
    }

    /**
     * The correction for [ids], worded once so the write path and the plan stage say the same thing.
     *
     * The rule comes before the offending values: the plan stage prefixes it with the operation's
     * ref, and [io.whozoss.agentos.configAgent.ConfigChangeAnswer] truncates what it replays to the
     * agent, so a message that led with the ids would lose the instruction that makes it useful.
     */
    fun correction(ids: List<String>): String =
        "subAgents takes glob patterns matched against the name of each agent, never its id: " +
            "an id matches no agent and leaves this agent unable to delegate. " +
            "Pass the name of each sub-agent, or a pattern over names such as '*' for all of them. " +
            "Refused ${ids.size} entity id(s): ${ids.take(ID_SAMPLE).joinToString(", ") { "'$it'" }}."

    private fun entityIds(subAgents: List<String>?): List<String> =
        subAgents?.filter { ENTITY_ID.matches(it.trim()) }.orEmpty()

    private fun refuse(ids: List<String>) {
        if (ids.isEmpty()) return
        // The project's own 400, like the ResourceNotFoundException thrown beside this guard in the
        // controller. `server.error.include-message: always` puts its message in the body, so the
        // UI and a configuring agent read the correction verbatim either way.
        throw BadRequestException(correction(ids))
    }

    /** Enough of the offending entries to recognise the call, not the whole payload back. */
    private const val ID_SAMPLE = 10
}
