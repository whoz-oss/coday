package io.whozoss.agentos.sdk.model

/**
 * Represents an actor in a case conversation.
 * An actor can be a human user or an AI agent.
 */
data class Actor(
    val id: String,
    val displayName: String,
    val role: ActorRole,
)
