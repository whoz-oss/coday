package io.whozoss.agentos.sdk.agent

/**
 * Represents an AI agent in the AgentOS orchestrator.
 *
 * An agent is a configured AI model with specific capabilities and context requirements.
 * Agents are provided by plugins and registered with AgentOS for orchestration.
 *
 * ## Example
 *
 * ```kotlin
 * Agent(
 *     id = "code-reviewer",
 *     name = "Code Reviewer",
 *     description = "Reviews code for best practices and potential issues",
 *     version = "1.0.0",
 *     capabilities = listOf("code-review", "kotlin", "java", "security"),
 *     requiredContext = setOf(ContextType.CODE_REVIEW, ContextType.WORKSPACE),
 *     tags = setOf("development", "quality"),
 *     priority = 8,
 *     status = AgentStatus.ACTIVE
 * )
 * ```
 *
 * @property id Unique identifier for the agent (kebab-case recommended)
 * @property name Human-readable agent name
 * @property description Detailed description of what the agent does
 * @property version Agent version (semantic versioning recommended)
 * @property capabilities List of capabilities/skills the agent has
 * @property requiredContext Set of context types the agent needs to operate
 * @property tags Additional tags for categorization and discovery
 * @property priority Agent priority for selection (0-10, higher = more priority)
 * @property status Current status of the agent
 */
data class Agent(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val capabilities: List<String>,
    val requiredContext: Set<ContextType>,
    val tags: Set<String> = emptySet(),
    val priority: Int = 5,
    val status: AgentStatus = AgentStatus.ACTIVE,
) {
    init {
        require(id.isNotBlank()) { "Agent ID cannot be blank" }
        require(name.isNotBlank()) { "Agent name cannot be blank" }
        require(priority in 0..100) { "Agent priority must be between 0 and 100" }
    }
}

/**
 * Status of an agent in the system.
 *
 * Determines whether the agent is available for use and how it should be treated.
 */
enum class AgentStatus {
    /**
     * Agent is active and available for use.
     */
    ACTIVE,

    /**
     * Agent is deprecated but still functional.
     * Should be avoided for new work.
     */
    DEPRECATED,

    /**
     * Agent is inactive and not available for use.
     */
    INACTIVE,

    /**
     * Agent is under maintenance.
     * Temporarily unavailable but will be active again.
     */
    MAINTENANCE,
}

/**
 * Types of context that an agent might require or be relevant for.
 *
 * Context types help AgentOS route requests to appropriate agents based on
 * the operational context.
 */
enum class ContextType {
    /**
     * Code review context - analyzing code changes, pull requests, etc.
     */
    CODE_REVIEW,

    /**
     * General purpose context - no specific requirements.
     */
    GENERAL,

    /**
     * User persona context - requires information about the user.
     */
    PERSONA,

    /**
     * Role-based context - requires role/permission information.
     */
    ROLES,

    /**
     * Screen/UI context - working with user interface elements.
     */
    SCREEN,

    /**
     * Ticket/issue context - working with tickets, issues, tasks.
     */
    TICKET,

    /**
     * Workspace context - requires workspace/project information.
     */
    WORKSPACE,

    /**
     * Agents focused on talent management and profiles
     */
    TALENT,

    /**
     * Agents focused on demand management and staffing requests
     */
    DEMANDS,

    /**
     * Agents focused on operational tasks and workflows
     */
    OPERATIONS,

    /**
     * Agents focused on analytics and reporting
     */
    ANALYTICS,
}
