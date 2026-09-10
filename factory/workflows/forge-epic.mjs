import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { resolveForgeRoots } from '../lib/forge-roots.mjs'
import { createEpicRun } from '../lib/forge-ledger.mjs'

/**
 * Minimal vertical Forge workflow. It creates evidence only: no AgentOS case,
 * agent turn, BMAD invocation, Jira request, worktree, or automatic gate pass.
 */
export async function run(log) {
  const raw = process.env.FACTORY_FORGE_RUN_REQUEST
    ?? (process.env.FACTORY_FORGE_RUN_FIXTURE && readFileSync(resolve(process.env.FACTORY_FORGE_RUN_FIXTURE), 'utf8'))
  if (!raw) throw new Error('FACTORY_FORGE_RUN_REQUEST or FACTORY_FORGE_RUN_FIXTURE is required')

  let request
  try { request = JSON.parse(raw) } catch { throw new Error('Forge run request must be valid JSON') }
  const roots = resolveForgeRoots(request.roots)
  const phase = { name: 'create-epic-run' }
  log.phaseStart(phase.name, 'code')
  const result = createEpicRun({ roots, epic: request.epic, stories: request.stories, runId: request.runId })
  log.phaseEnd(phase.name, 'pass', { runId: result.runId, storyRunCount: result.storyRuns.length, gate: 'G1', gateStatus: 'waiting_human' })
  log.info(`Forge EpicRun ${result.runId} created; G1 is waiting for a human decision.`)
  return { allPass: true, filePath: result.filePath }
}
