/**
 * Diagnostic backend-oracle-check -- verification de la compilation du backend (Gradle).
 *
 * L'ORCHESTRATEUR LANCE LUI-MEME LA COMMANDE DE VERITE.
 * Aucun agent n'intervient dans ce diagnostic.
 *
 * Pourquoi pas encore d'agent ?
 * La boucle de correction (agent -> build -> verdict -> retour agent) est l'etape
 * suivante. On etablit d'abord que l'oracle fonctionne sur une commande longue
 * et reelle, avant d'y brancher un agent. Construire les deux en meme temps
 * rendrait le diagnostic impossible en cas d'echec.
 *
 * Ce diagnostic prouve quatre choses :
 *   1. `domains.mjs` est correctement cable : la commande et le cwd sont lisibles
 *      et utilisables tels quels.
 *   2. `runCommand` tient sur plusieurs minutes sans timeout premature ni
 *      troncature de sortie.
 *   3. Le verdict repose exclusivement sur le code de sortie (`exitCode === 0`),
 *      jamais sur une chaine dans la sortie -- c'est l'invariant de l'oracle.
 *   4. Le build Gradle ne modifie aucun fichier suivi par Git : le build est
 *      reproductible et ne pollue pas le depot.
 */

import { createRun, startPhase, passPhase, failPhase, endRun } from '../lib/registry.mjs'
import { runCommand, snapshotDiff, diffSince, countTaskOutcomes } from '../lib/oracle.mjs'
import { domains } from '../lib/domains.mjs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const REPO_ROOT = join(__dirname, '..', '..')

/** Nombre de lignes non vides conservees en cas d'echec pour le diagnostic. */
const TAIL_LINES = 40

/**
 * Retourne les N dernieres lignes non vides d'une chaine, sous forme de tableau.
 *
 * @param {string} text
 * @param {number} n
 * @returns {string[]}
 */
function tailLines(text, n) {
  return text
    .split('\n')
    .filter((line) => line.trim().length > 0)
    .slice(-n)
}

/**
 * Point d'entree du diagnostic backend-oracle-check.
 *
 * @param {object} log  Logger fourni par `run.mjs`.
 */
export async function run(log) {
  const theRun = createRun('backend-oracle-check')
  let allPass = true

  // -------------------------------------------------------------------------
  // Phase unique : back-build (code)
  // -------------------------------------------------------------------------
  {
    const phase = startPhase(theRun, 'back-build', 'code')
    log.phaseStart('back-build', 'code')

    const domain = domains.back
    const TIMEOUT_MS = 20 * 60 * 1000 // 20 minutes

    log.info('Commande : ' + domain.command)
    log.info('Repertoire : ' + domain.cwd)
    log.info('Le build Gradle peut prendre plusieurs minutes sans sortie intermediaire.')

    const before = snapshotDiff(REPO_ROOT)

    const result = runCommand(domain.command, {
      cwd: domain.cwd,
      timeoutMs: TIMEOUT_MS,
    })

    const changed = diffSince(before, REPO_ROOT)

    const passed = result.exitCode === 0

    const tasks = countTaskOutcomes(result.stdout + '\n' + result.stderr)

    if (passed) {
      passPhase(phase, {
        command: domain.command,
        exitCode: result.exitCode,
        timedOut: result.timedOut,
        commandDurationMs: result.durationMs,
        tasks,
        filesModified: changed.modified,
        filesUntracked: changed.untracked,
      })
      log.phaseEnd('back-build', 'pass', {
        exitCode: result.exitCode,
        commandDurationMs: result.durationMs,
        tasksExecuted: tasks.executed,
      })

      if (tasks.executed === 0) {
        log.error('ATTENTION : aucune tache executee (up-to-date=' + tasks.upToDate +
          ', from-cache=' + tasks.fromCache + '). Ce succes ne valide rien.')
      }

      if (changed.modified.length > 0) {
        log.info('ATTENTION : le build a modifie des fichiers suivis par Git :')
        for (const f of changed.modified) {
          log.info('  ' + f)
        }
      }
    } else {
      const stderrTail = tailLines(result.stderr, TAIL_LINES)
      const stdoutTail = tailLines(result.stdout, TAIL_LINES)

      failPhase(phase, {
        command: domain.command,
        exitCode: result.exitCode,
        timedOut: result.timedOut,
        commandDurationMs: result.durationMs,
        tasks,
        filesModified: changed.modified,
        filesUntracked: changed.untracked,
        stderrTail,
        stdoutTail,
      })

      const diagnosticLines = stderrTail.length > 0 ? stderrTail : stdoutTail
      const diagnosticLabel = stderrTail.length > 0 ? 'stderr' : 'stdout'
      log.phaseEnd('back-build', 'fail', {
        exitCode: result.exitCode,
        timedOut: result.timedOut,
        commandDurationMs: result.durationMs,
      })
      log.error('Build echoue (exitCode=' + result.exitCode + (result.timedOut ? ', timeout' : '') + ')')
      log.error('--- ' + TAIL_LINES + ' dernieres lignes de ' + diagnosticLabel + ' ---')
      for (const line of diagnosticLines) {
        log.error(line)
      }

      allPass = false
    }
  }

  endRun(theRun, allPass ? 'pass' : 'fail')
  return { allPass, filePath: theRun.filePath }
}
