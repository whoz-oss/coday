package io.whozoss.factory.forge.port

import io.whozoss.factory.forge.domain.ForgeLedgerEvent
import io.whozoss.factory.forge.domain.ForgeRoots

/** Parameters of [ForgeLedgerStore.createEpicRun]. */
data class CreateEpicRunRequest(
    val roots: ForgeRoots,
    val epic: Map<String, Any?>,
    val stories: List<Map<String, Any?>>,
    val runId: String? = null,
)

/** Result of [ForgeLedgerStore.createEpicRun]. */
data class CreateEpicRunResult(
    val runId: String,
    val filePath: String,
    val storyRuns: List<Map<String, Any?>>,
)

/**
 * Append-only JSONL persistence of the Forge ledger.
 *
 * Port of `factory/src/adapters/forge/forge-ledger-store.ts`. The ledger stays
 * file-based: it is never stored in PostgreSQL, preserving append-only
 * semantics and the JSONL parsing/projection contract.
 */
interface ForgeLedgerStore {

    /** Create the EpicRun ledger and append its initial events. */
    fun createEpicRun(request: CreateEpicRunRequest): CreateEpicRunResult

    /** Append one event to an existing ledger file. */
    fun append(filePath: String, event: ForgeLedgerEvent)

    /** Read and validate a Forge ledger file. */
    fun parse(filePath: String): List<ForgeLedgerEvent>

    /** List every valid Forge run projection in a run-store directory. */
    fun listProjections(runStoreRoot: String): List<Map<String, Any?>>
}
