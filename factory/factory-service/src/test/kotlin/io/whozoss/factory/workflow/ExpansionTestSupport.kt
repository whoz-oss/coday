package io.whozoss.factory.workflow

import io.whozoss.factory.workspace.NamespaceGitState
import io.whozoss.factory.workspace.WorkspacePrecheckPort
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/**
 * The agents enabled in the namespace, as the workspace precheck port reports them. A test sets [agents] to the
 * list it wants, or to `null` to simulate an unreadable agent list (a continuation is then refused, fail-closed).
 */
class MutableAgentList : WorkspacePrecheckPort {
    @Volatile
    var agents: List<String>? = DEFAULT_AGENTS

    fun reset() {
        agents = DEFAULT_AGENTS
    }

    override fun isNamespaceAccessible(namespaceId: String, callerExternalUserId: String?): Boolean = true

    override fun namespaceGitState(namespaceId: String, callerExternalUserId: String?): NamespaceGitState? = null

    override fun availableAgents(namespaceId: String, callerExternalUserId: String?): List<String>? = agents

    companion object {
        val DEFAULT_AGENTS = listOf("Archay", "Frontend")
    }
}

/** Replaces the AgentOS-backed precheck port with a [MutableAgentList] for the expansion integration tests. */
@TestConfiguration
class ExpansionPrecheckPortConfiguration {
    @Bean
    @Primary
    fun expansionPrecheckPort(): MutableAgentList = MutableAgentList()
}
