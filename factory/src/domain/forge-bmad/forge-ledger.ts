/**
 * Pure Forge ledger domain: JSONL parsing/validation and the deterministic
 * replay (`projectForgeRun`) that derives the dashboard's display state from
 * the append-only journal.
 *
 * The file-backed store (`createEpicRun`, `parseForgeLedger`,
 * `listForgeRunProjections`) lives in `adapters/forge/forge-ledger-store.ts`.
 *
 * Domain purity: no `node:fs`, HTTP, AgentOS or Git CLI dependency.
 */

import type { ForgeLedgerEvent } from './types.js'
import { computeG1EvidenceSetHash } from './forge-human-decision.js'

/** Schema version of the Forge ledger. */
export const FORGE_LEDGER_SCHEMA_VERSION = 1

/** Workflow version stamped on an EpicRun. */
export const FORGE_WORKFLOW_VERSION = 'forge-epic-v1'

/**
 * Parse an append-only JSONL ledger body. Throws on malformed JSON or an
 * unsupported schema version.
 */
export function parseForgeLedgerLines(raw: string): ForgeLedgerEvent[] {
  return raw
    .split('\n')
    .filter(Boolean)
    .map((line, index) => {
      let event: ForgeLedgerEvent
      try {
        event = JSON.parse(line)
      } catch {
        throw new Error(`invalid JSONL at line ${index + 1}`)
      }
      if (event.schemaVersion !== FORGE_LEDGER_SCHEMA_VERSION)
        throw new Error(`unsupported forge ledger schema at line ${index + 1}`)
      return event
    })
}

/** Pure replay: the current display state is entirely derived from JSONL. */
export function projectForgeRun(events: readonly ForgeLedgerEvent[]): Record<string, any> | null {
  const start = events.find((event) => event.event === 'run_started' && event.runType === 'EpicRun')
  if (!start) return null
  const storyEvents = events
    .filter((event) => event.event === 'story_run_created' && event.parentRunId === start.runId)
    .sort((a, b) => a.ordinal - b.ordinal)
  const g1 = events
    .filter((event) => event.event === 'gate_started' && event.runId === start.runId && event.gate === 'G1')
    .at(-1)
  const decision =
    g1 &&
    events.find(
      (event) =>
        event.event === 'human_decision_recorded' &&
        event.runId === start.runId &&
        event.gate === 'G1' &&
        event.attempt === g1.attempt
    )
  const evidenceSetHash = g1 ? computeG1EvidenceSetHash(events, start.runId, g1.attempt, g1.policyVersion) : null
  const g1Status = decision ? decision.decision.outcome : (g1?.status ?? 'not_started')
  const g2 = events.filter((event) => event.event === 'g2_evaluated' && event.runId === start.runId).at(-1)
  const validations = new Map(
    events.filter((event) => event.event === 'story_analysis_plan_validated').map((event) => [event.executionId, event])
  )
  const oracleCampaignsByStory = new Map<string, any[]>()
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
    list.push({
      campaignId: gate.campaignId,
      editId: gate.editId,
      status: gate.status,
      specHash: gate.specHash,
      policyVersion: gate.policyVersion,
      results,
    })
    oracleCampaignsByStory.set(gate.storyRunId, list)
  }
  const editsByStory = new Map<string, any[]>()
  for (const edit of events.filter((event) => event.event === 'story_edit_finished')) {
    const list = editsByStory.get(edit.storyRunId) ?? []
    list.push({
      editId: edit.editId,
      status: edit.status,
      outcome: edit.outcome,
      caseId: edit.caseId,
      diffValidation: edit.diffValidation,
      filesModified: edit.filesModified,
      filesCreated: edit.filesCreated,
    })
    editsByStory.set(edit.storyRunId, list)
  }
  const g2usByStory = new Map<string, ForgeLedgerEvent>()
  for (const ev of events.filter((event) => event.event === 'g2_us_evaluated')) {
    g2usByStory.set(ev.storyRunId, ev)
  }
  const executions = events.filter((event) => event.event === 'agent_execution_finished')
  const executionsByStory = new Map<string, any[]>()
  for (const execution of executions) {
    const list = executionsByStory.get(execution.storyRunId) ?? []
    const validation = validations.get(execution.executionId)
    list.push({
      executionId: execution.executionId,
      caseId: execution.caseId,
      runtime: execution.runtime,
      role: execution.role,
      agentName: execution.agentName,
      namespaceId: execution.namespaceId,
      status: execution.status,
      outcome: execution.outcome,
      caseStatus: execution.caseStatus ?? null,
      killedByBudget: execution.killedByBudget === true,
      artifact: execution.artifact ?? null,
      analysisValidation: validation
        ? { schemaVersion: validation.planSchemaVersion, status: validation.status, code: validation.code }
        : null,
      observedAt: execution.observedAt,
    })
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
      ...(g1
        ? [
            {
              gate: 'G1',
              attempt: g1.attempt,
              status: g1Status,
              requiredDecision: g1.requiredDecision,
              policyVersion: g1.policyVersion,
              evidenceSetHash,
              decision: decision?.decision ?? null,
            },
          ]
        : []),
      ...(g2
        ? [
            {
              gate: 'G2',
              attempt: g2.attempt,
              status: g2.status,
              code: g2.code,
              policyVersion: g2.policyVersion,
              spec: g2.spec ?? null,
            },
          ]
        : []),
    ],
    stories: storyEvents.map((event) => {
      const executions = executionsByStory.get(event.runId) ?? []
      const edits = editsByStory.get(event.runId) ?? []
      const oracleCampaigns = oracleCampaignsByStory.get(event.runId) ?? []
      const g2usEvent = g2usByStory.get(event.runId) ?? null
      const storyG2 = g2usEvent
        ? {
            gate: 'G2-US',
            attempt: g2usEvent.attempt,
            status: g2usEvent.status,
            code: g2usEvent.code,
            policyVersion: g2usEvent.policyVersion,
            storySpec: g2usEvent.storySpec ?? null,
          }
        : null
      // Event order is the ledger's authoritative chronology. A Story's visible
      // state is its latest completed workflow step, without inventing a status.
      const latestCampaign = oracleCampaigns.at(-1)
      const latestEdit = edits.at(-1)
      const latestExecution = executions.at(-1)
      const status = latestCampaign?.status ?? latestEdit?.status ?? latestExecution?.status ?? 'not_started'
      return {
        runId: event.runId,
        ordinal: event.ordinal,
        status,
        workItem: event.workItem,
        executions,
        edits,
        oracleCampaigns,
        storyG2,
      }
    }),
  }
}
