#!/usr/bin/env ts-node
/**
 * forge-gate-run.ts
 *
 * Script-driven FORGE gate state machine.
 *
 * The script owns gate state transitions and deterministic steps.
 * Agent judgment steps (review synthesis, ticket drafting, spec quality)
 * are surfaced as structured PAUSE outputs — the calling agent (ProductEngineer)
 * invokes the relevant sub-agent and resumes the gate by passing the result
 * back as a --resume argument.
 *
 * Usage:
 *   # Advance a gate
 *   npx ts-node coday/scripts/forge-gate-run.ts run --ticket <ID> --gate <1|2|3|4> --run-dir <path>
 *
 *   # Resume after an agent-judgment step
 *   npx ts-node coday/scripts/forge-gate-run.ts resume --ticket <ID> --gate <1|2|3|4> --run-dir <path> --step <step-id> --result <json-string>
 *
 *   # Query current gate state
 *   npx ts-node coday/scripts/forge-gate-run.ts status --ticket <ID> --run-dir <path>
 *
 * Output: JSON to stdout. Exit code always 0 — errors travel in JSON.
 *
 * Output shapes:
 *
 *   done:  { ok: true, outcome: 'done', gate, ticket, state_file, summary }
 *   pause: { ok: true, outcome: 'pause', gate, ticket, step, agent, prompt, capture_as, state_file }
 *   halt:  { ok: true, outcome: 'halt', gate, ticket, step, reason, human_action_required, state_file }
 *   error: { ok: false, error, ... }
 *
 * Gate flow:
 *   Gate 1: draft_ticket → [PAUSE: review] → tally_votes → [PAUSE: human_checkpoint] → write_state
 *   Gate 2: readiness_check → [PAUSE: spec_quality] → branch_proposal → [PAUSE: human_checkpoint] → write_state
 *   Gate 3: surface_check → git_evidence → [PAUSE: review] → tally_votes → [PAUSE: human_checkpoint] → write_state
 *   Gate 4: [PAUSE: e2e_delegation] → [PAUSE: human_checkpoint] → write_state
 */

import * as fs from 'fs'
import * as path from 'path'
import { exec } from 'child_process'
import { updateForgeRunYamlAtomic } from './forge-run-yaml-updater'

// ---------------------------------------------------------------------------
// Generic workflow synchronization helper
// ---------------------------------------------------------------------------

/** Synchronizes the updated authoritative YAML without blocking the gate run. */
function syncWorkflowProjection(ticket: string): void {
  const namespaceId = process.env.NAMESPACE_ID ?? process.env.FACTORY_NAMESPACE_ID
  if (!namespaceId) return

  const factoryUrl = process.env.FACTORY_URL ?? process.env.FACTORY_SERVER_URL ?? 'http://localhost:3141'
  const syncScript = path.join(__dirname, 'forge-workflow-sync.ts')
  const cmd = `FACTORY_SERVER_URL=${factoryUrl} NAMESPACE_ID=${namespaceId} npx ts-node ${syncScript} ${ticket}`
  exec(cmd, { env: process.env }, (err) => {
    if (err) process.stderr.write(`[forge-gate-run] workflow sync skipped: ${err.message}\n`)
  })
}
// ---------------------------------------------------------------------------

type Outcome = 'done' | 'pause' | 'halt' | 'error'

interface PauseOutput {
  ok: true
  outcome: 'pause'
  gate: number
  ticket: string
  step: string
  agent: string
  prompt: string
  capture_as: string
  state_file: string
}

interface HaltOutput {
  ok: true
  outcome: 'halt'
  gate: number
  ticket: string
  step: string
  reason: string
  human_action_required: string
  state_file: string
}

interface DoneOutput {
  ok: true
  outcome: 'done'
  gate: number
  ticket: string
  state_file: string
  summary: string
}

interface ErrorOutput {
  ok: false
  error: string
  [key: string]: unknown
}

type Output = PauseOutput | HaltOutput | DoneOutput | ErrorOutput

interface GateState {
  ticket: string
  gate: number
  step: string // current step id
  started_at: string
  updated_at: string
  results: Record<string, unknown> // captured results keyed by capture_as
  pending_result?: string // step id waiting for a resume result
  completed: boolean
  halted: boolean
  halt_reason?: string
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function emit(obj: Output): void {
  process.stdout.write(JSON.stringify(obj) + '\n')
}

function now(): string {
  return new Date().toISOString()
}

function stateFilePath(runDir: string, ticket: string, gate: number): string {
  return path.join(runDir, `${ticket}-gate${gate}-state.json`)
}

function loadState(filePath: string): GateState | null {
  if (!fs.existsSync(filePath)) return null
  try {
    return JSON.parse(fs.readFileSync(filePath, 'utf-8')) as GateState
  } catch {
    return null
  }
}

function saveState(filePath: string, state: GateState): void {
  fs.mkdirSync(path.dirname(filePath), { recursive: true })
  const tmp = filePath + '.tmp'
  fs.writeFileSync(tmp, JSON.stringify(state, null, 2))
  fs.renameSync(tmp, filePath)
}

function loadRunRecord(runDir: string, ticket: string): Record<string, unknown> {
  const p = path.join(runDir, `${ticket}.yaml`)
  if (!fs.existsSync(p)) return {}
  // Minimal parse: just return raw content as a string blob under 'raw'
  // Full YAML parsing not needed here — we append via string operations
  return { raw: fs.readFileSync(p, 'utf-8') }
}

// ---------------------------------------------------------------------------
// Step definitions per gate
// ---------------------------------------------------------------------------

// Each step is either:
//   { kind: 'pause', id, agent, buildPrompt, capture_as }
//   { kind: 'deterministic', id, run }
//   { kind: 'human-checkpoint', id, buildMessage }

type Step =
  | { kind: 'pause'; id: string; agent: string; buildPrompt: (state: GateState) => string; capture_as: string }
  | { kind: 'deterministic'; id: string; run: (state: GateState, runDir: string, ticket: string) => StepResult }
  | { kind: 'human-checkpoint'; id: string; buildMessage: (state: GateState) => string }

interface StepResult {
  ok: boolean
  halt?: { reason: string; human_action_required: string }
  data?: Record<string, unknown>
}

// ---------------------------------------------------------------------------
// Vote tally (deterministic)
// ---------------------------------------------------------------------------

function tallyVotes(review: unknown): { verdict: 'PASS' | 'FAIL' | 'SPLIT'; pass: number; fail: number } {
  if (!review || typeof review !== 'object') return { verdict: 'SPLIT', pass: 0, fail: 0 }
  const r = review as Record<string, unknown>
  const votes = r['votes'] as Record<string, string> | undefined
  if (!votes) return { verdict: 'SPLIT', pass: 0, fail: 0 }
  let pass = 0
  let fail = 0
  for (const v of Object.values(votes)) {
    if (String(v).toUpperCase() === 'PASS') pass++
    else fail++
  }
  const total = pass + fail
  if (total === 0) return { verdict: 'SPLIT', pass, fail }
  if (pass >= 3) return { verdict: 'PASS', pass, fail }
  if (fail >= 3) return { verdict: 'FAIL', pass, fail }
  return { verdict: 'SPLIT', pass, fail }
}

// ---------------------------------------------------------------------------
// Gate step sequences
// ---------------------------------------------------------------------------

const GATE_STEPS: Record<number, Step[]> = {
  1: [
    {
      kind: 'pause',
      id: 'draft_ticket',
      agent: 'ProductEngineer',
      buildPrompt: (state) =>
        `Draft a structured Jira ticket from this input:\n\n${JSON.stringify(state.results['input'] ?? '(no input captured)', null, 2)}\n\nReturn JSON: { summary, description, acceptance_criteria, technical_notes, open_questions }`,
      capture_as: 'draft',
    },
    {
      kind: 'pause',
      id: 'review',
      agent: 'Reviewer',
      buildPrompt: (state) =>
        `Run a deep adversarial review (4 lenses) on this ticket draft. Return JSON: { votes: { AdversarialReviewer1: PASS|FAIL, AdversarialReviewer2: PASS|FAIL, AdversarialReviewer3: PASS|FAIL, AdversarialReviewer4: PASS|FAIL }, findings: [{ reviewer, severity, description }] }\n\nContent:\n${JSON.stringify(state.results['draft'] ?? '', null, 2)}`,
      capture_as: 'review',
    },
    {
      kind: 'deterministic',
      id: 'tally_votes',
      run: (state) => {
        const { verdict, pass, fail } = tallyVotes(state.results['review'])
        if (verdict === 'FAIL') {
          return {
            ok: false,
            halt: {
              reason: `Review failed ${fail}-${pass}. Findings require ticket revision.`,
              human_action_required: 'Review findings, revise ticket, and re-run gate 1.',
            },
          }
        }
        if (verdict === 'SPLIT') {
          return {
            ok: false,
            halt: {
              reason: `Review split 2-2. Human must break the tie.`,
              human_action_required: 'Review the split findings and decide: approve ticket or send back for revision.',
            },
          }
        }
        return { ok: true, data: { verdict, pass, fail } }
      },
    },
    {
      kind: 'human-checkpoint',
      id: 'human_checkpoint',
      buildMessage: (state) => {
        const draft = state.results['draft'] as Record<string, unknown> | undefined
        const review = state.results['review'] as Record<string, unknown> | undefined
        const tally = state.results['tally_votes'] as Record<string, unknown> | undefined
        return (
          `Gate 1 ready for human decision.\n\n` +
          `Ticket draft: ${JSON.stringify(draft?.['summary'] ?? '(see state file)', null, 2)}\n` +
          `Review verdict: ${tally?.['verdict'] ?? 'unknown'} (${tally?.['pass'] ?? 0}-${tally?.['fail'] ?? 0})\n` +
          `Findings: ${JSON.stringify(review?.['findings'] ?? [], null, 2)}\n\n` +
          `Resume with: --step human_checkpoint --result '{"decision":"approved"}' to advance.\n` +
          `Or: --result '{"decision":"rejected"}' to reject.`
        )
      },
    },
    {
      kind: 'deterministic',
      id: 'write_state',
      run: (state, runDir, ticket) => {
        const decision = (state.results['human_checkpoint'] as Record<string, unknown> | undefined)?.['decision']
        if (decision !== 'approved' && decision !== 'approved-with-changes') {
          return {
            ok: false,
            halt: { reason: 'Ticket rejected by human.', human_action_required: 'Revise and re-run gate 1.' },
          }
        }
        const runRecordPath = path.join(runDir, `${ticket}.yaml`)
        updateForgeRunYamlAtomic(runRecordPath, { gate: 1, decision, at: now() })
        syncWorkflowProjection(ticket)
        return { ok: true, data: { run_record_updated: runRecordPath } }
      },
    },
  ],

  2: [
    {
      kind: 'deterministic',
      id: 'readiness_check',
      run: (_state, runDir, ticket) => {
        // Look for sprint-status.yaml relative to run dir
        const statusFile = path.join(path.dirname(runDir), 'bmad', 'implementation-artifacts', 'sprint-status.yaml')
        if (!fs.existsSync(statusFile)) {
          // Not a blocker — sprint-status may not exist for all workflows
          return { ok: true, data: { readiness: 'skipped', reason: 'no sprint-status.yaml found' } }
        }
        // Delegate to bmad-sprint-plan validate via shell would require child_process.
        // Emit the file path for PE to call bmad-sprint-plan validate directly.
        return {
          ok: true,
          data: {
            readiness: 'pending_script',
            status_file: statusFile,
            instruction:
              'Call bmad-sprint-plan validate --status-file <status_file> and pass result back as step readiness_check result.',
          },
        }
      },
    },
    {
      kind: 'pause',
      id: 'spec_quality',
      agent: 'ProductEngineer',
      buildPrompt: (state) => {
        const readiness = state.results['readiness_check'] as Record<string, unknown> | undefined
        return (
          `Check spec quality against the Gate 2 bar:\n` +
          `- All affected surfaces enumerated explicitly\n` +
          `- All ACs testable (Given/When/Then), no TBDs\n` +
          `- Negative Scope section present and filled\n\n` +
          `Readiness check result: ${JSON.stringify(readiness ?? {}, null, 2)}\n\n` +
          `Return JSON: { verdict: PASS|FAIL, findings: [{ severity, description }] }`
        )
      },
      capture_as: 'spec_quality',
    },
    {
      kind: 'deterministic',
      id: 'check_spec_quality',
      run: (state) => {
        const sq = state.results['spec_quality'] as Record<string, unknown> | undefined
        if (!sq) return { ok: true, data: {} }
        if (sq['verdict'] === 'FAIL') {
          return {
            ok: false,
            halt: {
              reason: `Spec quality check failed: ${JSON.stringify(sq['findings'])}`,
              human_action_required: 'Fix spec issues and re-run gate 2.',
            },
          }
        }
        return { ok: true, data: { spec_quality_verdict: sq['verdict'] } }
      },
    },
    {
      kind: 'pause',
      id: 'branch_proposal',
      agent: 'ProductEngineer',
      buildPrompt: (state) =>
        `Propose a branch name for this ticket. Format: feat/<assignee>/<ticket-id>_<short-description>.\nTicket: ${state.ticket}\nReturn JSON: { branch: string, source_branch: string, story_points: number }`,
      capture_as: 'branch_proposal',
    },
    {
      kind: 'human-checkpoint',
      id: 'human_checkpoint',
      buildMessage: (state) => {
        const sq = state.results['spec_quality'] as Record<string, unknown> | undefined
        const bp = state.results['branch_proposal'] as Record<string, unknown> | undefined
        return (
          `Gate 2 ready for human decision.\n\n` +
          `Spec quality: ${sq?.['verdict'] ?? 'skipped'}\n` +
          `Branch proposal: ${bp?.['branch'] ?? '(not set)'}\n` +
          `Source branch: ${bp?.['source_branch'] ?? 'sprint'}\n` +
          `Story points: ${bp?.['story_points'] ?? '(not estimated)'}\n\n` +
          `Resume with: --step human_checkpoint --result '{"decision":"approved","branch":"<branch-name>"}' to advance.`
        )
      },
    },
    {
      kind: 'deterministic',
      id: 'write_state',
      run: (state, runDir, ticket) => {
        const decision = (state.results['human_checkpoint'] as Record<string, unknown> | undefined)?.['decision']
        if (decision !== 'approved' && decision !== 'approved-with-changes') {
          return {
            ok: false,
            halt: { reason: 'Spec rejected by human.', human_action_required: 'Revise spec and re-run gate 2.' },
          }
        }
        const branch =
          (state.results['human_checkpoint'] as Record<string, unknown> | undefined)?.['branch'] ??
          (state.results['branch_proposal'] as Record<string, unknown> | undefined)?.['branch'] ??
          '(not set)'
        const runRecordPath = path.join(runDir, `${ticket}.yaml`)
        updateForgeRunYamlAtomic(runRecordPath, { gate: 2, decision, at: now() })
        syncWorkflowProjection(ticket)
        return { ok: true, data: { run_record_updated: runRecordPath, branch } }
      },
    },
  ],

  3: [
    {
      kind: 'deterministic',
      id: 'git_evidence',
      run: (_state, _runDir, _ticket) => {
        // Signal to PE to call bmad-git-evidence and pass result back
        return {
          ok: true,
          data: {
            instruction:
              'Call bmad-git-evidence --repo . --range sprint..HEAD and pass result back as step git_evidence result.',
            pending_script: 'bmad-git-evidence',
          },
        }
      },
    },
    {
      kind: 'deterministic',
      id: 'surface_check',
      run: (state) => {
        const gitEvidence = state.results['git_evidence'] as Record<string, unknown> | undefined
        // If git evidence was passed back with actual file list, check it
        // Otherwise flag as pending
        if (!gitEvidence || gitEvidence['pending_script']) {
          return {
            ok: true,
            data: { surface_check: 'pending_git_evidence', note: 'Re-run after bmad-git-evidence result is passed.' },
          }
        }
        const files = (gitEvidence['files'] as Array<{ path: string }> | undefined) ?? []
        // Surface check: all changed files noted. Actual spec-surface matching requires PE judgment.
        return {
          ok: true,
          data: {
            surface_check: 'files_listed',
            changed_files: files.map((f) => f.path),
            note: 'Verify each file is traceable to a spec surface. Flag any that are not.',
          },
        }
      },
    },
    {
      kind: 'pause',
      id: 'review',
      agent: 'Reviewer',
      buildPrompt: (state) => {
        const evidence = state.results['git_evidence'] as Record<string, unknown> | undefined
        const surfaceCheck = state.results['surface_check'] as Record<string, unknown> | undefined
        return (
          `Run a deep adversarial code review (4 lenses). Return JSON: { votes: { AdversarialReviewer1: PASS|FAIL, AdversarialReviewer2: PASS|FAIL, AdversarialReviewer3: PASS|FAIL, AdversarialReviewer4: PASS|FAIL }, findings: [{ reviewer, severity, description, should_have_been_caught_at }] }\n\n` +
          `Git evidence:\n${JSON.stringify(evidence ?? {}, null, 2)}\n\n` +
          `Surface check:\n${JSON.stringify(surfaceCheck ?? {}, null, 2)}`
        )
      },
      capture_as: 'review',
    },
    {
      kind: 'deterministic',
      id: 'tally_votes',
      run: (state) => {
        const { verdict, pass, fail } = tallyVotes(state.results['review'])
        if (verdict === 'FAIL') {
          return {
            ok: false,
            halt: {
              reason: `Code review failed ${fail}-${pass}.`,
              human_action_required:
                'Review findings, fix implementation, and re-run gate 3. Or accept findings and override.',
            },
          }
        }
        if (verdict === 'SPLIT') {
          return {
            ok: false,
            halt: {
              reason: 'Code review split 2-2.',
              human_action_required: 'Review split findings and decide: approve or send back.',
            },
          }
        }
        return { ok: true, data: { verdict, pass, fail } }
      },
    },
    {
      kind: 'human-checkpoint',
      id: 'human_checkpoint',
      buildMessage: (state) => {
        const review = state.results['review'] as Record<string, unknown> | undefined
        const tally = state.results['tally_votes'] as Record<string, unknown> | undefined
        const surface = state.results['surface_check'] as Record<string, unknown> | undefined
        return (
          `Gate 3 ready for human decision.\n\n` +
          `Surface check: ${surface?.['surface_check'] ?? 'not run'}\n` +
          `Changed files: ${JSON.stringify(surface?.['changed_files'] ?? [])}\n` +
          `Review verdict: ${tally?.['verdict'] ?? 'unknown'} (${tally?.['pass'] ?? 0}-${tally?.['fail'] ?? 0})\n` +
          `Findings: ${JSON.stringify(review?.['findings'] ?? [], null, 2)}\n\n` +
          `Resume with: --step human_checkpoint --result '{"decision":"approved"}' to create PR.`
        )
      },
    },
    {
      kind: 'deterministic',
      id: 'write_state',
      run: (state, runDir, ticket) => {
        const decision = (state.results['human_checkpoint'] as Record<string, unknown> | undefined)?.['decision']
        if (decision !== 'approved' && decision !== 'approved-with-changes') {
          return {
            ok: false,
            halt: {
              reason: 'Tech review rejected by human.',
              human_action_required: 'Fix implementation and re-run gate 3.',
            },
          }
        }
        const runRecordPath = path.join(runDir, `${ticket}.yaml`)
        updateForgeRunYamlAtomic(runRecordPath, { gate: 3, decision, at: now() })
        syncWorkflowProjection(ticket)
        return { ok: true, data: { run_record_updated: runRecordPath } }
      },
    },
  ],

  4: [
    {
      kind: 'pause',
      id: 'e2e_delegation',
      agent: 'E2EPlaywrightWriter',
      buildPrompt: (state) =>
        `Write and run Playwright E2E tests for ticket ${state.ticket} against sprint-rc. Return JSON: { tests: [{ file, name, status: passed|failed|skipped }], all_passed: boolean }`,
      capture_as: 'e2e',
    },
    {
      kind: 'deterministic',
      id: 'check_e2e',
      run: (state) => {
        const e2e = state.results['e2e'] as Record<string, unknown> | undefined
        if (!e2e) return { ok: true, data: { e2e_status: 'not_run' } }
        if (!e2e['all_passed']) {
          return {
            ok: false,
            halt: {
              reason: `E2E tests failed: ${JSON.stringify(e2e['tests'])}`,
              human_action_required:
                '[F] Fix implementation and re-run gate 3, [T] Fix tests, or [S] Skip with documented reason.',
            },
          }
        }
        return { ok: true, data: { e2e_status: 'passed', tests: e2e['tests'] } }
      },
    },
    {
      kind: 'human-checkpoint',
      id: 'human_checkpoint',
      buildMessage: (state) => {
        const e2e = state.results['e2e'] as Record<string, unknown> | undefined
        return (
          `Gate 4 ready for human functional validation.\n\n` +
          `E2E tests: ${JSON.stringify(e2e?.['tests'] ?? [], null, 2)}\n` +
          `All passed: ${e2e?.['all_passed'] ?? false}\n\n` +
          `Resume with: --step human_checkpoint --result '{"decision":"approved"}' to close ticket.`
        )
      },
    },
    {
      kind: 'deterministic',
      id: 'write_state',
      run: (state, runDir, ticket) => {
        const decision = (state.results['human_checkpoint'] as Record<string, unknown> | undefined)?.['decision']
        if (decision !== 'approved') {
          return {
            ok: false,
            halt: {
              reason: 'Functional review rejected by human.',
              human_action_required: 'Fix the issue and re-run gate 4.',
            },
          }
        }
        const runRecordPath = path.join(runDir, `${ticket}.yaml`)
        updateForgeRunYamlAtomic(runRecordPath, { gate: 4, decision, at: now(), completeRun: true })
        syncWorkflowProjection(ticket)
        return { ok: true, data: { run_record_updated: runRecordPath, run_outcome: 'completed' } }
      },
    },
  ],
}

// ---------------------------------------------------------------------------
// Subcommand: run / resume
// ---------------------------------------------------------------------------

function cmdRun(args: {
  ticket: string
  gate: number
  runDir: string
  resumeStep?: string
  resumeResult?: string
}): void {
  const { ticket, gate, runDir } = args
  const steps = GATE_STEPS[gate]
  if (!steps) {
    emit({ ok: false, error: `unknown gate ${gate} (valid: 1, 2, 3, 4)` })
    return
  }

  const sf = stateFilePath(runDir, ticket, gate)
  let state: GateState = loadState(sf) ?? {
    ticket,
    gate,
    step: steps[0].id,
    started_at: now(),
    updated_at: now(),
    results: {},
    completed: false,
    halted: false,
  }

  // Handle resume
  if (args.resumeStep && args.resumeResult !== undefined) {
    let parsed: unknown
    try {
      parsed = JSON.parse(args.resumeResult)
    } catch {
      emit({ ok: false, error: `--result is not valid JSON: ${args.resumeResult}` })
      return
    }
    state.results[args.resumeStep] = parsed as Record<string, unknown>
    state.pending_result = undefined
    // Advance to next step
    const idx = steps.findIndex((s) => s.id === args.resumeStep)
    if (idx !== -1 && idx + 1 < steps.length) {
      state.step = steps[idx + 1].id
    }
    state.updated_at = now()
    saveState(sf, state)
  }

  // Execute steps from current position
  let stepIdx = steps.findIndex((s) => s.id === state.step)
  if (stepIdx === -1) stepIdx = 0

  for (let i = stepIdx; i < steps.length; i++) {
    const step = steps[i]
    state.step = step.id
    state.updated_at = now()
    saveState(sf, state)

    if (step.kind === 'pause') {
      // Already have result from resume?
      if (state.results[step.capture_as] !== undefined) {
        // Result already captured, skip pause
        continue
      }
      // Emit pause
      const out: PauseOutput = {
        ok: true,
        outcome: 'pause',
        gate,
        ticket,
        step: step.id,
        agent: step.agent,
        prompt: step.buildPrompt(state),
        capture_as: step.capture_as,
        state_file: sf,
      }
      emit(out)
      return
    }

    if (step.kind === 'human-checkpoint') {
      // Already have decision from resume?
      if (state.results[step.id] !== undefined) continue
      const out: HaltOutput = {
        ok: true,
        outcome: 'halt',
        gate,
        ticket,
        step: step.id,
        reason: 'human checkpoint',
        human_action_required: step.buildMessage(state),
        state_file: sf,
      }
      emit(out)
      return
    }

    if (step.kind === 'deterministic') {
      // Already ran? (result stored)
      if (state.results[step.id] !== undefined) continue
      const result = step.run(state, runDir, ticket)
      if (!result.ok && result.halt) {
        state.halted = true
        state.halt_reason = result.halt.reason
        state.updated_at = now()
        saveState(sf, state)
        const out: HaltOutput = {
          ok: true,
          outcome: 'halt',
          gate,
          ticket,
          step: step.id,
          reason: result.halt.reason,
          human_action_required: result.halt.human_action_required,
          state_file: sf,
        }
        emit(out)
        return
      }
      state.results[step.id] = result.data ?? {}
      state.updated_at = now()
      saveState(sf, state)
    }
  }

  // All steps done
  state.completed = true
  state.updated_at = now()
  saveState(sf, state)
  const out: DoneOutput = {
    ok: true,
    outcome: 'done',
    gate,
    ticket,
    state_file: sf,
    summary: `Gate ${gate} complete for ${ticket} at ${state.updated_at}`,
  }
  emit(out)
}

// ---------------------------------------------------------------------------
// Subcommand: status
// ---------------------------------------------------------------------------

function cmdStatus(args: { ticket: string; runDir: string }): void {
  const gateStates: Record<number, unknown> = {}
  for (const g of [1, 2, 3, 4]) {
    const sf = stateFilePath(args.runDir, args.ticket, g)
    const s = loadState(sf)
    if (s) gateStates[g] = { step: s.step, completed: s.completed, halted: s.halted, updated_at: s.updated_at }
  }
  emit({
    ok: true,
    outcome: 'done',
    gate: 0,
    ticket: args.ticket,
    state_file: args.runDir,
    summary: JSON.stringify(gateStates),
  } as DoneOutput)
}

// ---------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------

function parseArgs(argv: string[]): void {
  const args = argv.slice(2)
  const sub = args[0]

  if (!sub || sub === '--help') {
    emit({
      ok: false,
      error:
        'usage: forge-gate-run <run|resume|status> --ticket <ID> --gate <1-4> --run-dir <path> [--step <id> --result <json>]',
    })
    return
  }

  let ticket: string | undefined
  let gate: number | undefined
  let runDir: string | undefined
  let resumeStep: string | undefined
  let resumeResult: string | undefined

  for (let i = 1; i < args.length; i++) {
    if (args[i] === '--ticket' && args[i + 1]) ticket = args[++i]
    else if (args[i] === '--gate' && args[i + 1]) gate = parseInt(args[++i], 10)
    else if (args[i] === '--run-dir' && args[i + 1]) runDir = args[++i]
    else if (args[i] === '--step' && args[i + 1]) resumeStep = args[++i]
    else if (args[i] === '--result' && args[i + 1]) resumeResult = args[++i]
  }

  if (!ticket) {
    emit({ ok: false, error: 'missing --ticket' })
    return
  }
  if (!runDir) {
    emit({ ok: false, error: 'missing --run-dir' })
    return
  }

  if (sub === 'status') {
    cmdStatus({ ticket, runDir })
    return
  }

  if (sub === 'run' || sub === 'resume') {
    if (!gate || isNaN(gate)) {
      emit({ ok: false, error: 'missing or invalid --gate (1-4)' })
      return
    }
    cmdRun({ ticket, gate, runDir, resumeStep, resumeResult })
    return
  }

  emit({ ok: false, error: `unknown subcommand '${sub}'` })
}

parseArgs(process.argv)
