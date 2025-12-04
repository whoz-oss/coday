package io.biznet.agentos.orchestration

/**
 * Represents an actor in a case conversation.
 * An actor can be a human user or an AI agent.
 */
data class Actor(
    val id: String,
    val displayName: String,
    val role: ActorRole
)

/**
 * The role of an actor in the conversation.
 */
enum class ActorRole {
    /** Human user */
    USER,
    /** AI agent */
    AGENT
}
