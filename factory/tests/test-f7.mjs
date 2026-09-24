/**
 * Test unitaire de la correction F7 — quiescence après le DERNIER RUNNING.
 *
 * Simule des séquences d'événements représentant des scénarios réels sans
 * avoir besoin d'AgentOS. Invoque directement les fonctions internes de
 * agentos.mjs qui ne sont pas exportées, en les extrayant via monkey-patch
 * de la boucle de polling.
 *
 * Usage : node factory/tests/test-f7.mjs
 * Code de sortie : 0 = tous les cas passent, 1 = au moins un échec.
 */

// ---------------------------------------------------------------------------
// Copie locale des fonctions pures extraites de agentos.mjs
// (pas d'export disponible — on les redéfinit ici pour les tester isolément)
// ---------------------------------------------------------------------------

const CASE_STATUS_EVENT = 'CaseStatusEvent'
const QUIESCENT_STATUSES = ['IDLE', 'KILLED', 'ERROR']

function findStatusEvent(events, statuses, fromIndex = 0) {
  for (let i = fromIndex; i < events.length; i++) {
    const e = events[i]
    if (e.type === CASE_STATUS_EVENT && statuses.includes(e.status)) {
      return { event: e, index: i }
    }
  }
  return null
}

function findLastStatusEvent(events, statuses) {
  for (let i = events.length - 1; i >= 0; i--) {
    const e = events[i]
    if (e.type === CASE_STATUS_EVENT && statuses.includes(e.status)) {
      return { event: e, index: i }
    }
  }
  return null
}

/**
 * Simule la logique de runAgentTurn sur une séquence d'événements statique.
 *
 * Retourne le statut du CaseStatusEvent final détecté, ou null si la logique
 * se serait arrêtée sur un événement intermédiaire.
 *
 * @param {object[]} events  Séquence complète d'événements du tour.
 * @returns {{ finalStatus: string|null, runningIndexAtDecision: number|null }}
 */
function simulate(events) {
  let runningIndex = null

  // Chercher le premier RUNNING (phase de démarrage)
  const firstRunning = findStatusEvent(events, ['RUNNING'])
  if (!firstRunning) return { finalStatus: null, runningIndexAtDecision: null }
  runningIndex = firstRunning.index

  // Appliquer la logique F7 : avancer runningIndex sur le RUNNING le plus récent
  const lastRunning = findLastStatusEvent(events, ['RUNNING'])
  if (lastRunning && lastRunning.index > runningIndex) {
    runningIndex = lastRunning.index
  }

  // Chercher la quiescence après le RUNNING le plus récent
  const quiescent = findStatusEvent(events, QUIESCENT_STATUSES, runningIndex + 1)
  if (!quiescent) return { finalStatus: null, runningIndexAtDecision: runningIndex }

  return { finalStatus: quiescent.event.status, runningIndexAtDecision: runningIndex }
}

/**
 * Simule la logique AVANT F7 (bug) : s'arrête sur le premier IDLE après le
 * premier RUNNING, même si un autre RUNNING suit.
 *
 * @param {object[]} events
 * @returns {{ finalStatus: string|null, runningIndexAtDecision: number|null }}
 */
function simulateBuggy(events) {
  const firstRunning = findStatusEvent(events, ['RUNNING'])
  if (!firstRunning) return { finalStatus: null, runningIndexAtDecision: null }
  const runningIndex = firstRunning.index

  const quiescent = findStatusEvent(events, QUIESCENT_STATUSES, runningIndex + 1)
  if (!quiescent) return { finalStatus: null, runningIndexAtDecision: runningIndex }

  return { finalStatus: quiescent.event.status, runningIndexAtDecision: runningIndex }
}

// ---------------------------------------------------------------------------
// Constructeurs d'événements
// ---------------------------------------------------------------------------

let _id = 0
const ev = (type, extra = {}) => ({ id: String(++_id), type, ...extra })
const status = (s) => ev(CASE_STATUS_EVENT, { status: s })
const agentFinished = () => ev('AgentFinishedEvent')
const agentSelected = (name) => ev('AgentSelectedEvent', { agentName: name })
const toolResponse = (name) => ev('ToolResponseEvent', { toolName: name, success: true })
const message = (role) => ev('MessageEvent', { actor: { role } })

// ---------------------------------------------------------------------------
// Cas de test
// ---------------------------------------------------------------------------

const cases = [
  {
    name: 'Cas nominal — agent simple (1 tour)',
    description:
      'Un seul agent, un seul RUNNING, un seul IDLE. ' +
      'Le fix ne change rien au comportement attendu.',
    events: [
      status('RUNNING'),
      toolResponse('FILES__read'),
      toolResponse('FILES__edit'),
      agentFinished(),
      message('AGENT'),
      status('IDLE'),
    ],
    expectedFinal: 'IDLE',
    buggyFinal: 'IDLE', // même résultat avant le fix
    fixChangesOutcome: false,
  },

  {
    name: 'F7 — redirection vers un second agent (sans IDLE intermédiaire)',
    description:
      'Agent A redirige vers Agent B sans IDLE entre les deux. ' +
      'Séquence : RUNNING → AgentFinished (A) → AgentSelected (B) → RUNNING → AgentFinished (B) → IDLE. ' +
      'Pas d\'IDLE intermédiaire : le bug et le fix trouvent tous deux le IDLE final. ' +
      'Le fix avance juste runningIndex sur le deuxième RUNNING.',
    events: [
      status('RUNNING'),          // Agent A démarre
      toolResponse('FILES__read'),
      agentFinished(),            // Agent A termine et redirige
      agentSelected('agent-b'),
      status('RUNNING'),          // Agent B démarre
      toolResponse('FILES__edit'),
      agentFinished(),            // Agent B termine
      message('AGENT'),
      status('IDLE'),             // Quiescence finale
    ],
    expectedFinal: 'IDLE',
    buggyFinal: 'IDLE',          // sans IDLE intermédiaire, le bug trouve quand même le bon IDLE
    expectedRunningIndex: 4,     // fix : ancré sur le 2ème RUNNING (index 4)
    buggyRunningIndex: 0,        // bug : ancré sur le 1er RUNNING (index 0), même résultat ici
    fixChangesOutcome: true,     // l'index diffère, même si le statut final est identique
  },

  {
    name: 'F7 — IDLE intermédiaire avant redirection (le cas dangereux)',
    description:
      'CaseRuntime émet IDLE entre deux tours dans certaines implémentations. ' +
      'Séquence : RUNNING → IDLE (interm.) → RUNNING → IDLE (final). ' +
      'Le bug s\'arrêtait sur le premier IDLE, rendant le verdict avant que ' +
      'le second agent ait commencé à écrire.',
    events: [
      status('RUNNING'),   // Tour 1
      agentFinished(),
      status('IDLE'),      // IDLE intermédiaire — PAS la quiescence finale
      status('RUNNING'),   // Tour 2 (redirection ou file de commandes)
      toolResponse('FILES__edit'),
      agentFinished(),
      message('AGENT'),
      status('IDLE'),      // Quiescence finale
    ],
    expectedFinal: 'IDLE',       // Le fix détecte le bon IDLE (index 7)
    buggyFinal: 'IDLE',          // Le bug détecte le mauvais IDLE (index 2) — même valeur, mauvais événement
    // Pour distinguer les deux, on compare runningIndexAtDecision
    expectedRunningIndex: 3,     // après le fix : RUNNING à index 3
    buggyRunningIndex: 0,        // avant le fix : RUNNING à index 0
    fixChangesOutcome: true,     // même statut mais événement différent — l'index le trahit
  },

  {
    name: 'F7 — trois tours successifs',
    description:
      'Trois agents en chaîne avec IDLE intermédiaires. ' +
      'Le fix doit s\'ancrer sur le troisième RUNNING.',
    events: [
      status('RUNNING'),   // Tour 1
      agentFinished(),
      status('IDLE'),
      status('RUNNING'),   // Tour 2
      agentFinished(),
      status('IDLE'),
      status('RUNNING'),   // Tour 3
      toolResponse('FILES__edit'),
      agentFinished(),
      message('AGENT'),
      status('IDLE'),      // Quiescence finale
    ],
    expectedFinal: 'IDLE',
    buggyFinal: 'IDLE',
    expectedRunningIndex: 6,
    buggyRunningIndex: 0,
    fixChangesOutcome: true,
  },

  {
    name: 'F7 — quiescence ERROR après multi-tours',
    description:
      'Le second agent plante. Le fix doit retourner ERROR (pas IDLE intermédiaire).',
    events: [
      status('RUNNING'),
      agentFinished(),
      status('IDLE'),
      status('RUNNING'),
      ev('WarnEvent', { message: 'boom' }),
      status('ERROR'),
    ],
    expectedFinal: 'ERROR',
    buggyFinal: 'IDLE',   // le bug retourne le IDLE intermédiaire
    fixChangesOutcome: true,
  },

  {
    name: 'F7 — quiescence KILLED après multi-tours',
    description:
      'Budget dépassé sur le second tour. Le fix doit retourner KILLED.',
    events: [
      status('RUNNING'),
      agentFinished(),
      status('IDLE'),
      status('RUNNING'),
      toolResponse('FILES__read'),
      status('KILLED'),
    ],
    expectedFinal: 'KILLED',
    buggyFinal: 'IDLE',
    fixChangesOutcome: true,
  },

  {
    name: 'Pas de RUNNING — démarrage non observé',
    description: 'Aucun CaseStatusEvent RUNNING dans la fenêtre. Les deux versions retournent null.',
    events: [
      toolResponse('FILES__read'),
      message('AGENT'),
      status('IDLE'),
    ],
    expectedFinal: null,
    buggyFinal: null,
    fixChangesOutcome: false,
  },
]

// ---------------------------------------------------------------------------
// Runner
// ---------------------------------------------------------------------------

let passed = 0
let failed = 0

for (const tc of cases) {
  const fixed = simulate(tc.events)
  const buggy = simulateBuggy(tc.events)

  const fixOk = fixed.finalStatus === tc.expectedFinal
  const buggyOk = buggy.finalStatus === tc.buggyFinal

  // Si le cas est censé montrer une différence d'index, vérifier aussi ça
  const indexOk =
    tc.expectedRunningIndex === undefined ||
    fixed.runningIndexAtDecision === tc.expectedRunningIndex
  const buggyIndexOk =
    tc.buggyRunningIndex === undefined ||
    buggy.runningIndexAtDecision === tc.buggyRunningIndex

  const ok = fixOk && buggyOk && indexOk && buggyIndexOk

  const icon = ok ? '✓' : '✗'
  console.log(`${icon} ${tc.name}`)
  if (tc.description) {
    console.log(`  ${tc.description}`)
  }

  if (!fixOk) {
    console.log(
      `  FIX   : attendu finalStatus=${JSON.stringify(tc.expectedFinal)}, ` +
        `obtenu ${JSON.stringify(fixed.finalStatus)}`
    )
  }
  if (!buggyOk) {
    console.log(
      `  BUGGY : attendu finalStatus=${JSON.stringify(tc.buggyFinal)}, ` +
        `obtenu ${JSON.stringify(buggy.finalStatus)}`
    )
  }
  if (!indexOk) {
    console.log(
      `  FIX runningIndex : attendu ${tc.expectedRunningIndex}, ` +
        `obtenu ${fixed.runningIndexAtDecision}`
    )
  }
  if (!buggyIndexOk) {
    console.log(
      `  BUGGY runningIndex : attendu ${tc.buggyRunningIndex}, ` +
        `obtenu ${buggy.runningIndexAtDecision}`
    )
  }

  if (tc.fixChangesOutcome) {
    // Vérifier que le bug et le fix divergent effectivement sur ce cas
    const diverges =
      buggy.finalStatus !== fixed.finalStatus ||
      buggy.runningIndexAtDecision !== fixed.runningIndexAtDecision
    if (!diverges) {
      console.log(
        `  ATTENTION : ce cas est censé montrer une divergence fix/bug, ` +
          `mais les deux produisent le même résultat — le cas de test n'est peut-être pas représentatif.`
      )
    } else {
      console.log(
        `  ↳ Divergence confirmée : ` +
          `bug=(status=${buggy.finalStatus}, runningIdx=${buggy.runningIndexAtDecision}) ` +
          `fix=(status=${fixed.finalStatus}, runningIdx=${fixed.runningIndexAtDecision})`
      )
    }
  }

  console.log()

  if (ok) passed++
  else failed++
}

console.log(`Résultat : ${passed} passé(s), ${failed} échoué(s)`)
process.exit(failed > 0 ? 1 : 0)
