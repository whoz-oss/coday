/**
 * Filesystem adapter for the Forge ledger store.
 *
 * The append-only JSONL parsing and the deterministic replay are pure and live
 * in `domain/forge-bmad/forge-ledger.ts`; this adapter owns the file boundary
 * (`appendFileSync`, `readFileSync`, `readdirSync`) and the store creation.
 *
 * The TypeScript source is bundled into `factory/runtime/factory-operational.mjs`;
 * `factory/lib/forge-ledger.mjs` re-exports it as a stateless facade.
 */

import { appendFileSync, readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { randomUUID } from 'node:crypto'
import {
  FORGE_LEDGER_SCHEMA_VERSION,
  FORGE_WORKFLOW_VERSION,
  parseForgeLedgerLines,
  projectForgeRun,
} from '../../domain/forge-bmad/forge-ledger.js'
import { G1_POLICY_VERSION } from '../../domain/forge-bmad/forge-human-decision.js'
import type { ForgeLedgerEvent } from '../../domain/forge-bmad/types.js'
import { ensureForgeRunStore } from './forge-roots-resolver.js'

interface ForgeStoreRoots {
  runStoreRoot: string
}

function assertString(value: unknown, name: string): void {
  if (typeof value !== 'string' || !value.trim()) throw new Error(`${name} is required`)
}

function assertWorkItem(item: any, name: string): void {
  if (!item || typeof item !== 'object') throw new Error(`${name} is required`)
  assertString(item.id, `${name}.id`)
  assertString(item.kind, `${name}.kind`)
}

/** Append one event to an existing ledger file. */
export function appendForgeLedgerEvent(filePath: string, event: ForgeLedgerEvent): void {
  appendFileSync(filePath, `${JSON.stringify(event)}\n`, 'utf8')
}

/** Create the EpicRun ledger and append its initial events. */
export function createEpicRun({
  roots,
  epic,
  stories,
  runId = `epic_${randomUUID()}`,
  now = () => new Date().toISOString(),
}: {
  roots: ForgeStoreRoots
  epic: any
  stories: any[]
  runId?: string
  now?: () => string
}): { runId: string; filePath: string; storyRuns: Array<Record<string, any>> } {
  assertWorkItem(epic, 'epic')
  if (!Array.isArray(stories) || stories.length === 0)
    throw new Error('stories must contain at least one explicit Story work item')
  for (const story of stories) {
    assertWorkItem(story, 'story')
    if (story.kind !== 'Story') throw new Error('every child work item must have kind "Story"')
  }

  const filePath = join(ensureForgeRunStore(roots), `${runId}.jsonl`)
  const at = now()
  appendForgeLedgerEvent(filePath, {
    schemaVersion: FORGE_LEDGER_SCHEMA_VERSION,
    event: 'run_started',
    runId,
    runType: 'EpicRun',
    workflow: FORGE_WORKFLOW_VERSION,
    workItem: epic,
    roots,
    at,
  })

  const storyRuns = stories.map((workItem, index) => {
    const storyRunId = `story_${randomUUID()}`
    appendForgeLedgerEvent(filePath, {
      schemaVersion: FORGE_LEDGER_SCHEMA_VERSION,
      event: 'story_run_created',
      runId: storyRunId,
      parentRunId: runId,
      runType: 'StoryRun',
      ordinal: index + 1,
      workItem,
      at: now(),
    })
    return { runId: storyRunId, parentRunId: runId, ordinal: index + 1, workItem }
  })

  appendForgeLedgerEvent(filePath, {
    schemaVersion: FORGE_LEDGER_SCHEMA_VERSION,
    event: 'gate_started',
    runId,
    gate: 'G1',
    attempt: 1,
    status: 'waiting_human',
    requiredDecision: 'intent-approval',
    policyVersion: G1_POLICY_VERSION,
    at: now(),
  })

  return { runId, filePath, storyRuns }
}

/** Read and validate a Forge ledger file. */
export function parseForgeLedger(filePath: string): ForgeLedgerEvent[] {
  return parseForgeLedgerLines(readFileSync(filePath, 'utf8'))
}

/** List every valid Forge run projection in a run-store directory. */
export function listForgeRunProjections(runStoreRoot: string): Array<Record<string, any>> {
  let files: string[] = []
  try {
    files = readdirSync(runStoreRoot).filter((file) => file.endsWith('.jsonl'))
  } catch {
    return []
  }
  return files
    .flatMap((file) => {
      try {
        const projection = projectForgeRun(parseForgeLedger(join(runStoreRoot, file)))
        return projection ? [projection] : []
      } catch {
        return []
      } // legacy registry files remain readable by their existing consumer
    })
    .sort((a, b) => b.startedAt.localeCompare(a.startedAt))
}
