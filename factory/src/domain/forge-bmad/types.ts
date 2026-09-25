/**
 * Shared value shapes of the Forge/BMAD domain.
 *
 * The Forge ledger is an append-only JSONL journal: every event is a
 * self-describing record whose `event` field selects its shape. Domain
 * functions replay that journal without interpreting I/O; the file-backed
 * store lives under `adapters/forge/`.
 *
 * Domain purity: this module has no `node:fs`, HTTP, AgentOS or Git CLI
 * dependency.
 */

/** One append-only Forge ledger event, as parsed from a JSONL line. */
export type ForgeLedgerEvent = Record<string, any>

/** A governed work item (Epic or Story) referenced by a Forge run. */
export interface ForgeWorkItem {
  id: string
  kind: string
  [key: string]: unknown
}
