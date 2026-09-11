import { appendFileSync } from 'node:fs'
import { join } from 'node:path'
import { ensureForgeRunStore } from './forge-roots.mjs'
import { parseForgeLedger } from './forge-ledger.mjs'
import { G2_POLICY_VERSION, loadForgeSpec } from './forge-spec.mjs'
import { G2_US_POLICY_VERSION, readStorySpec, validateInheritance } from './forge-story-spec.mjs'

function gate(events, runId, name) { return events.filter((event) => event.event === 'gate_started' && event.runId === runId && event.gate === name).at(-1) }
function g1Status(events, runId) { const g1 = gate(events, runId, 'G1'); const decision = g1 && events.find((event) => event.event === 'human_decision_recorded' && event.runId === runId && event.gate === 'G1' && event.attempt === g1.attempt); return decision?.decision.outcome ?? g1?.status ?? 'missing' }
function append(path, event) { appendFileSync(path, `${JSON.stringify(event)}\n`, 'utf8') }
export function evaluateG2({ roots, runId, specPath, now = () => new Date().toISOString() }) {
  const filePath = join(ensureForgeRunStore(roots), `${runId}.jsonl`); const events = parseForgeLedger(filePath)
  const start = events.find((event) => event.event === 'run_started' && event.runId === runId)
  if (!start) throw new Error('G2_RUN_NOT_FOUND')
  const prior = events.filter((event) => event.event === 'g2_evaluated' && event.runId === runId).at(-1)
  let spec
  try { spec = loadForgeSpec({ specPath, roots, workItem: start.workItem }) } catch (error) {
    const code = error.code ?? 'G2_SPEC_INVALID'; return record(filePath, runId, prior, null, 'blocked', code, now)
  }
  // A previous G1-precondition block is deliberately not terminal: the human
  // approval is an append-only event that can arrive later. All other matching
  // spec/policy results are idempotent.
  if (prior?.spec?.sha256 === spec.sha256 && prior.policyVersion === G2_POLICY_VERSION && prior.code !== 'G2_G1_NOT_APPROVED') return { status: 'idempotent', event: prior }
  if (prior && prior.spec?.sha256 !== spec.sha256) return { status: 'conflict', code: 'G2_SPEC_HASH_CHANGED', event: prior }
  if (g1Status(events, runId) !== 'approved') return record(filePath, runId, prior, spec, 'blocked', 'G2_G1_NOT_APPROVED', now)
  return record(filePath, runId, prior, spec, 'passed', 'G2_SPEC_VALID', now)
}
function record(filePath, runId, prior, spec, status, code, now) {
  const attempt = (prior?.attempt ?? 0) + 1
  const event = { schemaVersion: 1, event: 'g2_evaluated', runId, gate: 'G2', attempt, status, code, policyVersion: G2_POLICY_VERSION, spec: spec && { path: spec.path, sha256: spec.sha256, schemaVersion: spec.schemaVersion }, at: now() }
  append(filePath, event); return { status: 'recorded', event }
}

export function evaluateG2US({ roots, epicRunId, storyRunId, storySpecPath, now = () => new Date().toISOString() }) {
  const filePath = join(ensureForgeRunStore(roots), `${epicRunId}.jsonl`)
  const events = parseForgeLedger(filePath)

  // Prior event lookup — read early so attempt counter increments correctly in all branches
  const prior = events.filter((e) => e.event === 'g2_us_evaluated' && e.storyRunId === storyRunId).at(-1)

  // Precondition: EpicRun must exist
  const epicStart = events.find((e) => e.event === 'run_started' && e.runId === epicRunId)
  if (!epicStart) return recordUS(filePath, epicRunId, storyRunId, null, prior, 'blocked', 'G2_US_EPIC_RUN_NOT_FOUND', now)

  // Precondition: StoryRun must exist and be parented to this EpicRun
  const storyRun = events.find((e) => e.event === 'story_run_created' && e.runId === storyRunId && e.parentRunId === epicRunId)
  if (!storyRun) return recordUS(filePath, epicRunId, storyRunId, null, prior, 'blocked', 'G2_US_STORY_RUN_NOT_FOUND', now)

  // Precondition: G1 must be approved (non-terminal — réévaluable)
  if (g1Status(events, epicRunId) !== 'approved') return recordUS(filePath, epicRunId, storyRunId, null, prior, 'blocked', 'G2_US_G1_NOT_APPROVED', now)

  // Precondition: G2 Epic must be passed
  const g2EpicEvent = events.filter((e) => e.event === 'g2_evaluated' && e.runId === epicRunId && e.status === 'passed').at(-1)
  if (!g2EpicEvent) return recordUS(filePath, epicRunId, storyRunId, null, prior, 'blocked', 'G2_US_G2_NOT_PASSED', now)

  // Load Story spec (may throw with G2_US_* codes)
  let storySpec
  try { storySpec = readStorySpec(storySpecPath, roots) } catch (error) {
    const code = error.code ?? 'G2_US_SPEC_INVALID'
    return recordUS(filePath, epicRunId, storyRunId, null, prior, 'blocked', code, now)
  }

  // Idempotence: same hash + same policy + not a non-terminal precondition block
  if (
    prior?.storySpec?.sha256 === storySpec.sha256 &&
    prior.policyVersion === G2_US_POLICY_VERSION &&
    prior.code !== 'G2_US_G1_NOT_APPROVED' &&
    prior.code !== 'G2_US_G2_NOT_PASSED'
  ) return { status: 'idempotent', event: prior }

  // Conflict: a passed event exists but spec hash has changed
  if (prior && prior.status === 'passed' && prior.storySpec?.sha256 !== storySpec.sha256) {
    return { status: 'conflict', code: 'G2_US_SPEC_HASH_CHANGED', event: prior }
  }

  // Verify workItem.id matches the StoryRun
  if (storySpec.frontmatter.workItem?.id !== storyRun.workItem?.id) {
    return recordUS(filePath, epicRunId, storyRunId, storySpec, prior, 'blocked', 'G2_US_WORK_ITEM_MISMATCH', now)
  }

  // Load Epic spec from the last passed g2_evaluated event
  let epicSpec
  try { epicSpec = loadForgeSpec({ specPath: g2EpicEvent.spec.path, roots, workItem: epicStart.workItem }) } catch (error) {
    const code = error.code ?? 'G2_US_SPEC_INVALID'
    return recordUS(filePath, epicRunId, storyRunId, storySpec, prior, 'blocked', code, now)
  }

  // Validate inheritance
  const { valid, violations } = validateInheritance(storySpec.frontmatter, epicSpec.frontmatter)
  if (!valid) {
    return recordUS(filePath, epicRunId, storyRunId, storySpec, prior, 'blocked', 'G2_US_INHERITANCE_VIOLATION', now, violations)
  }

  return recordUS(filePath, epicRunId, storyRunId, storySpec, prior, 'passed', 'G2_US_SPEC_VALID', now)
}

function recordUS(filePath, epicRunId, storyRunId, storySpec, prior, status, code, now, violations) {
  const attempt = (prior?.attempt ?? 0) + 1
  const event = {
    schemaVersion: 1,
    event: 'g2_us_evaluated',
    runId: epicRunId,
    storyRunId,
    gate: 'G2-US',
    attempt,
    status,
    code,
    policyVersion: G2_US_POLICY_VERSION,
    storySpec: storySpec ? { path: storySpec.path, sha256: storySpec.sha256, schemaVersion: storySpec.schemaVersion } : null,
    ...(violations ? { violations } : {}),
    at: now(),
  }
  append(filePath, event)
  return { status: 'recorded', event }
}
