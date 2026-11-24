package io.whozoss.agentos.searcherAgent

import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.agentConfig.NamespaceAgentCreator
import io.whozoss.agentos.configAgent.ConfigAgentIntegration
import io.whozoss.agentos.exchange.ExchangeIntegrationTypes
import io.whozoss.agentos.sdk.entity.EntityMetadata
import mu.KLogging
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Owns what the default search sub-agent [AGENT_NAME] (`Searcher`) is, and makes sure a namespace has one:
 * [ensureIn]. Declared by [SearcherAgentProperties.enabled].
 *
 * The default agent `Coday` delegates its wide searches to it. The point is isolation of context: the search
 * (listing, reading, searching many files) happens in the sub-case of the `Searcher`, and only its compact
 * report comes back into the conversation of the agent that delegated. The same pattern as the Explore agent of
 * Claude Code, the `explore` sub-agent of OpenCode and the `delegate_task` of Hermes.
 *
 * The agent is **read-only by construction**, not by instruction: its integrations only list the tools that read
 * and search the files of the conversation (`CASE_FILE_EXCHANGE`) and the shared folder of the namespace
 * (`NAMESPACE_FILE_EXCHANGE`), and `AGENTOS_CONFIG` is limited to `read`, so it cannot propose a change either.
 * It has no `subAgents`: delegation stops at one level on this path.
 *
 * It runs on the model alias `BIG`, the model of `Coday`, as the three tools above give a sub-agent the model of
 * its parent. A `SMALL` variant is a measure to make, not a default. It is namespace-scoped, like `Coday`, so
 * it can never clash with an agent of the same name in another namespace.
 *
 * Same semantics as [io.whozoss.agentos.codayAgent.CodayAgentSeeder], through [NamespaceAgentCreator]: **existence,
 * not content** (a namespace that already owns an agent named `Searcher`, ours or one a user made, keeps it
 * untouched), and **create-only, never delete**.
 */
@Service
class SearcherAgentSeeder(
    private val properties: SearcherAgentProperties,
    agentConfigService: AgentConfigService,
) {
    private val creator = NamespaceAgentCreator(agentConfigService)

    /**
     * The `Searcher` agent that [namespaceId] owns, created when it owns none.
     *
     * @return the agent the namespace owns, or null when `agentos.defaults.searcher-agent.enabled` is false
     *   (nothing is created, nothing is looked up) or when a concurrent creation conflicted and the agent cannot
     *   be read back yet.
     */
    fun ensureIn(namespaceId: UUID): AgentConfig? {
        if (!properties.enabled) return null
        return creator.ensureIn(namespaceId, AGENT_NAME) { defaultAgent(namespaceId) }
    }

    private fun defaultAgent(namespaceId: UUID) =
        AgentConfig(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            namespaceId = namespaceId,
            name = AGENT_NAME,
            description = DESCRIPTION,
            instructions = loadInstructions(),
            modelName = MODEL_NAME,
            enabled = true,
            integrations =
                mapOf(
                    ConfigAgentIntegration.TYPE to listOf(CONFIG_READ_TOOL),
                    ExchangeIntegrationTypes.CASE to READ_TOOLS,
                    ExchangeIntegrationTypes.NAMESPACE to READ_TOOLS,
                ),
        )

    private fun loadInstructions(): String =
        ClassPathResource(INSTRUCTIONS_RESOURCE).inputStream.use { it.readBytes().toString(Charsets.UTF_8) }

    companion object : KLogging() {
        /**
         * Name of the search sub-agent, a **contract**: the `subAgents` of `Coday` names it, and the instructions of
         * `Coday` tell it to delegate to it. Renaming it silently detaches every `Coday` that relies on it.
         */
        const val AGENT_NAME = "Searcher"

        /** The model alias the agent runs on, the same as `Coday`. */
        const val MODEL_NAME = "BIG"

        const val INSTRUCTIONS_RESOURCE = "agents/searcher-instructions.md"

        /** The only tool of `AGENTOS_CONFIG` the agent gets: reading, never proposing. */
        const val CONFIG_READ_TOOL = "read"

        /** The file tools that read and search, in both exchange scopes. None of them can modify a file. */
        val READ_TOOLS =
            listOf("listFiles", "readFile", "readDocument", "readSpreadsheet", "readAsImage", "searchFiles")

        private const val DESCRIPTION =
            "Read-only search specialist. Finds the files, passages and configuration relevant to a question in the " +
                "shared folder of this namespace and the files of this conversation, and returns a compact report " +
                "with paths. Cannot change anything."
    }
}
