package io.whozoss.agentos.caseEvent

import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent

/**
 * The conversation as it effectively stands, computed from the ordered event log of a case.
 *
 * The log is append-only: a user who re-runs a message does not delete anything, the new
 * [MessageEvent] carries `rerunOf = X` instead. Walking the log in order, such a message removes from
 * the result built so far X and everything that follows it, then takes its place, and the walk goes on.
 * What lies between X and the re-run (the answers of the agent, the tool calls, the questions, the
 * sub-cases, the orchestration events...) is therefore out of the conversation, and so is X.
 *
 * It works for chained re-runs (re-running a message that was itself a re-run) since each step only
 * looks at the result built so far.
 *
 * When X is not in the result built so far (unknown id, or already cut by an earlier re-run), nothing is
 * removed and the message is simply added: the conversation is already cut before X, or X was never in
 * it. The API refuses that case before it is recorded, so this only protects the replay of a log that
 * should not contain it.
 *
 * This is the single place where this rule lives. It is applied where the history is read to reason on it:
 * the runtime of a case ([io.whozoss.agentos.caseFlow.CaseRuntime.conversation]), which feeds the agents and
 * their tools, and the few services that read a stored history. The SSE stream and the REST listing keep
 * returning the whole log, the clients apply the same rule.
 *
 * Returns the receiver itself when no message re-runs another one (the common case, no copy).
 */
fun List<CaseEvent>.effectiveConversation(): List<CaseEvent> {
    if (none { it.isRerun() }) return this
    val conversation = ArrayList<CaseEvent>(size)
    for (event in this) {
        val target = (event as? MessageEvent)?.takeIf { it.isRerun() }?.rerunOf
        if (target != null) {
            val index = conversation.indexOfLast { it.id == target }
            if (index >= 0) conversation.subList(index, conversation.size).clear()
        }
        conversation.add(event)
    }
    return conversation
}

private fun CaseEvent.isRerun(): Boolean = this is MessageEvent && actor.role == ActorRole.USER && rerunOf != null
