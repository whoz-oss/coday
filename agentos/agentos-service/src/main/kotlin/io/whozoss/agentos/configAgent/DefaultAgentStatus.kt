package io.whozoss.agentos.configAgent

/** Whether a message without `@mention` reaches an agent, and if not, the first condition that fails. */
enum class DefaultAgentStatus(
    val hint: String,
) {
    OK("messages without @mention reach this agent"),
    NOT_SET("no default agent: messages without @mention get no answer; set the namespace's defaultAgentName"),
    NOT_FOUND("no agent with this name in the namespace nor on the platform"),
    DISABLED("the agent exists but is disabled: enable it"),
    NOT_DEPLOYED("the agent is enabled but not deployed on the namespace: deploy it (a user group deployment only reaches that group)"),
}
