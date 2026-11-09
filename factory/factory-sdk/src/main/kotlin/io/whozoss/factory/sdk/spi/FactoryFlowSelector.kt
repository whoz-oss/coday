package io.whozoss.factory.sdk.spi

import org.pf4j.ExtensionPoint

/**
 * PF4J extension point driving the fragment sequence of a parent Run.
 *
 * Execution at the end of each fragment is split into two pure phases:
 * 1. [reduce] computes the [RunUpdate] (domain state replacement, new/resolved findings);
 * 2. [next] chooses the next [FlowDecision] from the updated [RunView].
 *
 * The host persists the state update, the decision, loop counters and the next
 * fragment launch intent in a single atomic, idempotent transaction.
 */
interface FactoryFlowSelector : ExtensionPoint {
    /** Logical run types handled by this selector (e.g. `setOf("forge-controller-v1")`). */
    fun runTypes(): Set<String> = emptySet()

    /** Policy version frozen on the Run at creation time. */
    fun selectorVersion(): String = "1.0.0"

    /** Initializes the Run state and selects the first fragment to apply. */
    fun start(run: RunView): Pair<RunUpdate, FlowDecision>

    /** Pure phase 1: consolidates domain state and findings from a finished fragment. */
    fun reduce(run: RunView, outcome: FragmentOutcome): RunUpdate = RunUpdate()

    /** Pure phase 2: selects the next action from the updated [RunView]. */
    fun next(run: RunView): FlowDecision
}
