/**
 * Gestionnaire d'arrêt gracieux du processus factory.
 *
 * ## Problème résolu (A5/F24)
 * Quand le processus reçoit SIGTERM en plein milieu d'un tour d'agent, deux
 * invariants sont violés :
 *   1. Le case AgentOS reste en vie et continue d'écrire dans le checkout.
 *   2. Le fichier JSONL du run n'a pas de ligne `run_end` — le run est « ouvert ».
 *
 * Ce module corrige les deux via une séquence unique, idempotente :
 *   1. Tuer TOUS les cases actifs via l'API AgentOS (best-effort, chaque échec
 *      est logé mais ne bloque pas le nettoyage des autres cases).
 *   2. Écrire `run_end` avec `status: 'fail'` et le fait durable
 *      `checkoutMayBeIntermediate: true` (si au moins un case était actif).
 *   3. Quitter avec `process.exit(1)`.
 *
 * ## Multi-case
 *
 * Le registre `active-case.mjs` supporte plusieurs cases simultanément
 * (l'éditeur séquentiel + les reviewers parallèles). Sur SIGTERM, chaque case
 * est tué en best-effort via `Promise.allSettled`. Un échec de kill sur un case
 * ne bloque pas le kill des autres, ni l'écriture de `run_end`.
 *
 * ## Idempotence et sécurité aux courses
 *
 * `_shutdownInitiated` est positionné AVANT tout appel async — un second signal
 * reçu pendant le kill ou le flush est ignoré silencieusement.
 *
 * `_completed` est positionné via `markCompleted()` par run.mjs juste avant
 * de retourner au workflow. Si le signal arrive après la complétion normale,
 * `endRun` ne sera pas appelé une deuxième fois.
 *
 * `getCurrentRun()` lit le run courant depuis registry.mjs — renseigné
 * automatiquement par `createRun()` dès le début du workflow.
 *
 * ## Périmètre
 * - SIGTERM uniquement. SIGINT n'est pas géré : les conventions du projet
 *   (pnpm start, pnpm web) ne le requièrent pas, et élargir le périmètre
 *   sans raison explicite violerait la contrainte « ne pas refactoriser le
 *   code adjacent ».
 * - Ce module ne gère pas le dashboard (A6), pas les webhooks, pas les
 *   timeouts internes — uniquement l'arrêt externe via signal.
 *
 * ## Cycle de vie d'un run
 *
 *   run.mjs
 *     ├── initShutdownHandler({ log })   ← enregistre SIGTERM
 *     │
 *     │   workflow.run(log)
 *     │     ├── createRun()               ← publie _currentRun dans registry.mjs
 *     │     │
 *     │     │   runAgentTurn(caseId, ...)
 *     │     │     ├── setActiveCaseId(caseId) ← active-case.mjs
 *     │     │     └── clearActiveCaseId(caseId) ← active-case.mjs (finally)
 *     │     │
 *     │     └── endRun(run, ...)           ← appelé par le workflow
 *     │
 *     └── markCompleted()              ← désarme SIGTERM
 *
 *   Sur SIGTERM (à n'importe quel moment) :
 *     killCase(caseId) pour chaque case actif  ← best-effort, tous en parallèle
 *     endRun(currentRun, 'fail', { ... })       ← si le run est encore ouvert
 *     process.exit(1)
 */

import { endRun, getCurrentRun } from './registry.mjs'
import { killCase } from './agentos.mjs'
import { getActiveCaseIds } from './active-case.mjs'

// ---------------------------------------------------------------------------
// État interne
// ---------------------------------------------------------------------------

/** true dès que le SIGTERM a été reçu ou que la séquence d'arrêt a démarré. */
let _shutdownInitiated = false

/** true dès que le workflow a terminé normalement — désarme le handler. */
let _completed = false

// ---------------------------------------------------------------------------
// API publique
// ---------------------------------------------------------------------------

/**
 * Enregistre le handler SIGTERM.
 * À appeler UNE SEULE FOIS depuis run.mjs, avant le lancement du workflow.
 *
 * @param {{ log?: object }} [options]
 */
export function initShutdownHandler({ log } = {}) {
  _shutdownInitiated = false
  _completed = false

  process.once('SIGTERM', () => handleSignal('SIGTERM', log))
}

/**
 * Marque le workflow comme terminé normalement.
 * Désarme le handler SIGTERM : si le signal arrive après cet appel,
 * `endRun` ne sera plus appelé une seconde fois (le workflow l'a déjà appelé).
 *
 * À appeler depuis run.mjs juste après le retour de `workflow.run()`.
 */
export function markCompleted() {
  _completed = true
}

// ---------------------------------------------------------------------------
// Implémentation interne
// ---------------------------------------------------------------------------

/**
 * Séquence d'arrêt gracieux, déclenchée par SIGTERM.
 * Idempotente : un second appel est ignoré.
 *
 * @param {string} signal
 * @param {object|undefined} log
 */
async function handleSignal(signal, log) {
  // Idempotence : ignorer les signaux répétés et les appels après complétion.
  if (_shutdownInitiated || _completed) return
  _shutdownInitiated = true

  const warn = (msg) => {
    if (log?.error) log.error(msg)
    else console.error(msg)
  }

  warn(`[shutdown] ${signal} reçu — arrêt gracieux en cours.`)

  // --- 1. Tuer tous les cases actifs en best-effort ---
  //
  // Snapshot des caseIds AVANT tout appel async : les cases peuvent se désenregistrer
  // pendant le kill (runAgentTurn finally), mais on veut tuer ceux qui étaient
  // actifs au moment du signal.
  const caseIds = getActiveCaseIds()
  const hadActiveCases = caseIds.length > 0

  if (hadActiveCases) {
    warn(`[shutdown] ${caseIds.length} case(s) actif(s) à tuer : ${caseIds.join(', ')}`)

    // Best-effort : on tue tous en parallèle. Un échec de kill ne bloque pas
    // le nettoyage des autres cases ni l'écriture de run_end.
    const kills = caseIds.map(async (caseId) => {
      try {
        await killCase(caseId)
        warn(`[shutdown] Case ${caseId} tué.`)
      } catch (err) {
        warn(`[shutdown] Erreur lors du kill du case ${caseId} : ${err}`)
      }
    })
    await Promise.allSettled(kills)
  }

  // --- 2. Finaliser le run, exactement une fois ---
  //
  // Le fait `checkoutMayBeIntermediate: true` est DURABLE : il reste dans le
  // fichier JSONL append-only. Il documente que l'agent était peut-être en train
  // d'écrire quand le processus a été arrêté. Toute analyse ultérieure du
  // checkout doit en tenir compte.
  //
  // On n'appelle endRun que si le run est encore ouvert (_completed === false).
  // Si le workflow a déjà appelé endRun (complétion normale), ce bloc est ignoré.
  const run = getCurrentRun()
  if (run && !_completed) {
    try {
      // endRun est synchrone (appendFileSync) — pas de risque de course async ici.
      endRun(run, 'fail', {
        checkoutMayBeIntermediate: hadActiveCases,
        terminatedBySignal: signal,
      })
      warn(`[shutdown] Run finalisé en fail dans ${run.filePath}`)
    } catch (err) {
      warn(`[shutdown] Erreur lors de la finalisation du run : ${err}`)
    }
  }

  warn('[shutdown] Sortie.')
  process.exit(1)
}
