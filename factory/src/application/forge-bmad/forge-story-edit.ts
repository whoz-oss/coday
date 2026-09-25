/**
 * Application service for the write-enabled Story edit phase.
 *
 * The pure scope matching and plan parsing live here and in the legacy pure
 * module `factory/lib/plan.mjs`; Git snapshots come from the oracle executor,
 * AgentOS calls from the agentos operations, and ledger/artifact access from
 * the adapters.
 */

import { existsSync, readFileSync } from 'node:fs'
import { createHash, randomUUID } from 'node:crypto'
import { join, resolve } from 'node:path'
import { ensureForgeRunStore } from '../../adapters/forge/forge-roots-resolver.js'
import { appendForgeLedgerEvent, parseForgeLedger } from '../../adapters/forge/forge-ledger-store.js'
import { loadForgeSpec, type ForgeSpecRoots } from '../../adapters/forge/forge-spec-reader.js'
import { snapshotDiff, diffSince } from '../oracle/oracle-executor.js'
import { parsePlan, checkPlanFiles } from '../../../lib/plan.mjs'
import type { CodedError } from '../../domain/forge-bmad/forge-spec.js'
import * as runtimeDefault from '../agentos-operations.js'

/** Schema version of the Story edit record. */
export const STORY_EDIT_SCHEMA_VERSION = 1
/** Policy version of the Story edit phase. */
export const STORY_EDIT_POLICY_VERSION = 'forge-story-edit-v1'

const fail: (code: string, message?: string) => never = (code, message = code) => {
  const error = new Error(message) as CodedError
  error.code = code
  throw error
}

const hash = (value: string): string => `sha256:${createHash('sha256').update(value).digest('hex')}`

interface PlanShape {
  files: string[]
  doneWhen: string
  steps?: string[]
}

const safeArtifact = (store: string, descriptor: any): string => {
  if (!descriptor?.path || !descriptor?.sha256)
    fail('STORY_EDIT_ANALYSIS_ARTIFACT_INVALID', 'Analysis artifact descriptor requires path and sha256.')
  const root = resolve(store)
  const path = resolve(root, descriptor.path)
  if (!path.startsWith(`${root}/`))
    fail('STORY_EDIT_ANALYSIS_ARTIFACT_PATH_INVALID', 'Analysis artifact path escapes the run store.')
  if (!existsSync(path)) fail('STORY_EDIT_ANALYSIS_ARTIFACT_INVALID', 'Analysis artifact does not exist.')
  const text = readFileSync(path, 'utf8')
  if (hash(text) !== descriptor.sha256)
    fail('STORY_EDIT_ANALYSIS_ARTIFACT_HASH_MISMATCH', 'Analysis artifact content does not match its SHA-256.')
  return text
}

const matches = (pattern: string, file: string): boolean =>
  new RegExp(
    `^${pattern
      .split('/')
      .map((p) => (p === '**' ? '.*' : p === '*' ? '[^/]+' : p.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')))
      .join('/')}$`
  ).test(file)

const allowedModified = (file: string, scope: any, plan: PlanShape): boolean =>
  plan.files.includes(file) && !scope.deny.some((p: string) => matches(p, file))

const allowedCreated = (file: string, scope: any): boolean =>
  scope.create.some((p: string) => matches(p, file)) && !scope.deny.some((p: string) => matches(p, file))

function planFromArtifact(text: string): PlanShape {
  const blocks = [...text.matchAll(/```json\s*([\s\S]*?)```/g)].map((m) => m[1]!.trim())
  if (blocks.length !== 1)
    fail('STORY_EDIT_ANALYSIS_PLAN_INVALID', 'Analysis artifact must contain exactly one JSON plan.')
  let raw: any
  try {
    raw = JSON.parse(blocks[0]!)
  } catch {
    fail('STORY_EDIT_ANALYSIS_PLAN_INVALID', 'Analysis artifact JSON plan is invalid.')
  }
  if (
    !raw ||
    typeof raw !== 'object' ||
    Array.isArray(raw) ||
    Object.keys(raw).some((k) => !['files', 'doneWhen', 'steps'].includes(k))
  )
    fail('STORY_EDIT_ANALYSIS_PLAN_INVALID', 'Analysis artifact plan schema is invalid.')
  const parsed: any = parsePlan(`\`\`\`json\n${blocks[0]}\n\`\`\``)
  if (!parsed.ok) fail('STORY_EDIT_ANALYSIS_PLAN_INVALID', parsed.error)
  return parsed.plan
}

/** The AgentOS surface used by the Story edit phase. */
export interface StoryEditRuntime {
  preflightAgent: (namespaceId: string, agentName: string) => Promise<any>
  preflightWritableWorkspace: (namespaceId: string, agent: any, repoRoot: string) => Promise<any>
  createCase: (namespaceId: string, title: string) => Promise<any>
  runAgentTurn: (caseId: string, agentName: string, brief: string) => Promise<any>
}

/** Execute the write-enabled Story edit phase. */
export async function executeStoryEdit({
  roots,
  epicRunId,
  storyRunId,
  analysisExecutionId,
  namespaceId,
  agentName,
  expectedSpecHash,
  storySpecHash,
  supplement,
  runtime = runtimeDefault,
  now = () => new Date().toISOString(),
}: {
  roots: ForgeSpecRoots & { runStoreRoot: string }
  epicRunId: string
  storyRunId: string
  analysisExecutionId: string
  namespaceId: string
  agentName: string
  expectedSpecHash?: string
  storySpecHash?: string
  supplement?: string
  runtime?: StoryEditRuntime
  now?: () => string
}): Promise<Record<string, any>> {
  if (!namespaceId || !agentName) fail('STORY_EDIT_INPUT_INVALID', 'namespaceId and agentName are required.')
  if (supplement !== undefined && (typeof supplement !== 'string' || supplement.length > 4000))
    fail('STORY_EDIT_SUPPLEMENT_INVALID', 'supplement must be a string of at most 4000 characters.')
  const store = ensureForgeRunStore(roots)
  const filePath = join(store, `${epicRunId}.jsonl`)
  if (!existsSync(filePath)) fail('STORY_EDIT_RUN_NOT_FOUND', `Epic run ${epicRunId} has no ledger.`)
  const events = parseForgeLedger(filePath)
  const epic = events.find((e) => e.event === 'run_started' && e.runId === epicRunId)
  if (!epic) fail('STORY_EDIT_RUN_NOT_FOUND', `Epic run ${epicRunId} is absent from its ledger.`)
  const story = events.find(
    (e) => e.event === 'story_run_created' && e.runId === storyRunId && e.parentRunId === epicRunId
  )
  if (!story) fail('STORY_EDIT_STORY_NOT_FOUND', `Story run ${storyRunId} is absent from Epic run ${epicRunId}.`)
  if (
    events.some(
      (e) =>
        e.event === 'story_edit_started' &&
        e.storyRunId === storyRunId &&
        !events.some((f) => f.event === 'story_edit_finished' && f.editId === e.editId)
    )
  )
    fail('STORY_EDIT_ALREADY_RUNNING', 'A Story edit is already active.')
  const g1Event = events.find((e) => e.event === 'human_decision_recorded' && e.runId === epicRunId && e.gate === 'G1')
  if (g1Event?.decision?.outcome !== 'approved') fail('STORY_EDIT_G1_NOT_APPROVED')
  const g2 = events.filter((e) => e.event === 'g2_evaluated' && e.runId === epicRunId && e.status === 'passed').at(-1)
  if (!g2 || g2.spec?.sha256 !== expectedSpecHash) fail('STORY_EDIT_G2_NOT_PASSED')
  if (storySpecHash !== undefined) {
    const g2us = events.find(
      (e) =>
        e.event === 'g2_us_evaluated' &&
        e.storyRunId === storyRunId &&
        e.status === 'passed' &&
        e.storySpec?.sha256 === storySpecHash
    )
    if (!g2us) fail('STORY_EDIT_G2_US_NOT_PASSED')
  }
  const analysis = events.find(
    (e) =>
      e.event === 'agent_execution_finished' &&
      e.executionId === analysisExecutionId &&
      e.storyRunId === storyRunId &&
      e.status === 'finished'
  )
  const validation = events.find(
    (e) => e.event === 'story_analysis_plan_validated' && e.executionId === analysisExecutionId && e.status === 'valid'
  )
  if (!analysis || !validation) fail('STORY_EDIT_ANALYSIS_NOT_VALID')
  const text = safeArtifact(store, analysis.artifact)
  if (
    validation.artifact?.sha256 !== analysis.artifact?.sha256 ||
    validation.artifact?.path !== analysis.artifact?.path
  )
    fail('STORY_EDIT_ANALYSIS_PLAN_STALE', 'Analysis validation does not reference the finished artifact.')
  const plan = planFromArtifact(text)
  const missing = checkPlanFiles(plan.files, roots.repoRoot).missingFiles
  if (missing.length) fail('STORY_EDIT_ANALYSIS_PLAN_STALE', `Analysis plan files are missing: ${missing.join(', ')}.`)
  const spec = loadForgeSpec({
    specPath: g2.spec.path,
    roots,
    workItem: epic.workItem,
  })
  if (spec.sha256 !== g2.spec.sha256) fail('STORY_EDIT_SPEC_HASH_STALE')
  const agent = await runtime.preflightAgent(namespaceId, agentName)
  if (!agent.ok) fail('STORY_EDIT_AGENT_PREFLIGHT_FAILED')
  const writable = await runtime.preflightWritableWorkspace(namespaceId, agent.agent, roots.repoRoot)
  if (!writable.ok) fail('STORY_EDIT_WRITABLE_PREFLIGHT_FAILED', writable.reason)
  const editId = `edit_${randomUUID()}`
  const brief = [
    `Epic: ${epic.workItem.id}`,
    `Story: ${story.workItem.id}`,
    `Spec SHA-256: ${spec.sha256}`,
    `Files to modify: ${plan.files.join(', ')}`,
    `Done when: ${plan.doneWhen}`,
    `Allow: ${spec.frontmatter.scope.allow.join(', ')}`,
    `Create: ${spec.frontmatter.scope.create.join(', ')}`,
    `Deny: ${spec.frontmatter.scope.deny.join(', ')}`,
    supplement ? `Supplement: ${supplement}` : '',
    'Implement only this plan. Do not run shell, git, tests, builds, or oracles.',
  ]
    .filter(Boolean)
    .join('\n')
  const before = snapshotDiff(roots.repoRoot)
  const created = await runtime.createCase(namespaceId, `Forge edit ${story.workItem.id}`)
  appendForgeLedgerEvent(filePath, {
    schemaVersion: 1,
    event: 'story_edit_started',
    runId: epicRunId,
    storyRunId,
    editId,
    analysisExecutionId,
    caseId: created.id,
    policyVersion: STORY_EDIT_POLICY_VERSION,
    at: now(),
  })
  const turn = await runtime.runAgentTurn(created.id, agentName, brief)
  const changed = diffSince(before, roots.repoRoot)
  const invalid = [
    ...changed.modified.filter((file) => !allowedModified(file, spec.frontmatter.scope, plan)),
    ...changed.untracked.filter((file) => !allowedCreated(file, spec.frontmatter.scope)),
  ]
  const status = turn.status === 'finished' && invalid.length === 0 ? 'finished' : 'failed'
  appendForgeLedgerEvent(filePath, {
    schemaVersion: 1,
    event: 'story_edit_finished',
    runId: epicRunId,
    storyRunId,
    editId,
    caseId: created.id,
    status,
    outcome: turn.status,
    caseStatus: turn.caseStatus ?? null,
    killedByBudget: turn.killedByBudget === true,
    filesModified: changed.modified,
    filesCreated: changed.untracked,
    diffValidation: {
      status: invalid.length ? 'invalid' : 'valid',
      code: invalid.length ? 'STORY_EDIT_DIFF_OUT_OF_SCOPE' : 'STORY_EDIT_DIFF_VALID',
      invalidFiles: invalid,
    },
    at: now(),
  })
  return {
    editId,
    status,
    filesModified: changed.modified,
    filesCreated: changed.untracked,
    diffValidation: { status: invalid.length ? 'invalid' : 'valid', invalidFiles: invalid },
  }
}
