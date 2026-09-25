/**
 * Application service for the deterministic G2 (Epic spec) and G2-US (Story
 * spec) gates.
 *
 * The pure spec parsers/validators and the inheritance rules live in
 * `domain/forge-bmad/`; spec reads live in `adapters/forge/forge-spec-reader.ts`
 * and the append-only ledger access in `adapters/forge/forge-ledger-store.ts`.
 */

import { join } from 'node:path'
import { ensureForgeRunStore } from '../../adapters/forge/forge-roots-resolver.js'
import { appendForgeLedgerEvent, parseForgeLedger } from '../../adapters/forge/forge-ledger-store.js'
import { loadForgeSpec, readStorySpec, type ForgeSpecRoots } from '../../adapters/forge/forge-spec-reader.js'
import { G2_POLICY_VERSION, type CodedError } from '../../domain/forge-bmad/forge-spec.js'
import { G2_US_POLICY_VERSION, validateInheritance } from '../../domain/forge-bmad/forge-story-spec.js'
import type { ForgeLedgerEvent } from '../../domain/forge-bmad/types.js'

/** Roots carrying the run store and the spec confinement. */
export interface ForgeGateRoots extends ForgeSpecRoots {
  runStoreRoot: string
}

function gate(events: readonly ForgeLedgerEvent[], runId: string, name: string): ForgeLedgerEvent | undefined {
  return events.filter((event) => event.event === 'gate_started' && event.runId === runId && event.gate === name).at(-1)
}

function g1Status(events: readonly ForgeLedgerEvent[], runId: string): string {
  const g1 = gate(events, runId, 'G1')
  const decision =
    g1 &&
    events.find(
      (event) =>
        event.event === 'human_decision_recorded' &&
        event.runId === runId &&
        event.gate === 'G1' &&
        event.attempt === g1.attempt
    )
  return decision?.decision.outcome ?? g1?.status ?? 'missing'
}

/** Evaluate the Epic G2 gate against a spec file. */
export function evaluateG2({
  roots,
  runId,
  specPath,
  now = () => new Date().toISOString(),
}: {
  roots: ForgeGateRoots
  runId: string
  specPath: string
  now?: () => string
}): { status: string; code?: string; event: ForgeLedgerEvent } {
  const filePath = join(ensureForgeRunStore(roots), `${runId}.jsonl`)
  const events = parseForgeLedger(filePath)
  const start = events.find((event) => event.event === 'run_started' && event.runId === runId)
  if (!start) throw new Error('G2_RUN_NOT_FOUND')
  const prior = events.filter((event) => event.event === 'g2_evaluated' && event.runId === runId).at(-1)
  let spec: ReturnType<typeof loadForgeSpec>
  try {
    spec = loadForgeSpec({ specPath, roots, workItem: start.workItem })
  } catch (error) {
    const code = (error as CodedError).code ?? 'G2_SPEC_INVALID'
    return record(filePath, runId, prior, null, 'blocked', code, now)
  }
  // A previous G1-precondition block is deliberately not terminal: the human
  // approval is an append-only event that can arrive later. All other matching
  // spec/policy results are idempotent.
  if (prior?.status === 'passed' && prior.spec?.sha256 === spec.sha256 && prior.policyVersion === G2_POLICY_VERSION)
    return { status: 'idempotent', event: prior }
  if (prior?.status === 'passed' && prior.spec?.sha256 !== spec.sha256)
    return { status: 'conflict', code: 'G2_SPEC_HASH_CHANGED', event: prior }
  if (g1Status(events, runId) !== 'approved')
    return record(filePath, runId, prior, spec, 'blocked', 'G2_G1_NOT_APPROVED', now)
  return record(filePath, runId, prior, spec, 'passed', 'G2_SPEC_VALID', now)
}

function record(
  filePath: string,
  runId: string,
  prior: ForgeLedgerEvent | undefined,
  spec: ReturnType<typeof loadForgeSpec> | null,
  status: string,
  code: string,
  now: () => string
): { status: 'recorded'; event: ForgeLedgerEvent } {
  const attempt = (prior?.attempt ?? 0) + 1
  const event: ForgeLedgerEvent = {
    schemaVersion: 1,
    event: 'g2_evaluated',
    runId,
    gate: 'G2',
    attempt,
    status,
    code,
    policyVersion: G2_POLICY_VERSION,
    spec: spec && { path: spec.path, sha256: spec.sha256, schemaVersion: spec.schemaVersion },
    at: now(),
  }
  appendForgeLedgerEvent(filePath, event)
  return { status: 'recorded', event }
}

/** Evaluate the Story G2-US gate against a spec file, inheriting from the Epic. */
export function evaluateG2US({
  roots,
  epicRunId,
  storyRunId,
  storySpecPath,
  now = () => new Date().toISOString(),
}: {
  roots: ForgeGateRoots
  epicRunId: string
  storyRunId: string
  storySpecPath: string
  now?: () => string
}): { status: string; code?: string; event: ForgeLedgerEvent } {
  const filePath = join(ensureForgeRunStore(roots), `${epicRunId}.jsonl`)
  const events = parseForgeLedger(filePath)

  // Prior event lookup — read early so attempt counter increments correctly in all branches
  const prior = events.filter((e) => e.event === 'g2_us_evaluated' && e.storyRunId === storyRunId).at(-1)

  // Precondition: EpicRun must exist
  const epicStart = events.find((e) => e.event === 'run_started' && e.runId === epicRunId)
  if (!epicStart)
    return recordUS(filePath, epicRunId, storyRunId, null, prior, 'blocked', 'G2_US_EPIC_RUN_NOT_FOUND', now)

  // Precondition: StoryRun must exist and be parented to this EpicRun
  const storyRun = events.find(
    (e) => e.event === 'story_run_created' && e.runId === storyRunId && e.parentRunId === epicRunId
  )
  if (!storyRun)
    return recordUS(filePath, epicRunId, storyRunId, null, prior, 'blocked', 'G2_US_STORY_RUN_NOT_FOUND', now)

  // Precondition: G1 must be approved (non-terminal — réévaluable)
  if (g1Status(events, epicRunId) !== 'approved')
    return recordUS(filePath, epicRunId, storyRunId, null, prior, 'blocked', 'G2_US_G1_NOT_APPROVED', now)

  // Precondition: G2 Epic must be passed
  const g2EpicEvent = events
    .filter((e) => e.event === 'g2_evaluated' && e.runId === epicRunId && e.status === 'passed')
    .at(-1)
  if (!g2EpicEvent) return recordUS(filePath, epicRunId, storyRunId, null, prior, 'blocked', 'G2_US_G2_NOT_PASSED', now)

  // Load Story spec (may throw with G2_US_* codes)
  let storySpec: ReturnType<typeof readStorySpec>
  try {
    storySpec = readStorySpec(storySpecPath, roots)
  } catch (error) {
    const code = (error as CodedError).code ?? 'G2_US_SPEC_INVALID'
    return recordUS(filePath, epicRunId, storyRunId, null, prior, 'blocked', code, now)
  }

  // Idempotence: same hash + same policy + not a non-terminal precondition block
  if (
    prior?.storySpec?.sha256 === storySpec.sha256 &&
    prior.policyVersion === G2_US_POLICY_VERSION &&
    prior.code !== 'G2_US_G1_NOT_APPROVED' &&
    prior.code !== 'G2_US_G2_NOT_PASSED'
  )
    return { status: 'idempotent', event: prior }

  // Conflict: a passed event exists but spec hash has changed
  if (prior && prior.status === 'passed' && prior.storySpec?.sha256 !== storySpec.sha256) {
    return { status: 'conflict', code: 'G2_US_SPEC_HASH_CHANGED', event: prior }
  }

  // Verify workItem.id matches the StoryRun
  if (storySpec.frontmatter.workItem?.id !== storyRun.workItem?.id) {
    return recordUS(filePath, epicRunId, storyRunId, storySpec, prior, 'blocked', 'G2_US_WORK_ITEM_MISMATCH', now)
  }

  // Load Epic spec from the last passed g2_evaluated event
  let epicSpec: ReturnType<typeof loadForgeSpec>
  try {
    epicSpec = loadForgeSpec({ specPath: g2EpicEvent.spec.path, roots, workItem: epicStart.workItem })
  } catch (error) {
    const code = (error as CodedError).code ?? 'G2_US_SPEC_INVALID'
    return recordUS(filePath, epicRunId, storyRunId, storySpec, prior, 'blocked', code, now)
  }

  // Validate inheritance
  const { valid, violations } = validateInheritance(storySpec.frontmatter, epicSpec.frontmatter)
  if (!valid) {
    return recordUS(
      filePath,
      epicRunId,
      storyRunId,
      storySpec,
      prior,
      'blocked',
      'G2_US_INHERITANCE_VIOLATION',
      now,
      violations
    )
  }

  return recordUS(filePath, epicRunId, storyRunId, storySpec, prior, 'passed', 'G2_US_SPEC_VALID', now)
}

function recordUS(
  filePath: string,
  epicRunId: string,
  storyRunId: string,
  storySpec: ReturnType<typeof readStorySpec> | null,
  prior: ForgeLedgerEvent | undefined,
  status: string,
  code: string,
  now: () => string,
  violations?: unknown
): { status: 'recorded'; event: ForgeLedgerEvent } {
  const attempt = (prior?.attempt ?? 0) + 1
  const event: ForgeLedgerEvent = {
    schemaVersion: 1,
    event: 'g2_us_evaluated',
    runId: epicRunId,
    storyRunId,
    gate: 'G2-US',
    attempt,
    status,
    code,
    policyVersion: G2_US_POLICY_VERSION,
    storySpec: storySpec
      ? { path: storySpec.path, sha256: storySpec.sha256, schemaVersion: storySpec.schemaVersion }
      : null,
    ...(violations ? { violations } : {}),
    at: now(),
  }
  appendForgeLedgerEvent(filePath, event)
  return { status: 'recorded', event }
}
