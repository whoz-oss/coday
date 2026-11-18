package io.whozoss.factory.sdk.spi

import org.pf4j.ExtensionPoint

/** Pinned, plugin-owned facts shown with a human checkpoint and checked at reply. */
data class HumanCheckpointContext(
    val run: RunView,
    val flowType: String,
    val stepId: String,
)

sealed interface HumanCheckpointVerdict {
    data class Allowed(val facts: Map<String, Any?>, val prompt: String? = null) : HumanCheckpointVerdict
    data class Denied(val code: String) : HumanCheckpointVerdict
}

/**
 * A domain-neutral policy for workflows that require identity-bound human replies.
 * The host stores [present]'s facts with the interaction, calls [decide] inside
 * the reply transaction, and adds the accepted facts to audited evidence.
 */
interface FactoryHumanCheckpointPolicy : ExtensionPoint {
    fun supports(flowType: String, stepId: String): Boolean = false

    fun present(context: HumanCheckpointContext): HumanCheckpointVerdict =
        HumanCheckpointVerdict.Denied("HUMAN_CHECKPOINT_UNSUPPORTED")

    fun decide(
        context: HumanCheckpointContext,
        presented: Map<String, Any?>,
        actionId: String,
    ): HumanCheckpointVerdict = HumanCheckpointVerdict.Denied("HUMAN_DECISION_UNSUPPORTED")
}
