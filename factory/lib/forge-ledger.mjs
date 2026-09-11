import { appendFileSync, readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { randomUUID } from 'node:crypto'
import { ensureForgeRunStore } from './forge-roots.mjs'
import { computeG1EvidenceSetHash, G1_POLICY_VERSION } from './forge-human-decision.mjs'

export const FORGE_LEDGER_SCHEMA_VERSION = 1
export const FORGE_WORKFLOW_VERSION = 'forge-epic-v1'

function assertString(value, name) {
  if (typeof value !== 'string' || !value.trim()) throw new Error(`${name} is required`)
}

function assertWorkItem(item, name) {
  if (!item || typeof item !== 'object') throw new Error(`${name} is required`)
  assertString(item.id, `${name}.id`)
  assertString(item.kind, `${name}.kind`)
}

function append(filePath, event) {
  appendFileSync(filePath, `${JSON.stringify(event)}\n`, 'utf8')
}

export function createEpicRun({ roots, epic, stories, runId = `epic_${randomUUID()}`, now = () => new Date().toISOString() }) {
  assertWorkItem(epic, 'epic')
  if (!Array.isArray(stories) || stories.length === 0) throw new Error('stories must contain at least one explicit Story work item')
  for (const story of stories) {
    assertWorkItem(story, 'story')
    if (story.kind !== 'Story') throw new Error('every child work item must have kind "Story"')
  }

  const filePath = join(ensureForgeRunStore(roots), `${runId}.jsonl`)
  const at = now()
  append(filePath, {
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
    append(filePath, {
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

  append(filePath, {
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

export function parseForgeLedger(filePath) {
  return readFileSync(filePath, 'utf8').split('\n').filter(Boolean).map((line, index) => {
    let event
    try { event = JSON.parse(line) } catch { throw new Error(`invalid JSONL at line ${index + 1}`) }
    if (event.schemaVersion !== FORGE_LEDGER_SCHEMA_VERSION) throw new Error(`unsupported forge ledger schema at line ${index + 1}`)
    return event
  })
}

/** Pure replay: the current display state is entirely derived from JSONL. */
export function projectForgeRun(events) {
  const start = events.find((event) => event.event === 'run_started' && event.runType === 'EpicRun')
  if (!start) return null
  const storyEvents = events.filter((event) => event.event === 'story_run_created' && event.parentRunId === start.runId)
    .sort((a, b) => a.ordinal - b.ordinal)
  const g1 = events.filter((event) => event.event === 'gate_started' && event.runId === start.runId && event.gate === 'G1').at(-1)
  const decision = g1 && events.find((event) => event.event === 'human_decision_recorded' && event.runId === start.runId && event.gate === 'G1' && event.attempt === g1.attempt)
  const evidenceSetHash = g1 ? computeG1EvidenceSetHash(events, start.runId, g1.attempt, g1.policyVersion) : null
  const g1Status = decision ? decision.decision.outcome : (g1?.status ?? 'not_started')
  const g2 = events.filter((event) => event.event === 'g2_evaluated' && event.runId === start.runId).at(-1)
  const validations = new Map(events.filter((event) => event.event === 'story_analysis_plan_validated').map((event) => [event.executionId, event]))
  const oracleCampaignsByStory = new Map()
  for (const gate of events.filter((event) => event.event === 'story_g3_evaluated')) {
    const results = events
      .filter((event) => event.event === 'story_oracle_finished' && event.campaignId === gate.campaignId)
      .map((event) => ({
        name: event.name,
        status: event.status,
        code: event.code,
        ownerProjects: event.ownerProjects ?? [],
        target: event.target ?? null,
        buildHosts: event.buildHosts ?? [],
        ownersWithTestTarget: event.ownersWithTestTarget ?? [],
        ownersWithoutTestTarget: event.ownersWithoutTestTarget ?? [],
        exitCode: event.exitCode,
        durationMs: event.durationMs,
        commandHash: event.commandHash,
      }))
    const list = oracleCampaignsByStory.get(gate.storyRunId) ?? []
    list.push({ campaignId: gate.campaignId, editId: gate.editId, status: gate.status, specHash: gate.specHash, policyVersion: gate.policyVersion, results })
    oracleCampaignsByStory.set(gate.storyRunId, list)
  }
  const editsByStory = new Map()
  for (const edit of events.filter((event) => event.event === 'story_edit_finished')) { const list=editsByStory.get(edit.storyRunId) ?? []; list.push({editId:edit.editId,status:edit.status,outcome:edit.outcome,caseId:edit.caseId,diffValidation:edit.diffValidation,filesModified:edit.filesModified,filesCreated:edit.filesCreated}); editsByStory.set(edit.storyRunId,list) }
  const g2usByStory = new Map()
  for (const ev of events.filter((event) => event.event === 'g2_us_evaluated')) {
    g2usByStory.set(ev.storyRunId, ev)
  }
  const executions = events.filter((event) => event.event === 'agent_execution_finished')
  const executionsByStory = new Map()
  for (const execution of executions) {
    const list = executionsByStory.get(execution.storyRunId) ?? []
    const validation = validations.get(execution.executionId)
    list.push({ executionId: execution.executionId, caseId: execution.caseId, runtime: execution.runtime, role: execution.role, agentName: execution.agentName, namespaceId: execution.namespaceId, status: execution.status, outcome: execution.outcome, caseStatus: execution.caseStatus ?? null, killedByBudget: execution.killedByBudget === true, artifact: execution.artifact ?? null, analysisValidation: validation ? { schemaVersion: validation.planSchemaVersion, status: validation.status, code: validation.code } : null, observedAt: execution.observedAt })
    executionsByStory.set(execution.storyRunId, list)
  }
  return {
    schemaVersion: FORGE_LEDGER_SCHEMA_VERSION,
    runId: start.runId,
    runType: start.runType,
    workflow: start.workflow,
    workItem: start.workItem,
    roots: start.roots,
    startedAt: start.at,
    status: g1Status,
    gates: [
      ...(g1 ? [{ gate: 'G1', attempt: g1.attempt, status: g1Status, requiredDecision: g1.requiredDecision, policyVersion: g1.policyVersion, evidenceSetHash, decision: decision?.decision ?? null }] : []),
      ...(g2 ? [{ gate: 'G2', attempt: g2.attempt, status: g2.status, code: g2.code, policyVersion: g2.policyVersion, spec: g2.spec ?? null }] : []),
    ],
    stories: storyEvents.map((event) => {
      const executions = executionsByStory.get(event.runId) ?? []
      const edits = editsByStory.get(event.runId) ?? []
      const oracleCampaigns = oracleCampaignsByStory.get(event.runId) ?? []
      const g2usEvent = g2usByStory.get(event.runId) ?? null
      const storyG2 = g2usEvent ? { gate: 'G2-US', attempt: g2usEvent.attempt, status: g2usEvent.status, code: g2usEvent.code, policyVersion: g2usEvent.policyVersion, storySpec: g2usEvent.storySpec ?? null } : null
      // Event order is the ledger's authoritative chronology. A Story's visible
      // state is its latest completed workflow step, without inventing a status.
      const latestCampaign = oracleCampaigns.at(-1)
      const latestEdit = edits.at(-1)
      const latestExecution = executions.at(-1)
      const status = latestCampaign?.status ?? latestEdit?.status ?? latestExecution?.status ?? 'not_started'
      return { runId: event.runId, ordinal: event.ordinal, status, workItem: event.workItem, executions, edits, oracleCampaigns, storyG2 }
    }),
  }
}

export function listForgeRunProjections(runStoreRoot) {
  let files = []
  try { files = readdirSync(runStoreRoot).filter((file) => file.endsWith('.jsonl')) } catch { return [] }
  return files.flatMap((file) => {
    try {
      const projection = projectForgeRun(parseForgeLedger(join(runStoreRoot, file)))
      return projection ? [projection] : []
    } catch { return [] } // legacy registry files remain readable by their existing consumer
  }).sort((a, b) => b.startedAt.localeCompare(a.startedAt))
}
