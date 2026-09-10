import { appendFileSync } from 'node:fs'
import { createHash, randomUUID } from 'node:crypto'
import { join } from 'node:path'
import { ensureForgeRunStore } from './forge-roots.mjs'
import { parseForgeLedger } from './forge-ledger.mjs'
import * as agentosRuntime from './agentos.mjs'

export const AGENT_EXECUTION_REFERENCE_SCHEMA_VERSION = 1
export const STORY_ANALYSIS_POLICY_VERSION = 'forge-story-analysis-v1'
const TERMINAL_SUCCESS = new Set(['finished'])

function append(path, event) { appendFileSync(path, `${JSON.stringify(event)}\n`, 'utf8') }
function g1(events, parentRunId) { const gate = events.filter((e) => e.event === 'gate_started' && e.runId === parentRunId && e.gate === 'G1').at(-1); const decision = gate && events.find((e) => e.event === 'human_decision_recorded' && e.runId === parentRunId && e.gate === 'G1' && e.attempt === gate.attempt); return decision?.decision.outcome }
function g2(events, parentRunId, expectedSpecHash) { const result = events.filter((e) => e.event === 'g2_evaluated' && e.runId === parentRunId && e.status === 'passed').at(-1); return !!result && (!expectedSpecHash || result.spec?.sha256 === expectedSpecHash) }
function reference({ executionId, caseId, storyRunId, role, agentName, namespaceId, observedAt, status }) { return { schemaVersion: AGENT_EXECUTION_REFERENCE_SCHEMA_VERSION, runtime: 'agentos', executionId, caseId, storyRunId, role, agentName, namespaceId, observedAt, status } }

/** Runs exactly one AgentOS turn. Its result is operational evidence, never G3. */
export async function executeStoryAnalysis({ roots, epicRunId, storyRunId, namespaceId, agentName, brief, expectedSpecHash, runtime = agentosRuntime, now = () => new Date().toISOString() }) {
  if (!namespaceId || !agentName || !brief) throw new Error('STORY_ANALYSIS_INPUT_INVALID')
  const filePath = join(ensureForgeRunStore(roots), `${epicRunId}.jsonl`); const events = parseForgeLedger(filePath)
  const story = events.find((e) => e.event === 'story_run_created' && e.runId === storyRunId && e.parentRunId === epicRunId)
  if (!story) throw new Error('STORY_RUN_NOT_FOUND')
  if (g1(events, epicRunId) !== 'approved') throw new Error('STORY_ANALYSIS_G1_NOT_APPROVED')
  if (!g2(events, epicRunId, expectedSpecHash)) throw new Error('STORY_ANALYSIS_G2_NOT_PASSED')
  const preflight = await runtime.preflightAgent(namespaceId, agentName)
  if (!preflight.ok) throw new Error(`STORY_ANALYSIS_AGENT_PREFLIGHT_FAILED:${preflight.reason}`)
  if (typeof runtime.preflightReadOnlyWorkspace !== 'function') throw new Error('STORY_ANALYSIS_READ_ONLY_PREFLIGHT_UNAVAILABLE')
  const readOnlyWorkspace = await runtime.preflightReadOnlyWorkspace(namespaceId, preflight.agent, roots.repoRoot)
  if (!readOnlyWorkspace.ok) throw new Error(`STORY_ANALYSIS_READ_ONLY_PREFLIGHT_FAILED:${readOnlyWorkspace.reason}`)
  const executionId = `exec_${randomUUID()}`; const created = await runtime.createCase(namespaceId, `Forge analysis ${story.workItem.id}`); const caseId = created.id
  const started = reference({ executionId, caseId, storyRunId, role: 'analyst', agentName, namespaceId, observedAt: now(), status: 'started' })
  append(filePath, { schemaVersion: 1, event: 'agent_execution_started', runId: epicRunId, parentRunId: epicRunId, ...started, policyVersion: STORY_ANALYSIS_POLICY_VERSION, briefArtifact: { kind: 'brief', sha256: `sha256:${createHash('sha256').update(brief).digest('hex')}`, mediaType: 'text/plain', schemaVersion: 1 } })
  let turn
  try { turn = await runtime.runAgentTurn(caseId, agentName, brief) } catch (error) { turn = { status: 'error', message: String(error), killedByBudget: false } }
  const status = TERMINAL_SUCCESS.has(turn.status) ? 'finished' : 'failed'
  const ended = reference({ executionId, caseId, storyRunId, role: 'analyst', agentName, namespaceId, observedAt: now(), status })
  append(filePath, { schemaVersion: 1, event: 'agent_execution_finished', runId: epicRunId, parentRunId: epicRunId, ...ended, policyVersion: STORY_ANALYSIS_POLICY_VERSION, outcome: turn.status, caseStatus: turn.caseStatus ?? null, killedByBudget: turn.killedByBudget === true })
  return { execution: ended, outcome: turn.status }
}
