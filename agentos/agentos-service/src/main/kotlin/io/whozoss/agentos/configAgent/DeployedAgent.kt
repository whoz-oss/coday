package io.whozoss.agentos.configAgent

/** An agent deployed directly on a namespace, as the namespace readiness lists it. */
data class DeployedAgent(
    val name: String,
    val enabled: Boolean,
)
