import { appendFileSync, mkdirSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { randomBytes } from 'node:crypto'
import { fileURLToPath } from 'node:url'

const RUNS_DIR = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'runs')

export type RunStatus = 'pass' | 'fail'
export type PhaseKind = 'agent' | 'code'
export type Facts = Record<string, unknown>

export interface Run {
  runId: string
  filePath: string
  _startedAt: number
  namespaceId?: string
}

export interface Phase {
  name: string
  _startedAt: number
  run: Run
}

let currentRun: Run | null = null
const closedRunIds = new Set<string>()

export function getCurrentRun(): Run | null {
  return currentRun
}

function generateRunId(): string {
  const timestamp = new Date()
    .toISOString()
    .replace(/[-:]/g, '')
    .replace(/\.\d{3}Z$/, 'Z')
  return `${timestamp}-${randomBytes(2).toString('hex')}`
}

function appendLine(filePath: string, record: object): void {
  appendFileSync(filePath, `${JSON.stringify(record)}\n`, 'utf8')
}

export function createRun(workflowName: string, opts: { namespaceId?: string } = {}): Run {
  mkdirSync(RUNS_DIR, { recursive: true })
  const runId = generateRunId()
  const filePath = join(RUNS_DIR, `${runId}.jsonl`)
  const record: Record<string, unknown> = {
    kind: 'run_start',
    runId,
    workflow: workflowName,
    startedAt: new Date().toISOString(),
  }
  if (opts.namespaceId) record.namespaceId = opts.namespaceId
  appendLine(filePath, record)

  const run: Run = { runId, filePath, _startedAt: Date.now() }
  if (opts.namespaceId) run.namespaceId = opts.namespaceId
  currentRun = run
  return run
}

export function startPhase(run: Run, name: string, kind: PhaseKind): Phase {
  appendLine(run.filePath, {
    kind: 'phase',
    name,
    phaseKind: kind,
    status: 'fail',
    startedAt: new Date().toISOString(),
  })
  return { name, _startedAt: Date.now(), run }
}

function endPhase(phase: Phase, status: RunStatus, facts: Facts): void {
  appendLine(phase.run.filePath, {
    kind: 'phase_end',
    name: phase.name,
    status,
    durationMs: Date.now() - phase._startedAt,
    facts,
  })
}

export function passPhase(phase: Phase, facts: Facts = {}): void {
  endPhase(phase, 'pass', facts)
}

export function failPhase(phase: Phase, facts: Facts = {}): void {
  endPhase(phase, 'fail', facts)
}

export function endRun(run: Pick<Run, 'filePath' | '_startedAt'>, status: RunStatus, facts: Facts = {}): void {
  const record: Record<string, unknown> = {
    kind: 'run_end',
    status,
    durationMs: Date.now() - run._startedAt,
    endedAt: new Date().toISOString(),
  }
  if (Object.keys(facts).length > 0) record.facts = facts
  appendLine(run.filePath, record)
}

export function endCurrentRunOnce(status: RunStatus, facts: Facts = {}): boolean {
  const run = currentRun
  if (!run || closedRunIds.has(run.runId)) return false
  endRun(run, status, facts)
  closedRunIds.add(run.runId)
  return true
}
