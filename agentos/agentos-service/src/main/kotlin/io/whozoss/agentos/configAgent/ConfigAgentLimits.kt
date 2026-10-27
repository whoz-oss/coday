package io.whozoss.agentos.configAgent

import java.time.Duration

/**
 * Fixed limits of the config agent. There is no ceiling on what may be proposed: the REST API
 * decides at apply time with the rights of the user who clicks. A configurable ceiling, if ever
 * needed, starts here.
 */
object ConfigAgentLimits {
    const val MAX_OPERATIONS = 20
    val PROPOSAL_TTL: Duration = Duration.ofHours(24)
}
