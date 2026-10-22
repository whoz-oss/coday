package io.whozoss.agentos.workflow

import java.util.UUID

/**
 * Supplies the [LoopConfig] a loop case must execute.
 *
 * The loop runtime holds one of these instead of carrying configuration of its own:
 * configuration belongs to whatever initiated the run (ADR-64 decision 2 — process separated
 * from context). Each initiator contributes its own implementation; the runtime stays unaware
 * of schedulers, Studio previews, or anything else that may start a loop.
 *
 * Returns null when this resolver does not recognise [caseId] — the case was not started by
 * the initiator it speaks for.
 */
fun interface LoopConfigResolver {
    fun resolve(caseId: UUID): LoopConfig?
}
