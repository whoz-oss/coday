/**
 * Registre JSONL des runs de l'orchestrateur factory.
 *
 * INVARIANT 1 — Statut `fail` par défaut.
 * Chaque phase est écrite immédiatement en base avec `status: 'fail'`.
 * Elle ne devient `pass` que si `passPhase()` est explicitement appelée.
 * Si l'orchestrateur plante en cours de route, le registre reste honnête :
 * les phases non terminées apparaissent comme échouées, jamais comme succès.
 *
 * INVARIANT 2 — Aucune sortie de LLM dans le registre.
 * On n'y écrit que des faits : noms de phases, statuts, durées, fichiers
 * modifiés, verdicts booléens, codes de sortie, compteurs d'outils.
 * Jamais le texte produit par un agent. Un registre contenant du texte
 * généré n'est pas une preuve : il faudrait faire confiance à ce qu'on mesure.
 *
 * INVARIANT 3 — Les champs du registre ne sont pas réinscriptibles.
 * Les faits fournis par l'appelant sont écrits SOUS un préfixe et ne peuvent
 * jamais écraser `kind`, `name`, `status` ou `durationMs`. Un registre dont
 * les champs peuvent être réécrits par ce qu'il enregistre n'est pas une preuve.
 * (Ce n'est pas théorique : un premier run a vu son `status: 'pass'` remplacé par
 * `'finished'` et sa durée de phase remplacée par la durée d'une commande.)
 *
 * FORMAT : un fichier par run, une ligne JSON par événement, en append pur.
 * Le fichier n'est jamais réécrit après création. Un registre modifiable
 * a posteriori n'est pas une preuve.
 */

import { appendFileSync, mkdirSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { randomBytes } from 'node:crypto'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const RUNS_DIR = join(__dirname, '..', 'runs')

// --------------------------------------------------------------------------
// Registre du run courant (singleton de processus)
// --------------------------------------------------------------------------

/**
 * Run courant du processus, ou null.
 * Renseigné par createRun(), lu par le handler SIGTERM dans shutdown.mjs.
 *
 * @type {{ filePath: string, _startedAt: number }|null}
 */
let _currentRun = null

/**
 * Retourne le run courant, ou null s'il n'y en a pas encore.
 * Utilisé par shutdown.mjs pour écrire endRun sur SIGTERM.
 *
 * @returns {{ filePath: string, _startedAt: number }|null}
 */
export function getCurrentRun() {
  return _currentRun
}

// --------------------------------------------------------------------------
// Helpers
// --------------------------------------------------------------------------

/**
 * Génère un runId de la forme `20240115T143022Z-a3f7`.
 * Le tri alphabétique des noms de fichiers est également le tri chronologique.
 *
 * @returns {string}
 */
function generateRunId() {
  const now = new Date()
  const ts = now
    .toISOString()
    .replace(/[-:]/g, '')
    .replace(/\.\d{3}Z$/, 'Z')
  const suffix = randomBytes(2).toString('hex')
  return `${ts}-${suffix}`
}

/**
 * Ajoute une ligne JSON dans le fichier de run.
 * Ouverture en append à chaque écriture — jamais de réécriture.
 *
 * @param {string} filePath
 * @param {object} record
 */
function appendLine(filePath, record) {
  appendFileSync(filePath, JSON.stringify(record) + '\n', 'utf8')
}

// --------------------------------------------------------------------------
// API publique
// --------------------------------------------------------------------------

/**
 * Crée un nouveau run et écrit la ligne `run_start`.
 *
 * @param {string} workflowName
 * @param {object} [opts]
 * @param {string} [opts.namespaceId]  Namespace AgentOS associé au run (optionnel).
 *   Quand fourni, il est écrit dans `run_start` comme fait durable.
 *   Les runs historiques qui n'ont pas ce champ ont `namespaceId: undefined`
 *   dans leur résumé — ils ne correspondent à aucun namespace demandé.
 * @returns {{ runId: string, filePath: string, _startedAt: number }}
 */
export function createRun(workflowName, opts = {}) {
  mkdirSync(RUNS_DIR, { recursive: true })

  const runId = generateRunId()
  const filePath = join(RUNS_DIR, `${runId}.jsonl`)
  const startedAt = new Date().toISOString()

  const record = {
    kind: 'run_start',
    runId,
    workflow: workflowName,
    startedAt,
  }
  if (opts.namespaceId) record.namespaceId = opts.namespaceId

  appendLine(filePath, record)

  const run = { runId, filePath, _startedAt: Date.now() }
  if (opts.namespaceId) run.namespaceId = opts.namespaceId
  // Publication du run courant pour le handler SIGTERM (shutdown.mjs).
  // Un seul run tourne à la fois par processus : la séquentialité est garantie
  // par l'orchestrateur. _currentRun n'a donc pas besoin de protection concurrente.
  _currentRun = run
  return run
}

/**
 * Démarre une phase et l'écrit immédiatement avec `status: 'fail'`.
 *
 * INVARIANT : le statut par défaut est `fail`. Si l'orchestrateur plante
 * avant que `passPhase` soit appelé, le registre reste honnête.
 *
 * @param {{ filePath: string }} run
 * @param {string} name  Nom de la phase (ex. `'git-status'`).
 * @param {'agent' | 'code'} kind
 * @returns {{ name: string, _startedAt: number, run: object }}
 */
export function startPhase(run, name, kind) {
  const startedAt = new Date().toISOString()

  // Écriture immédiate avec statut `fail` — c'est l'invariant fondamental.
  appendLine(run.filePath, {
    kind: 'phase',
    name,
    phaseKind: kind,
    status: 'fail',
    startedAt,
  })

  return { name, _startedAt: Date.now(), run }
}

/**
 * Écrit la ligne de clôture d'une phase.
 *
 * Les faits de l'appelant sont placés sous la clé `facts`, jamais à la racine :
 * c'est ce qui garantit qu'ils ne peuvent pas écraser les champs du registre.
 * `status` ne prend que deux valeurs, `pass` ou `fail` — tout autre vocabulaire
 * (les statuts d'un tour d'agent, par exemple) appartient aux faits.
 *
 * @param {{ name: string, _startedAt: number, run: object }} phase
 * @param {'pass' | 'fail'} status
 * @param {Record<string, unknown>} facts
 */
function endPhase(phase, status, facts) {
  appendLine(phase.run.filePath, {
    kind: 'phase_end',
    name: phase.name,
    status,
    durationMs: Date.now() - phase._startedAt,
    facts,
  })
}

/**
 * Clôture une phase avec le statut `pass`.
 *
 * @param {{ name: string, _startedAt: number, run: object }} phase
 * @param {Record<string, unknown>} facts  Faits à enregistrer (PAS de texte LLM).
 */
export function passPhase(phase, facts = {}) {
  endPhase(phase, 'pass', facts)
}

/**
 * Clôture une phase avec le statut `fail`.
 *
 * @param {{ name: string, _startedAt: number, run: object }} phase
 * @param {Record<string, unknown>} facts  Faits à enregistrer (PAS de texte LLM).
 */
export function failPhase(phase, facts = {}) {
  endPhase(phase, 'fail', facts)
}

/**
 * Écrit la ligne finale du run.
 *
 * Les faits optionnels de l'appelant sont placés sous la clé `facts`,
 * exactement comme dans `endPhase` — ils ne peuvent donc pas écraser
 * `kind`, `status`, `durationMs` ou `endedAt`.
 *
 * Cas d'usage : le handler SIGTERM passe
 * `{ checkoutMayBeIntermediate: true, terminatedBySignal: 'SIGTERM' }`
 * pour laisser une trace durable dans le JSONL.
 *
 * @param {{ filePath: string, _startedAt: number }} run
 * @param {'pass' | 'fail'} status
 * @param {Record<string, unknown>} [facts]  Faits supplémentaires (PAS de texte LLM).
 */
export function endRun(run, status, facts = {}) {
  const durationMs = Date.now() - run._startedAt
  const record = {
    kind: 'run_end',
    status,
    durationMs,
    endedAt: new Date().toISOString(),
  }
  if (Object.keys(facts).length > 0) {
    record.facts = facts
  }
  appendLine(run.filePath, record)
}
