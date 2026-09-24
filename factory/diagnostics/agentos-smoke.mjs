/**
 * Diagnostic agentos-smoke — validation de plomberie.
 *
 * But : vérifier que la chaîne fonctionne (registry, oracle, client AgentOS),
 * pas produire du travail utile.
 *
 * Variables d'environnement requises :
 *   FACTORY_NAMESPACE_ID  — identifiant du namespace AgentOS
 *   FACTORY_AGENT         — nom de l'agent à invoquer
 *
 * Pour obtenir ces valeurs :
 *   - FACTORY_NAMESPACE_ID : ID du namespace dans l'UI AgentOS (Settings > Namespaces)
 *   - FACTORY_AGENT        : nom exact de l'agent tel qu'il apparaît dans AgentOS
 */

import { createRun, startPhase, passPhase, failPhase, endRun } from '../lib/registry.mjs'
import { runCommand, snapshotDiff, diffSince } from '../lib/oracle.mjs'
import { createCase, runAgentTurn } from '../lib/agentos.mjs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const REPO_ROOT = join(__dirname, '..', '..')

/**
 * Point d'entrée du diagnostic agentos-smoke.
 *
 * @param {object} log  Logger fourni par `run.mjs`.
 */
export async function run(log) {
  // Validation des variables d'environnement obligatoires
  const namespaceId = process.env.FACTORY_NAMESPACE_ID
  const agentName = process.env.FACTORY_AGENT

  if (!namespaceId || !agentName) {
    const missing = []
    if (!namespaceId) missing.push('FACTORY_NAMESPACE_ID')
    if (!agentName) missing.push('FACTORY_AGENT')
    throw new Error(
      `Variables d'environnement manquantes : ${missing.join(', ')}\n` +
      `  FACTORY_NAMESPACE_ID : ID du namespace AgentOS (Settings > Namespaces)\n` +
      `  FACTORY_AGENT        : nom exact de l'agent dans AgentOS`
    )
  }

  const theRun = createRun('agentos-smoke', { namespaceId })
  let allPass = true

  // -------------------------------------------------------------------------
  // Phase 1 : git-status (code)
  // -------------------------------------------------------------------------
  {
    const phase = startPhase(theRun, 'git-status', 'code')
    log.phaseStart('git-status', 'code')

    const result = runCommand('git rev-parse --abbrev-ref HEAD', { cwd: REPO_ROOT })
    const branch = result.stdout.trim()
    const passed = result.exitCode === 0

    if (passed) {
      passPhase(phase, { branch, exitCode: result.exitCode, commandDurationMs: result.durationMs })
      log.phaseEnd('git-status', 'pass', { branch })
    } else {
      failPhase(phase, {
        exitCode: result.exitCode,
        stderr: result.stderr,
        commandDurationMs: result.durationMs,
      })
      log.phaseEnd('git-status', 'fail', { exitCode: result.exitCode })
      allPass = false
    }
  }

  // -------------------------------------------------------------------------
  // Phase 2 : ping (agent)
  // -------------------------------------------------------------------------
  {
    const phase = startPhase(theRun, 'ping', 'agent')
    log.phaseStart('ping', 'agent')

    const snapshotBefore = snapshotDiff(REPO_ROOT)

    let caseId
    try {
      const newCase = await createCase(namespaceId, 'factory/agentos-smoke \u2014 ping')
      caseId = newCase.id
    } catch (err) {
      failPhase(phase, { agentStatus: 'error', error: String(err) })
      log.phaseEnd('ping', 'fail', { error: String(err) })
      allPass = false
      endRun(theRun, 'fail')
      return { allPass: false, filePath: theRun.filePath }
    }

    const turnResult = await runAgentTurn(
      caseId,
      agentName,
      "R\u00e9ponds uniquement par le mot OK et rien d'autre.",
      { startTimeoutMs: 30 * 1000, workTimeoutMs: 3 * 60 * 1000 }
    )

    const changed = diffSince(snapshotBefore, REPO_ROOT)

    const facts = {
      agentStatus: turnResult.status,
      caseStatus: turnResult.caseStatus,
      agentTurns: turnResult.agentTurns,
      toolCallCount: turnResult.toolCallCount,
      failedToolCalls: turnResult.failedToolCalls,
      killedByBudget: turnResult.killedByBudget,
      anchored: turnResult.anchored,
      filesModified: changed.modified,
      filesUntracked: changed.untracked,
    }

    if (turnResult.status === 'finished') {
      passPhase(phase, facts)
      log.phaseEnd('ping', 'pass', facts)
    } else {
      failPhase(phase, facts)
      log.phaseEnd('ping', 'fail', facts)
      allPass = false
    }
  }

  endRun(theRun, allPass ? 'pass' : 'fail')
  return { allPass, filePath: theRun.filePath }
}
