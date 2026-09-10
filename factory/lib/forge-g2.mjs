import { appendFileSync } from 'node:fs'
import { join } from 'node:path'
import { ensureForgeRunStore } from './forge-roots.mjs'
import { parseForgeLedger } from './forge-ledger.mjs'
import { G2_POLICY_VERSION, loadForgeSpec } from './forge-spec.mjs'

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
