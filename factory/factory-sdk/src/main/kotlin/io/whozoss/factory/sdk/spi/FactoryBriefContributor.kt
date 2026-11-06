package io.whozoss.factory.sdk.spi

import org.pf4j.ExtensionPoint

/**
 * Context passed to [FactoryBriefContributor.contribute] when assembling an
 * agent step's brief.
 */
data class BriefContext(
    val run: RunView,
    val flowType: String,
    val stepId: String,
    val agentName: String,
    val fragmentInputs: Map<String, Any?> = emptyMap(),
)

/**
 * Additional brief sections and watched file paths contributed by a plugin for
 * an agent turn.
 */
data class BriefContribution(
    val sections: List<String> = emptyList(),
    val watchedPaths: List<String> = emptyList(),
)

/**
 * PF4J extension point allowing a plugin to enrich agent briefs and declare
 * files whose content SHA-256 and diff must be captured before and after the turn.
 */
interface FactoryBriefContributor : ExtensionPoint {
    fun supports(flowType: String, stepId: String, agentName: String): Boolean = false

    fun contribute(context: BriefContext): BriefContribution = BriefContribution()
}
