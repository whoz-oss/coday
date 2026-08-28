/**
 * Tests unitaires pour la terminaison gracieuse (A5/F24).
 *
 * Couvre :
 *   - active-case.mjs : registre multi-case, register/unregister, idempotence,
 *     cas concurrents, API legacy (setActiveCaseId / clearActiveCaseId / getActiveCaseId)
 *   - registry.mjs : getCurrentRun, endRun avec facts
 *   - shutdown.mjs : séquence complète sur SIGTERM, kill de TOUS les cases actifs,
 *     best-effort sur échec de kill, idempotence, race vs completion, un seul run_end
 *   - agentos.mjs (lifecycle A5) : publication du case avant postMessage,
 *     nettoyage sur échec de postMessage, visibilité depuis le handler SIGTERM
 *     pendant que postMessage est en attente (promise différée)
 *
 * Aucune dépendance réseau. Les appels AgentOS (killCase) sont stubés.
 *
 * Usage : node factory/tests/test-shutdown.mjs
 * Code de sortie : 0 = tous les cas passent, 1 = au moins un échec.
 */

import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import {
  registerActiveCase,
  unregisterActiveCase,
  getActiveCaseIds,
  setActiveCaseId,
  clearActiveCaseId,
  getActiveCaseId,
} from '../lib/active-case.mjs'

// ---------------------------------------------------------------------------
// Runner minimal
// ---------------------------------------------------------------------------

let passed = 0
let failed = 0

function expect(name, actual, expected) {
  const ok = JSON.stringify(actual) === JSON.stringify(expected)
  const icon = ok ? '\u2713' : '\u2717'
  console.log(`${icon} ${name}`)
  if (!ok) {
    console.log(`  attendu : ${JSON.stringify(expected)}`)
    console.log(`  obtenu  : ${JSON.stringify(actual)}`)
  }
  if (ok) passed++
  else failed++
}

function expectTrue(name, value) {
  return expect(name, value, true)
}

// ---------------------------------------------------------------------------
// Tests de active-case.mjs — API multi-case
// ---------------------------------------------------------------------------

console.log('\n=== active-case.mjs : API multi-case ===\n')

// Réinitialiser le registre entre les blocs via unregister de tous les cases
function clearRegistry() {
  for (const id of getActiveCaseIds()) {
    unregisterActiveCase(id)
  }
}

{
  // État initial : registre vide
  clearRegistry()
  expect('initial : getActiveCaseIds() = []', getActiveCaseIds(), [])
}

{
  // Enregistrer un case
  clearRegistry()
  registerActiveCase('case-001')
  const ids = getActiveCaseIds()
  expect('register : un case enregistré', ids.length, 1)
  expect('register : bon caseId', ids[0], 'case-001')
  clearRegistry()
}

{
  // Enregistrer plusieurs cases (parallèles)
  clearRegistry()
  registerActiveCase('case-A')
  registerActiveCase('case-B')
  registerActiveCase('case-C')
  const ids = getActiveCaseIds()
  expect('multi-register : 3 cases enregistrés', ids.length, 3)
  expectTrue('multi-register : case-A présent', ids.includes('case-A'))
  expectTrue('multi-register : case-B présent', ids.includes('case-B'))
  expectTrue('multi-register : case-C présent', ids.includes('case-C'))
  clearRegistry()
}

{
  // Idempotence de register : enregistrer deux fois le même caseId
  clearRegistry()
  registerActiveCase('case-dup')
  registerActiveCase('case-dup') // deuxième fois
  expect('register idempotent : toujours 1 case', getActiveCaseIds().length, 1)
  clearRegistry()
}

{
  // Unregister d'un case
  clearRegistry()
  registerActiveCase('case-X')
  registerActiveCase('case-Y')
  unregisterActiveCase('case-X')
  const ids = getActiveCaseIds()
  expect('unregister : 1 case restant', ids.length, 1)
  expect('unregister : case-Y restant', ids[0], 'case-Y')
  clearRegistry()
}

{
  // Idempotence de unregister : retirer un caseId absent est une no-op
  clearRegistry()
  registerActiveCase('case-Z')
  unregisterActiveCase('case-absent') // absent
  expect('unregister idempotent : case-Z toujours là', getActiveCaseIds().length, 1)
  clearRegistry()
}

{
  // Snapshot : getActiveCaseIds() retourne une copie
  clearRegistry()
  registerActiveCase('case-snap-1')
  registerActiveCase('case-snap-2')
  const snap = getActiveCaseIds()
  unregisterActiveCase('case-snap-1') // modifier le registre après le snapshot
  expect('snapshot : la copie n\'est pas affectée', snap.length, 2)
  clearRegistry()
}

{
  // Cas d'éditeur séquentiel + reviewers parallèles : 1 + 2 cases
  clearRegistry()
  registerActiveCase('editor-case')
  registerActiveCase('reviewer-1')
  registerActiveCase('reviewer-2')
  expect('concurrent : 3 cases actifs', getActiveCaseIds().length, 3)

  // Reviewer 1 termine
  unregisterActiveCase('reviewer-1')
  expect('concurrent : 2 cases après fin reviewer-1', getActiveCaseIds().length, 2)

  // Éditeur termine
  unregisterActiveCase('editor-case')
  expect('concurrent : 1 case après fin éditeur', getActiveCaseIds().length, 1)

  // Reviewer 2 termine
  unregisterActiveCase('reviewer-2')
  expect('concurrent : 0 case après tout', getActiveCaseIds().length, 0)
}

// ---------------------------------------------------------------------------
// Tests de active-case.mjs — API legacy (compatibilité agentos.mjs)
// ---------------------------------------------------------------------------

console.log('\n=== active-case.mjs : API legacy ===\n')

{
  // État initial : null
  clearRegistry()
  expect('legacy initial : getActiveCaseId() === null', getActiveCaseId(), null)
}

{
  // Set puis get
  clearRegistry()
  setActiveCaseId('case-123')
  expect('legacy après set : getActiveCaseId() retourne l\'id', getActiveCaseId(), 'case-123')
  clearRegistry()
}

{
  // Clear remet à null
  clearRegistry()
  setActiveCaseId('case-to-clear')
  clearActiveCaseId('case-to-clear')
  expect('legacy après clear : getActiveCaseId() === null', getActiveCaseId(), null)
}

{
  // Plusieurs set : tous enregistrés, getActiveCaseId retourne le premier
  clearRegistry()
  setActiveCaseId('case-aaa')
  setActiveCaseId('case-bbb')
  const ids = getActiveCaseIds()
  expect('legacy multi-set : 2 cases enregistrés', ids.length, 2)
  clearRegistry()
}

{
  // clearActiveCaseId avec null : no-op
  clearRegistry()
  setActiveCaseId('case-safe')
  clearActiveCaseId(null) // ne doit pas planter
  expect('legacy clearActiveCaseId(null) : case-safe toujours là', getActiveCaseIds().length, 1)
  clearRegistry()
}

// ---------------------------------------------------------------------------
// Tests de registry.mjs : getCurrentRun et endRun avec facts
// ---------------------------------------------------------------------------

console.log('\n=== registry.mjs : getCurrentRun + endRun facts ===\n')

const tmpDir = mkdtempSync(join(tmpdir(), 'test-shutdown-registry-'))

try {
  const registry = await import('../lib/registry.mjs')

  {
    const before = registry.getCurrentRun()
    expect(
      'getCurrentRun() retourne null ou un objet run',
      before === null || (typeof before === 'object' && 'filePath' in before),
      true
    )
  }

  {
    const run = registry.createRun('test-shutdown')
    const current = registry.getCurrentRun()
    expect('getCurrentRun() retourne le run après createRun', current === run, true)
    expect('getCurrentRun().filePath === run.filePath', current.filePath, run.filePath)

    registry.endRun(run, 'pass')
    const lines = readFileSync(run.filePath, 'utf8').trim().split('\n')
    const endLine = JSON.parse(lines.at(-1))
    expect('endRun sans facts : kind = run_end', endLine.kind, 'run_end')
    expect('endRun sans facts : status = pass', endLine.status, 'pass')
    expect('endRun sans facts : pas de clé facts', 'facts' in endLine, false)
  }

  {
    const run = registry.createRun('test-shutdown-facts')
    registry.endRun(run, 'fail', {
      checkoutMayBeIntermediate: true,
      terminatedBySignal: 'SIGTERM',
      status: 'SHOULD_NOT_OVERWRITE',
    })
    const lines = readFileSync(run.filePath, 'utf8').trim().split('\n')
    const endLine = JSON.parse(lines.at(-1))

    expect('endRun avec facts : kind = run_end', endLine.kind, 'run_end')
    expect('endRun avec facts : status = fail (non écrasé)', endLine.status, 'fail')
    expect('endRun avec facts : facts.checkoutMayBeIntermediate', endLine.facts?.checkoutMayBeIntermediate, true)
    expect('endRun avec facts : facts.terminatedBySignal', endLine.facts?.terminatedBySignal, 'SIGTERM')
    expect('endRun avec facts : facts.status (passthrough)', endLine.facts?.status, 'SHOULD_NOT_OVERWRITE')
    expect('endRun avec facts : durationMs est un nombre', typeof endLine.durationMs, 'number')
    expect('endRun avec facts : endedAt est une chaîne', typeof endLine.endedAt, 'string')
  }

  {
    const run = registry.createRun('test-shutdown-append')
    registry.endRun(run, 'pass')
    const lines = readFileSync(run.filePath, 'utf8').trim().split('\n')
    expect('JSONL : 2 lignes (run_start + run_end)', lines.length, 2)
    expect('JSONL ligne 0 : kind = run_start', JSON.parse(lines[0]).kind, 'run_start')
    expect('JSONL ligne 1 : kind = run_end', JSON.parse(lines[1]).kind, 'run_end')
  }

} finally {
  rmSync(tmpDir, { recursive: true, force: true })
}

// ---------------------------------------------------------------------------
// Tests de la logique de shutdown multi-case (logique pure, sans vrais signaux)
// ---------------------------------------------------------------------------

console.log('\n=== logique de shutdown multi-case (fonctions pures) ===\n')

/**
 * Simule la séquence d'arrêt gracieux sur plusieurs cases.
 *
 * @param {{ caseIds: string[], runFilePath: string|null, runStartedAt: number,
 *           completed: boolean, failingCaseIds?: string[] }} opts
 */
async function simulateShutdownMulti(opts) {
  const { caseIds, runFilePath, runStartedAt, completed, failingCaseIds = [] } = opts

  const result = {
    killedCaseIds: [],
    killErrors: [],
    endRunCalled: false,
    endRunStatus: null,
    endRunFacts: null,
    endRunCallCount: 0,
    exitCalled: false,
    exitCode: null,
    warnings: [],
  }

  const fakeKillCase = async (id) => {
    if (failingCaseIds.includes(id)) throw new Error(`kill failed for ${id}`)
    result.killedCaseIds.push(id)
  }

  const fakeEndRun = (run, status, facts = {}) => {
    result.endRunCallCount++
    result.endRunCalled = true
    result.endRunStatus = status
    result.endRunFacts = facts
  }

  const fakeExit = (code) => {
    result.exitCalled = true
    result.exitCode = code
  }

  const warn = (msg) => result.warnings.push(msg)

  const signal = 'SIGTERM'

  // Idempotence : si déjà complété, aucune action
  if (completed) return result

  warn(`[shutdown] ${signal} reçu.`)

  const hadActiveCases = caseIds.length > 0

  if (hadActiveCases) {
    const kills = caseIds.map(async (caseId) => {
      try {
        await fakeKillCase(caseId)
        warn(`[shutdown] Case ${caseId} tué.`)
      } catch (err) {
        result.killErrors.push(caseId)
        warn(`[shutdown] Erreur kill ${caseId} : ${err}`)
      }
    })
    await Promise.allSettled(kills)
  }

  const run = runFilePath ? { filePath: runFilePath, _startedAt: runStartedAt } : null
  if (run && !completed) {
    try {
      fakeEndRun(run, 'fail', {
        checkoutMayBeIntermediate: hadActiveCases,
        terminatedBySignal: signal,
      })
    } catch (err) {
      warn(`[shutdown] Erreur endRun : ${err}`)
    }
  }

  warn('[shutdown] Sortie.')
  fakeExit(1)

  return result
}

{
  // Un seul case actif
  const r = await simulateShutdownMulti({
    caseIds: ['case-editor'],
    runFilePath: '/tmp/run.jsonl',
    runStartedAt: Date.now() - 5000,
    completed: false,
  })
  expect('1 case : killed', r.killedCaseIds, ['case-editor'])
  expect('1 case : endRun appelé', r.endRunCalled, true)
  expect('1 case : status = fail', r.endRunStatus, 'fail')
  expect('1 case : checkoutMayBeIntermediate = true', r.endRunFacts?.checkoutMayBeIntermediate, true)
  expect('1 case : exit(1)', r.exitCode, 1)
}

{
  // Plusieurs cases concurrents (1 éditeur + 2 reviewers)
  const r = await simulateShutdownMulti({
    caseIds: ['editor-case', 'reviewer-1', 'reviewer-2'],
    runFilePath: '/tmp/run.jsonl',
    runStartedAt: Date.now() - 2000,
    completed: false,
  })
  // Tous les cases doivent être tués
  expect('3 cases : 3 killed', r.killedCaseIds.length, 3)
  expectTrue('3 cases : editor-case tué', r.killedCaseIds.includes('editor-case'))
  expectTrue('3 cases : reviewer-1 tué', r.killedCaseIds.includes('reviewer-1'))
  expectTrue('3 cases : reviewer-2 tué', r.killedCaseIds.includes('reviewer-2'))
  // Un seul run_end
  expect('3 cases : un seul run_end', r.endRunCallCount, 1)
  expect('3 cases : exit(1)', r.exitCode, 1)
}

{
  // Best-effort : un case échoue à tuer, les autres sont quand même tués
  const r = await simulateShutdownMulti({
    caseIds: ['editor-case', 'reviewer-fail', 'reviewer-ok'],
    runFilePath: '/tmp/run.jsonl',
    runStartedAt: Date.now() - 2000,
    completed: false,
    failingCaseIds: ['reviewer-fail'],
  })
  // Les deux cases qui ne échouent pas sont tués
  expectTrue('best-effort : editor-case tué', r.killedCaseIds.includes('editor-case'))
  expectTrue('best-effort : reviewer-ok tué', r.killedCaseIds.includes('reviewer-ok'))
  // reviewer-fail a échoué mais ça n'a pas bloqué les autres
  expectTrue('best-effort : reviewer-fail a échoué', r.killErrors.includes('reviewer-fail'))
  // endRun quand même appelé
  expect('best-effort : endRun appelé malgré l\'erreur', r.endRunCalled, true)
  // Un seul run_end
  expect('best-effort : un seul run_end', r.endRunCallCount, 1)
  expect('best-effort : exit(1)', r.exitCode, 1)
}

{
  // Aucun case actif
  const r = await simulateShutdownMulti({
    caseIds: [],
    runFilePath: '/tmp/run.jsonl',
    runStartedAt: Date.now() - 1000,
    completed: false,
  })
  expect('0 case : aucun kill', r.killedCaseIds.length, 0)
  expect('0 case : endRun appelé', r.endRunCalled, true)
  expect('0 case : checkoutMayBeIntermediate = false', r.endRunFacts?.checkoutMayBeIntermediate, false)
  expect('0 case : exit(1)', r.exitCode, 1)
}

{
  // Workflow déjà complété : aucune action
  const r = await simulateShutdownMulti({
    caseIds: ['case-late'],
    runFilePath: '/tmp/run.jsonl',
    runStartedAt: Date.now(),
    completed: true,
  })
  expect('déjà complété : aucun kill', r.killedCaseIds.length, 0)
  expect('déjà complété : endRun non appelé', r.endRunCalled, false)
  expect('déjà complété : exit non appelé', r.exitCalled, false)
}

{
  // Idempotence : un second signal ne doit pas produire un deuxième run_end
  const r1 = await simulateShutdownMulti({
    caseIds: ['case-once'],
    runFilePath: '/tmp/run.jsonl',
    runStartedAt: Date.now(),
    completed: false,
  })
  const r2 = await simulateShutdownMulti({
    caseIds: ['case-once'],
    runFilePath: '/tmp/run.jsonl',
    runStartedAt: Date.now(),
    completed: true, // signalé comme déjà initié
  })
  expect('idempotence : premier appel : 1 run_end', r1.endRunCallCount, 1)
  expect('idempotence : deuxième appel : 0 run_end', r2.endRunCallCount, 0)
}

// ---------------------------------------------------------------------------
// Tests shutdown.mjs : exports et signature
// ---------------------------------------------------------------------------

console.log('\n=== shutdown.mjs : exports et signature ===\n')

{
  const shutdown = await import('../lib/shutdown.mjs')

  expect('initShutdownHandler est une fonction', typeof shutdown.initShutdownHandler, 'function')
  expect('markCompleted est une fonction', typeof shutdown.markCompleted, 'function')

  let threw = false
  try {
    shutdown.initShutdownHandler({ log: { error: () => {} } })
  } catch {
    threw = true
  }
  expect('initShutdownHandler({ log }) ne lève pas', threw, false)

  threw = false
  try {
    shutdown.markCompleted()
  } catch {
    threw = true
  }
  expect('markCompleted() ne lève pas', threw, false)
}

// ---------------------------------------------------------------------------
// Intégration JSONL : endRun avec facts écrit dans un vrai fichier
// ---------------------------------------------------------------------------

console.log('\n=== intégration JSONL : endRun sur un vrai run ===\n')

{
  const registry = await import('../lib/registry.mjs')

  const run = registry.createRun('test-shutdown-integration')
  const phase = registry.startPhase(run, 'test-phase', 'code')
  registry.passPhase(phase, { verdict: 'ok' })

  registry.endRun(run, 'fail', {
    checkoutMayBeIntermediate: true,
    terminatedBySignal: 'SIGTERM',
  })

  const lines = readFileSync(run.filePath, 'utf8').trim().split('\n')
  const records = lines.map((l) => JSON.parse(l))

  expect('JSONL intégration : 4 lignes', records.length, 4)
  expect('JSONL intégration ligne 0 : run_start', records[0].kind, 'run_start')
  expect('JSONL intégration ligne 1 : phase (fail par défaut)', records[1].status, 'fail')
  expect('JSONL intégration ligne 2 : phase_end pass', records[2].status, 'pass')
  expect('JSONL intégration ligne 3 : run_end fail', records[3].kind, 'run_end')
  expect('JSONL intégration ligne 3 : status = fail', records[3].status, 'fail')
  expect('JSONL intégration ligne 3 : facts.checkoutMayBeIntermediate', records[3].facts?.checkoutMayBeIntermediate, true)
  expect('JSONL intégration ligne 3 : facts.terminatedBySignal', records[3].facts?.terminatedBySignal, 'SIGTERM')
  expect('JSONL intégration : status racine non écrasé', records[3].status, 'fail')
  expect('JSONL intégration : kind racine non écrasé', records[3].kind, 'run_end')
  expectTrue('JSONL intégration : durationMs >= 0', records[3].durationMs >= 0)
}

// ---------------------------------------------------------------------------
// Tests A5 : publication du case AVANT postMessage dans runAgentTurn
// ---------------------------------------------------------------------------

console.log('\n=== A5 : publication du case avant postMessage (runAgentTurn lifecycle) ===\n')

/**
 * Réimplémentation fidèle du cycle de vie A5 de runAgentTurn,
 * avec listEvents, postMessage et killCase injectés comme stubs.
 */
async function runAgentTurnLifecycle(caseId, stubs) {
  const { listEvents, postMessage, killCase } = stubs

  let killedByPostMessageFailure = false

  setActiveCaseId(caseId)
  try {
    // étape 1 : ancrage (listEvents)
    try {
      await listEvents(caseId)
    } catch (err) {
      return { status: 'listEvents_error', killedByPostMessageFailure }
    }

    // étape 2 : postMessage
    try {
      await postMessage(caseId, 'brief')
    } catch (err) {
      // Échec de postMessage : tenter de tuer le case avant de retourner
      try { await killCase(caseId) } catch { /* ignore */ }
      killedByPostMessageFailure = true
      return { status: 'postMessage_error', killedByPostMessageFailure }
    }

    return { status: 'ok', killedByPostMessageFailure }
  } finally {
    clearActiveCaseId(caseId)
  }
}

// --- Cas A5-1 : setActiveCaseId est appelé AVANT listEvents ---
{
  clearRegistry()

  let resolveListEvents
  const listEventsPromise = new Promise((resolve) => { resolveListEvents = resolve })

  let caseIdSeenDuringListEvents = 'NOT_CHECKED_YET'

  const listEventsStub = async () => {
    caseIdSeenDuringListEvents = getActiveCaseId()
    await listEventsPromise
    return []
  }

  const turnPromise = runAgentTurnLifecycle('case-a5-test-1', {
    listEvents: listEventsStub,
    postMessage: async () => {},
    killCase: async () => {},
  })

  await new Promise((resolve) => setImmediate(resolve))

  expect(
    'A5-1 : getActiveCaseId() = case-a5-test-1 pendant listEvents (avant postMessage)',
    caseIdSeenDuringListEvents,
    'case-a5-test-1'
  )

  resolveListEvents()
  await turnPromise

  expect(
    'A5-1 : getActiveCaseId() = null après la fin (finally exécuté)',
    getActiveCaseId(),
    null
  )
}

// --- Cas A5-2 : setActiveCaseId est appelé AVANT postMessage ---
{
  clearRegistry()

  let resolvePostMessage
  const postMessagePromise = new Promise((resolve) => { resolvePostMessage = resolve })

  let caseIdSeenDuringPostMessage = 'NOT_CHECKED_YET'

  const postMessageStub = async () => {
    caseIdSeenDuringPostMessage = getActiveCaseId()
    await postMessagePromise
  }

  const turnPromise = runAgentTurnLifecycle('case-a5-test-2', {
    listEvents: async () => [],
    postMessage: postMessageStub,
    killCase: async () => {},
  })

  await new Promise((resolve) => setImmediate(resolve))

  expect(
    'A5-2 : getActiveCaseId() = case-a5-test-2 pendant postMessage (fenêtre R1 fermée)',
    caseIdSeenDuringPostMessage,
    'case-a5-test-2'
  )

  resolvePostMessage()
  await turnPromise

  expect(
    'A5-2 : getActiveCaseId() = null après la fin',
    getActiveCaseId(),
    null
  )
}

// --- Cas A5-3 : échec de postMessage → killCase appelé, active-case effacé ---
{
  clearRegistry()

  let killCalledWith = null

  const result = await runAgentTurnLifecycle('case-a5-test-3', {
    listEvents: async () => [],
    postMessage: async () => { throw new Error('network error') },
    killCase: async (id) => { killCalledWith = id },
  })

  expect('A5-3 postMessage échoue : status = postMessage_error', result.status, 'postMessage_error')
  expect('A5-3 postMessage échoue : killCase appelé avec le bon id', killCalledWith, 'case-a5-test-3')
  expect('A5-3 postMessage échoue : killedByPostMessageFailure = true', result.killedByPostMessageFailure, true)
  expect('A5-3 postMessage échoue : getActiveCaseId() = null (finally)', getActiveCaseId(), null)
}

// --- Cas A5-4 : échec de postMessage ET killCase échoue → active-case effacé quand même ---
{
  clearRegistry()

  const result = await runAgentTurnLifecycle('case-a5-test-4', {
    listEvents: async () => [],
    postMessage: async () => { throw new Error('network error') },
    killCase: async () => { throw new Error('kill also failed') },
  })

  expect('A5-4 postMessage + killCase échouent : status = postMessage_error', result.status, 'postMessage_error')
  expect('A5-4 postMessage + killCase échouent : getActiveCaseId() = null (finally)', getActiveCaseId(), null)
}

// --- Cas A5-5 : chemin nominal → active-case effacé à la fin ---
{
  clearRegistry()

  const result = await runAgentTurnLifecycle('case-a5-test-5', {
    listEvents: async () => [],
    postMessage: async () => {},
    killCase: async () => {},
  })

  expect('A5-5 chemin nominal : status = ok', result.status, 'ok')
  expect('A5-5 chemin nominal : getActiveCaseId() = null après la fin', getActiveCaseId(), null)
}

// --- Cas A5-6 : simulation du handler SIGTERM pendant postMessage en vol ---
{
  clearRegistry()

  let resolvePostMessage
  const postMessagePromise = new Promise((resolve) => { resolvePostMessage = resolve })

  const killLog = []

  const postMessageStub = async () => { await postMessagePromise }
  const killCaseStub = async (id) => { killLog.push(id) }

  const turnPromise = runAgentTurnLifecycle('case-a5-sigterm', {
    listEvents: async () => [],
    postMessage: postMessageStub,
    killCase: killCaseStub,
  })

  await new Promise((resolve) => setImmediate(resolve))

  const caseIdAtSignal = getActiveCaseId()
  if (caseIdAtSignal) {
    await killCaseStub(caseIdAtSignal)
  }

  expect(
    'A5-6 SIGTERM pendant postMessage : handler voit case-a5-sigterm',
    caseIdAtSignal,
    'case-a5-sigterm'
  )
  expect(
    'A5-6 SIGTERM pendant postMessage : killCase appelé avec le bon id',
    killLog[0],
    'case-a5-sigterm'
  )

  resolvePostMessage()
  await turnPromise

  expect(
    'A5-6 après déblocage : getActiveCaseId() = null',
    getActiveCaseId(),
    null
  )
}

// --- Cas A5-7 : plusieurs cases concurrents dans le registre pendant SIGTERM ---
{
  clearRegistry()

  registerActiveCase('editor-concurrent')
  registerActiveCase('reviewer-concurrent-1')
  registerActiveCase('reviewer-concurrent-2')

  const caseIds = getActiveCaseIds()

  expect('A5-7 : 3 cases vus par SIGTERM', caseIds.length, 3)
  expectTrue('A5-7 : editor-concurrent vu', caseIds.includes('editor-concurrent'))
  expectTrue('A5-7 : reviewer-concurrent-1 vu', caseIds.includes('reviewer-concurrent-1'))
  expectTrue('A5-7 : reviewer-concurrent-2 vu', caseIds.includes('reviewer-concurrent-2'))

  clearRegistry()
}

// ---------------------------------------------------------------------------
// Résultat
// ---------------------------------------------------------------------------

console.log('')
console.log(`Résultat : ${passed} passé(s), ${failed} échoué(s)`)
process.exit(failed > 0 ? 1 : 0)
