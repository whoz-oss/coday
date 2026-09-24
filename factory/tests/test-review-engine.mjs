/**
 * Tests unitaires pour factory/lib/review-engine.mjs.
 *
 * Toutes les fonctions AgentOS sont injectées via des fakes.
 * Aucun I/O, aucun appel réseau, aucun agent reviewer réel.
 *
 * Organisation :
 *   Bloc A — Validation d'entrée (no reviewers, IDs dupliqués)
 *   Bloc B — Préflight : agent invalide, intégrations mutantes, FILE_ACCESS non readOnly
 *   Bloc C — Exécution nominale : un reviewer, approbation
 *   Bloc D — Exécution parallèle : ordre déterministe, même snapshot
 *   Bloc E — Statuts d'agent : timeout, pending_question, case_error
 *   Bloc F — Parsing : pas de JSON, JSON invalide, résultat invalide
 *   Bloc G — Hash mismatch
 *   Bloc H — Cleanup : cases tués sur échec
 *   Bloc I — Étanchéité des faits : prose absente, champs racine absents
 *   Bloc J — Verdict agrégé : véto, request-changes, approve
 *   Bloc K — Registre global SIGTERM : active-case.mjs exposé aux reviewers
 *
 * Usage : node factory/tests/test-review-engine.mjs
 * Code de sortie : 0 = tous passent, 1 = au moins un échec.
 */

import { runReview, ENGINE_ERROR_CODES } from '../lib/review-engine.mjs'
import { PARSE_ERROR_CODES } from '../lib/review.mjs'
import {
  getActiveCaseIds,
  unregisterActiveCase,
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

function expectTrue(name, value) { expect(name, value, true) }
function expectFalse(name, value) { expect(name, value, false) }

// Sentinel de prose LLM
const PROSE_SENTINEL = 'LLM-generated prose that must never reach JSONL registry'

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

/** SubjectDescriptor standard. */
const SUBJECT = Object.freeze({
  subjectType: /** @type {'implementation'} */ ('implementation'),
  path: 'src/user.ts',
  hash: 'sha256:deadbeef',
  content: 'export class User { name: string }',
})

/** ReviewConfig standard. */
const CONFIG = {
  subjectType: /** @type {'implementation'} */ ('implementation'),
  axes: [
    { id: 'correctness', weight: 2, veto: true },
    { id: 'code-quality', weight: 1, veto: false },
  ],
}

/** RevisionMeta standard. */
const REV_META = { revision: 1, attempt: 1, maxRevisions: 2, maxAttempts: 3 }

/** ReviewerDef standard. */
const REVIEWER_A = {
  reviewerId: 'reviewer-a',
  agentName: 'factory-reviewer-a',
  axes: CONFIG.axes,
}

const REVIEWER_B = {
  reviewerId: 'reviewer-b',
  agentName: 'factory-reviewer-b',
  axes: CONFIG.axes,
}

/** Sortie JSON valide d'un reviewer (approve). */
const VALID_OUTPUT_APPROVE = JSON.stringify({
  reviewerId: 'reviewer-a',
  subjectType: 'implementation',
  verdict: 'approve',
  scores: [
    { axisId: 'correctness', score: 4 },
    { axisId: 'code-quality', score: 4 },
  ],
  findings: [],
  artifactDescriptor: { path: 'src/user.ts', hash: 'sha256:deadbeef' },
})

/** Sortie JSON valide pour reviewer-b (approve). */
const VALID_OUTPUT_B_APPROVE = JSON.stringify({
  reviewerId: 'reviewer-b',
  subjectType: 'implementation',
  verdict: 'approve',
  scores: [
    { axisId: 'correctness', score: 5 },
    { axisId: 'code-quality', score: 3 },
  ],
  findings: [],
  artifactDescriptor: { path: 'src/user.ts', hash: 'sha256:deadbeef' },
})

/** Sortie JSON valide d'un reviewer (request-changes avec finding). */
const VALID_OUTPUT_REQUEST_CHANGES = JSON.stringify({
  reviewerId: 'reviewer-a',
  subjectType: 'implementation',
  verdict: 'request-changes',
  scores: [{ axisId: 'correctness', score: 2 }, { axisId: 'code-quality', score: 3 }],
  findings: [{
    severity: 'major',
    axisId: 'correctness',
    title: 'Missing null check',
    evidence: 'Line 5 accesses name without guard',
    recommendation: 'Add null guard',
  }],
  artifactDescriptor: { path: 'src/user.ts', hash: 'sha256:deadbeef' },
})

/** Sortie JSON avec finding blocking sur axe veto. */
const VALID_OUTPUT_VETO = JSON.stringify({
  reviewerId: 'reviewer-a',
  subjectType: 'implementation',
  verdict: 'reject',
  scores: [{ axisId: 'correctness', score: 1 }, { axisId: 'code-quality', score: 3 }],
  findings: [{
    severity: 'blocking',
    axisId: 'correctness',
    title: 'SQL injection',
    evidence: 'User input in query',
    recommendation: 'Use parameterized queries',
    file: 'src/user.ts',
    line: 10,
  }],
  artifactDescriptor: { path: 'src/user.ts', hash: 'sha256:deadbeef' },
})

// ---------------------------------------------------------------------------
// Factories de fakes AgentOps
// ---------------------------------------------------------------------------

/**
 * Crée un AgentOps fake minimal.
 * Chaque reviewer reçoit la même sortie (message).
 *
 * @param {string} message  Texte renvoyé par le tour d'agent.
 * @param {string} [turnStatus]  Statut du tour ('finished' par défaut).
 * @param {object} [overrides]  Surcharges partielles.
 */
function makeOps(message, turnStatus = 'finished', overrides = {}) {
  let caseCounter = 0
  const createdCases = []
  const killedCases = []
  const briefsReceived = []
  const agentsUsed = []

  const ops = {
    _createdCases: createdCases,
    _killedCases: killedCases,
    _briefsReceived: briefsReceived,
    _agentsUsed: agentsUsed,

    async createCase(namespaceId, title) {
      const id = `case-${++caseCounter}`
      createdCases.push({ id, namespaceId, title })
      return { id }
    },

    async runAgentTurn(caseId, agentName, brief, opts) {
      briefsReceived.push({ caseId, agentName, brief })
      agentsUsed.push(agentName)
      return {
        status: turnStatus,
        caseStatus: turnStatus === 'finished' ? 'IDLE' : null,
        message,
        events: [],
        killedByBudget: false,
        anchored: true,
      }
    },

    async killCase(caseId) {
      killedCases.push(caseId)
    },

    async preflightAgent(namespaceId, agentName) {
      return {
        ok: true,
        reason: null,
        agent: { name: agentName, enabled: true, subAgents: [], integrations: {} },
      }
    },

    async listIntegrations(namespaceId) {
      return []
    },

    ...overrides,
  }
  return ops
}

/**
 * Crée un AgentOps où chaque reviewer reçoit un message différent selon son agentName.
 *
 * @param {Record<string, string>} messagesByAgent
 * @param {Record<string, string>} [statusByAgent]
 */
function makeOpsPerAgent(messagesByAgent, statusByAgent = {}) {
  let caseCounter = 0
  const createdCases = []
  const killedCases = []
  const briefsReceived = []

  return {
    _createdCases: createdCases,
    _killedCases: killedCases,
    _briefsReceived: briefsReceived,

    async createCase(namespaceId, title) {
      const id = `case-${++caseCounter}`
      createdCases.push({ id, title })
      return { id }
    },

    async runAgentTurn(caseId, agentName, brief, opts) {
      briefsReceived.push({ caseId, agentName, brief })
      const message = messagesByAgent[agentName] ?? ''
      const status = statusByAgent[agentName] ?? 'finished'
      return {
        status,
        caseStatus: status === 'finished' ? 'IDLE' : null,
        message,
        events: [],
        killedByBudget: false,
        anchored: true,
      }
    },

    async killCase(caseId) {
      killedCases.push(caseId)
    },

    async preflightAgent(namespaceId, agentName) {
      return {
        ok: true,
        reason: null,
        agent: { name: agentName, enabled: true, subAgents: [], integrations: {} },
      }
    },

    async listIntegrations(namespaceId) { return [] },
  }
}

/** Paramètres de base pour runReview. */
function baseParams(agentOps, overrides = {}) {
  return {
    namespaceId: 'ns-test',
    subject: SUBJECT,
    reviewers: [REVIEWER_A],
    config: CONFIG,
    revisionMeta: REV_META,
    agentOps,
    ...overrides,
  }
}

// ---------------------------------------------------------------------------
// Bloc A — Validation d'entrée
// ---------------------------------------------------------------------------

console.log('\n=== A : Validation d\'entrée ===\n')

{
  // Aucun reviewer
  const ops = makeOps('')
  const r = await runReview(baseParams(ops, { reviewers: [] }))
  expect('A1 : ok=false si aucun reviewer', r.ok, false)
  expect('A1 : errorCode NO_REVIEWERS', r.errorCode, ENGINE_ERROR_CODES.NO_REVIEWERS)
  expect('A1 : facts null', r.facts, null)
}

{
  // IDs dupliqués
  const ops = makeOps('')
  const r = await runReview(baseParams(ops, {
    reviewers: [
      { reviewerId: 'dup', agentName: 'agent-a', axes: CONFIG.axes },
      { reviewerId: 'dup', agentName: 'agent-b', axes: CONFIG.axes },
    ],
  }))
  expect('A2 : ok=false sur IDs dupliqués', r.ok, false)
  expect('A2 : errorCode DUPLICATE_REVIEWER_IDS', r.errorCode, ENGINE_ERROR_CODES.DUPLICATE_REVIEWER_IDS)
}

// ---------------------------------------------------------------------------
// Bloc B — Préflight
// ---------------------------------------------------------------------------

console.log('\n=== B : Préflight ===\n')

{
  // Agent inexistant
  const ops = makeOps('', 'finished', {
    async preflightAgent() {
      return { ok: false, reason: 'Agent not found', agent: null }
    },
  })
  const r = await runReview(baseParams(ops))
  expect('B1 : ok=false sur agent inexistant', r.ok, false)
  expect('B1 : errorCode PREFLIGHT_FAILED', r.errorCode, ENGINE_ERROR_CODES.PREFLIGHT_FAILED)
}

{
  // subAgents non vide
  const ops = makeOps('', 'finished', {
    async preflightAgent(ns, agentName) {
      return {
        ok: false,
        reason: `Agent ${agentName} déclare subAgents`,
        agent: { name: agentName, enabled: true, subAgents: ['other'] },
      }
    },
  })
  const r = await runReview(baseParams(ops))
  expect('B2 : ok=false sur subAgents non vide', r.ok, false)
  expect('B2 : errorCode PREFLIGHT_FAILED', r.errorCode, ENGINE_ERROR_CODES.PREFLIGHT_FAILED)
}

{
  // Intégration BASH
  const ops = makeOps('', 'finished', {
    async preflightAgent(ns, agentName) {
      return {
        ok: true,
        reason: null,
        agent: { name: agentName, enabled: true, subAgents: [], integrations: { BASH_TOOL: {} } },
      }
    },
    async listIntegrations() {
      return [{ name: 'BASH_TOOL', integrationType: 'BASH', parameters: {} }]
    },
  })
  const r = await runReview(baseParams(ops))
  expect('B3 : ok=false sur intégration BASH', r.ok, false)
  expect('B3 : errorCode PREFLIGHT_FAILED', r.errorCode, ENGINE_ERROR_CODES.PREFLIGHT_FAILED)
}

{
  // Intégration MCP_STDIO
  const ops = makeOps('', 'finished', {
    async preflightAgent(ns, agentName) {
      return {
        ok: true,
        reason: null,
        agent: { name: agentName, enabled: true, subAgents: [], integrations: { MCP_TOOL: {} } },
      }
    },
    async listIntegrations() {
      return [{ name: 'MCP_TOOL', integrationType: 'MCP_STDIO', parameters: {} }]
    },
  })
  const r = await runReview(baseParams(ops))
  expect('B4 : ok=false sur intégration MCP_STDIO', r.ok, false)
  expect('B4 : errorCode PREFLIGHT_FAILED', r.errorCode, ENGINE_ERROR_CODES.PREFLIGHT_FAILED)
}

{
  // FILE_ACCESS non readOnly
  const ops = makeOps('', 'finished', {
    async preflightAgent(ns, agentName) {
      return {
        ok: true,
        reason: null,
        agent: { name: agentName, enabled: true, subAgents: [], integrations: { FILES: {} } },
      }
    },
    async listIntegrations() {
      return [{ name: 'FILES', integrationType: 'FILE_ACCESS', parameters: { rootPath: '/repo', readOnly: false } }]
    },
  })
  const r = await runReview(baseParams(ops))
  expect('B5 : ok=false sur FILE_ACCESS non readOnly', r.ok, false)
  expect('B5 : errorCode PREFLIGHT_FAILED', r.errorCode, ENGINE_ERROR_CODES.PREFLIGHT_FAILED)
}

{
  // FILE_ACCESS readOnly=true : admis
  const ops = makeOps(VALID_OUTPUT_APPROVE, 'finished', {
    async preflightAgent(ns, agentName) {
      return {
        ok: true,
        reason: null,
        agent: { name: agentName, enabled: true, subAgents: [], integrations: { FILES_RO: {} } },
      }
    },
    async listIntegrations() {
      return [{ name: 'FILES_RO', integrationType: 'FILE_ACCESS', parameters: { rootPath: '/repo', readOnly: true } }]
    },
  })
  const r = await runReview(baseParams(ops))
  expect('B6 : ok=true sur FILE_ACCESS readOnly', r.ok, true)
}

{
  // Intégration introuvable via REST — fail-closed
  const ops = makeOps('', 'finished', {
    async preflightAgent(ns, agentName) {
      return {
        ok: true,
        reason: null,
        agent: { name: agentName, enabled: true, subAgents: [], integrations: { MYSTERY: {} } },
      }
    },
    async listIntegrations() {
      return [] // MYSTERY n'apparaît pas
    },
  })
  const r = await runReview(baseParams(ops))
  expect('B7 : ok=false sur intégration introuvable (fail-closed)', r.ok, false)
  expect('B7 : errorCode PREFLIGHT_FAILED', r.errorCode, ENGINE_ERROR_CODES.PREFLIGHT_FAILED)
}

// ---------------------------------------------------------------------------
// Bloc C — Exécution nominale
// ---------------------------------------------------------------------------

console.log('\n=== C : Exécution nominale ===\n')

{
  const ops = makeOps(VALID_OUTPUT_APPROVE)
  const r = await runReview(baseParams(ops))
  expectTrue('C1 : ok=true sur approbation', r.ok)
  expect('C1 : verdict approve', r.aggregate?.verdict, 'approve')
  expect('C1 : reviewerResults.length', r.reviewerResults.length, 1)
  expect('C1 : reviewerResults[0].ok', r.reviewerResults[0].ok, true)
  expect('C1 : reviewerResults[0].reviewerId', r.reviewerResults[0].reviewerId, 'reviewer-a')
  expect('C1 : errorCode null', r.errorCode, null)
  // Un case créé
  expect('C1 : un case créé', ops._createdCases.length, 1)
  // Aucun case tué (succès)
  expect('C1 : aucun case tué', ops._killedCases.length, 0)
}

{
  // rawOutputs contient la prose, facts ne la contient pas
  const ops = makeOps(VALID_OUTPUT_APPROVE)
  const r = await runReview(baseParams(ops))
  expectTrue('C2 : rawOutputs contient la réponse', r.rawOutputs['reviewer-a'] !== null)
  // facts ne contient pas de prose
  const factsJson = JSON.stringify(r.facts)
  expectFalse('C2 : facts ne contient pas de contenu de sujet', factsJson.includes(SUBJECT.content))
}

{
  // request-changes
  const ops = makeOps(VALID_OUTPUT_REQUEST_CHANGES)
  const r = await runReview(baseParams(ops))
  expectTrue('C3 : ok=true sur request-changes', r.ok)
  expect('C3 : verdict request-changes', r.aggregate?.verdict, 'request-changes')
  expect('C3 : findingCounts.major', r.aggregate?.findingCounts.major, 1)
}

// ---------------------------------------------------------------------------
// Bloc D — Parallélisme et ordre déterministe
// ---------------------------------------------------------------------------

console.log('\n=== D : Parallélisme et ordre déterministe ===\n')

{
  // Deux reviewers, même snapshot (même brief envoyé aux deux)
  const ops = makeOpsPerAgent({
    'factory-reviewer-a': VALID_OUTPUT_APPROVE,
    'factory-reviewer-b': VALID_OUTPUT_B_APPROVE,
  })
  const r = await runReview(baseParams(ops, { reviewers: [REVIEWER_A, REVIEWER_B] }))
  expectTrue('D1 : ok=true avec deux reviewers', r.ok)
  expect('D1 : deux reviewerResults', r.reviewerResults.length, 2)
  // Ordre déterministe : A en premier, B en second
  expect('D1 : reviewerResults[0].reviewerId', r.reviewerResults[0].reviewerId, 'reviewer-a')
  expect('D1 : reviewerResults[1].reviewerId', r.reviewerResults[1].reviewerId, 'reviewer-b')
  expect('D1 : deux cases créés', ops._createdCases.length, 2)
}

{
  // Même snapshot : les deux reviewers reçoivent le même brief
  const ops = makeOpsPerAgent({
    'factory-reviewer-a': VALID_OUTPUT_APPROVE,
    'factory-reviewer-b': VALID_OUTPUT_B_APPROVE,
  })
  await runReview(baseParams(ops, { reviewers: [REVIEWER_A, REVIEWER_B] }))
  const briefA = ops._briefsReceived.find((b) => b.agentName === 'factory-reviewer-a')?.brief
  const briefB = ops._briefsReceived.find((b) => b.agentName === 'factory-reviewer-b')?.brief
  expect('D2 : brief identique pour les deux reviewers', briefA, briefB)
  // Le brief contient le hash
  expectTrue('D2 : brief contient le hash', briefA?.includes(SUBJECT.hash) ?? false)
  // Le brief contient le path
  expectTrue('D2 : brief contient le path', briefA?.includes(SUBJECT.path) ?? false)
}

{
  // Ordre déterministe même si B termine avant A (simulé par délai)
  // Dans notre fake synchrone, l'ordre de Promise.allSettled suit l'ordre d'entrée.
  // Ce test vérifie que le résultat est indexé par position, pas par temps d'arrivée.
  const ops = makeOpsPerAgent({
    'factory-reviewer-a': VALID_OUTPUT_APPROVE,
    'factory-reviewer-b': VALID_OUTPUT_B_APPROVE,
  })
  const r = await runReview(baseParams(ops, { reviewers: [REVIEWER_A, REVIEWER_B] }))
  // reviewerIds dans l'agrégat : ordre défini par l'ordre d'entrée
  expect('D3 : reviewerIds ordonnés', r.aggregate?.reviewerIds, ['reviewer-a', 'reviewer-b'])
}

{
  // Le contenu de l'artefact ne doit pas être dans les faits
  const ops = makeOpsPerAgent({
    'factory-reviewer-a': VALID_OUTPUT_APPROVE,
    'factory-reviewer-b': VALID_OUTPUT_B_APPROVE,
  })
  const r = await runReview(baseParams(ops, { reviewers: [REVIEWER_A, REVIEWER_B] }))
  const factsJson = JSON.stringify(r.facts)
  expectFalse('D4 : contenu artefact absent des faits', factsJson.includes(SUBJECT.content))
  // Le hash est dans les faits (artifactHashes)
  expectTrue('D4 : hash présent dans les faits', factsJson.includes(SUBJECT.hash))
  // Le path est dans les faits (artifactPaths)
  expectTrue('D4 : path présent dans les faits', factsJson.includes(SUBJECT.path))
}

// ---------------------------------------------------------------------------
// Bloc E — Statuts d'agent
// ---------------------------------------------------------------------------

console.log('\n=== E : Statuts d\'agent ===\n')

{
  // Timeout
  const ops = makeOps('', 'work_timeout')
  const r = await runReview(baseParams(ops))
  expect('E1 : ok=false sur work_timeout', r.ok, false)
  expect('E1 : errorCode REVIEWER_TIMEOUT', r.reviewerResults[0].errorCode, ENGINE_ERROR_CODES.REVIEWER_TIMEOUT)
}

{
  // start_timeout
  const ops = makeOps('', 'start_timeout')
  const r = await runReview(baseParams(ops))
  expect('E2 : ok=false sur start_timeout', r.ok, false)
  expect('E2 : errorCode REVIEWER_TIMEOUT', r.reviewerResults[0].errorCode, ENGINE_ERROR_CODES.REVIEWER_TIMEOUT)
}

{
  // pending_question
  const ops = makeOps('', 'pending_question')
  const r = await runReview(baseParams(ops))
  expect('E3 : ok=false sur pending_question', r.ok, false)
  expect('E3 : errorCode REVIEWER_PENDING', r.reviewerResults[0].errorCode, ENGINE_ERROR_CODES.REVIEWER_PENDING)
}

{
  // case_error
  const ops = makeOps('', 'case_error')
  const r = await runReview(baseParams(ops))
  expect('E4 : ok=false sur case_error', r.ok, false)
  expect('E4 : errorCode REVIEWER_AGENT_ERROR', r.reviewerResults[0].errorCode, ENGINE_ERROR_CODES.REVIEWER_AGENT_ERROR)
}

{
  // killed
  const ops = makeOps('', 'killed')
  const r = await runReview(baseParams(ops))
  expect('E5 : ok=false sur killed', r.ok, false)
  expect('E5 : errorCode REVIEWER_AGENT_ERROR', r.reviewerResults[0].errorCode, ENGINE_ERROR_CODES.REVIEWER_AGENT_ERROR)
}

// ---------------------------------------------------------------------------
// Bloc F — Parsing
// ---------------------------------------------------------------------------

console.log('\n=== F : Parsing ===\n')

{
  // Pas de JSON dans la réponse
  const ops = makeOps('Je suis un reviewer et voilà mon avis sans JSON.')
  const r = await runReview(baseParams(ops))
  expect('F1 : ok=false sur pas de JSON', r.ok, false)
  expect('F1 : errorCode REVIEWER_NO_JSON', r.reviewerResults[0].errorCode, ENGINE_ERROR_CODES.REVIEWER_NO_JSON)
}

{
  // JSON syntaxiquement invalide
  const ops = makeOps('```json\n{ broken json \n```')
  const r = await runReview(baseParams(ops))
  expect('F2 : ok=false sur JSON invalide', r.ok, false)
  expect('F2 : errorCode REVIEWER_INVALID_JSON', r.reviewerResults[0].errorCode, ENGINE_ERROR_CODES.REVIEWER_INVALID_JSON)
}

{
  // JSON valide mais structure invalide (verdict inconnu)
  const badOutput = JSON.stringify({ reviewerId: 'reviewer-a', subjectType: 'implementation', verdict: 'maybe', scores: [], findings: [] })
  const ops = makeOps(badOutput)
  const r = await runReview(baseParams(ops))
  expect('F3 : ok=false sur résultat invalide', r.ok, false)
  expect('F3 : errorCode REVIEWER_INVALID_RESULT', r.reviewerResults[0].errorCode, ENGINE_ERROR_CODES.REVIEWER_INVALID_RESULT)
}

{
  // JSON avec champ interdit (revision)
  const forbiddenOutput = JSON.stringify({
    reviewerId: 'reviewer-a',
    subjectType: 'implementation',
    verdict: 'approve',
    scores: [],
    findings: [],
    revision: 1,  // champ interdit
  })
  const ops = makeOps(forbiddenOutput)
  const r = await runReview(baseParams(ops))
  expect('F4 : ok=false sur champ interdit', r.ok, false)
  expect('F4 : errorCode REVIEWER_INVALID_RESULT', r.reviewerResults[0].errorCode, ENGINE_ERROR_CODES.REVIEWER_INVALID_RESULT)
}

// ---------------------------------------------------------------------------
// Bloc G — Hash mismatch
// ---------------------------------------------------------------------------

console.log('\n=== G : Hash mismatch ===\n')

{
  // Le reviewer rapporte un hash différent de celui du subject
  const badHashOutput = JSON.stringify({
    reviewerId: 'reviewer-a',
    subjectType: 'implementation',
    verdict: 'approve',
    scores: [],
    findings: [],
    artifactDescriptor: { path: 'src/user.ts', hash: 'sha256:WRONG' },
  })
  const ops = makeOps(badHashOutput)
  const r = await runReview(baseParams(ops))
  expect('G1 : ok=false sur hash mismatch', r.ok, false)
  expect('G1 : errorCode ARTIFACT_HASH_MISMATCH', r.reviewerResults[0].errorCode, ENGINE_ERROR_CODES.ARTIFACT_HASH_MISMATCH)
}

{
  // Le reviewer n'inclut pas d'artifactDescriptor : accepté (pas d'obligation)
  const noDescOutput = JSON.stringify({
    reviewerId: 'reviewer-a',
    subjectType: 'implementation',
    verdict: 'approve',
    scores: [{ axisId: 'correctness', score: 4 }, { axisId: 'code-quality', score: 4 }],
    findings: [],
  })
  const ops = makeOps(noDescOutput)
  const r = await runReview(baseParams(ops))
  expectTrue('G2 : ok=true si pas d\'artifactDescriptor', r.ok)
}

// ---------------------------------------------------------------------------
// Bloc H — Cleanup
// ---------------------------------------------------------------------------

console.log('\n=== H : Cleanup ===\n')

{
  let caseCounter = 0
  const killedCases = []
  const createdCases = []

  const ops = {
    _killedCases: killedCases,
    _createdCases: createdCases,
    async createCase(ns, title) {
      const id = `case-${++caseCounter}`
      createdCases.push(id)
      return { id }
    },
    async runAgentTurn(caseId, agentName, brief, opts) {
      if (agentName === 'factory-reviewer-a') {
        // A échoue après avoir été lancé (son case est dans activeCases)
        throw new Error('Simulated network error')
      }
      // B réussit mais son case sera tué car A a échoué
      return { status: 'finished', caseStatus: 'IDLE', message: VALID_OUTPUT_B_APPROVE, events: [], killedByBudget: false, anchored: true }
    },
    async killCase(caseId) { killedCases.push(caseId) },
    async preflightAgent(ns, agentName) {
      return { ok: true, reason: null, agent: { name: agentName, enabled: true, subAgents: [], integrations: {} } }
    },
    async listIntegrations() { return [] },
  }

  const r = await runReview(baseParams(ops, { reviewers: [REVIEWER_A, REVIEWER_B] }))
  expect('H1 : ok=false quand un reviewer échoue', r.ok, false)
  expectTrue('H1 : au moins un case tué (cleanup)', killedCases.length >= 1)
}

{
  // Succès total : aucun case tué
  const ops = makeOps(VALID_OUTPUT_APPROVE)
  await runReview(baseParams(ops))
  expect('H2 : aucun case tué sur succès', ops._killedCases.length, 0)
}

{
  // Cleanup sur timeout : le case est tué
  const ops = makeOps('', 'work_timeout')
  const r = await runReview(baseParams(ops))
  expect('H3 : ok=false sur timeout', r.ok, false)
}

// ---------------------------------------------------------------------------
// Bloc I — Étanchéité des faits
// ---------------------------------------------------------------------------

console.log('\n=== I : Étanchéité des faits ===\n')

{
  // Le contenu de l'artefact ne doit pas apparaître dans les faits
  const ops = makeOps(VALID_OUTPUT_APPROVE)
  const r = await runReview(baseParams(ops))
  const factsJson = JSON.stringify(r.facts)
  expectFalse('I1 : contenu artefact absent des faits', factsJson.includes(SUBJECT.content))
}

{
  // La prose sentinel ne doit pas apparaître dans les faits
  const outputWithProse = JSON.stringify({
    reviewerId: 'reviewer-a',
    subjectType: 'implementation',
    verdict: 'request-changes',
    scores: [{ axisId: 'correctness', score: 2 }, { axisId: 'code-quality', score: 3 }],
    findings: [{
      severity: 'major',
      axisId: 'correctness',
      title: PROSE_SENTINEL + ' [title]',
      evidence: PROSE_SENTINEL + ' [evidence]',
      recommendation: PROSE_SENTINEL + ' [recommendation]',
    }],
    artifactDescriptor: { path: 'src/user.ts', hash: 'sha256:deadbeef' },
  })
  const ops = makeOps(outputWithProse)
  const r = await runReview(baseParams(ops))
  expectTrue('I2 : ok=true malgré prose dans findings', r.ok)
  const factsJson = JSON.stringify(r.facts)
  expectFalse('I2 : PROSE_SENTINEL absent des faits', factsJson.includes(PROSE_SENTINEL))
  // Mais la prose est dans rawOutputs
  expectTrue('I2 : rawOutputs contient la prose', r.rawOutputs['reviewer-a']?.includes(PROSE_SENTINEL) ?? false)
}

{
  // Champs racine du registre absents des faits
  const ops = makeOps(VALID_OUTPUT_APPROVE)
  const r = await runReview(baseParams(ops))
  const facts = r.facts
  const IMMUTABLE = ['kind', 'name', 'status', 'durationMs', 'startedAt', 'endedAt']
  for (const field of IMMUTABLE) {
    expectTrue(`I3 : champ racine "${field}" absent des faits`, facts !== null && !(field in facts))
  }
}

{
  // errorDetail est null dans le résultat machine (pas de prose dans les champs machines)
  const ops = makeOps('', 'work_timeout')
  const r = await runReview(baseParams(ops))
  expect('I4 : errorDetail null dans résultat machine', r.errorDetail, null)
  expect('I4 : errorDetail null dans reviewerResult', r.reviewerResults[0].errorDetail, null)
}

{
  // facts null sur échec précoce (pas de reviewers)
  const ops = makeOps('')
  const r = await runReview(baseParams(ops, { reviewers: [] }))
  expect('I5 : facts null sur échec précoce', r.facts, null)
}

// ---------------------------------------------------------------------------
// Bloc J — Verdict agrégé
// ---------------------------------------------------------------------------

console.log('\n=== J : Verdict agrégé ===\n')

{
  // Véto : un reviewer avec finding blocking sur axe correctness (veto)
  const ops = makeOps(VALID_OUTPUT_VETO)
  const r = await runReview(baseParams(ops))
  expectTrue('J1 : ok=true sur véto (l\'agrégation réussit)', r.ok)
  expect('J1 : verdict reject', r.aggregate?.verdict, 'reject')
  expectTrue('J1 : vetoFired', r.aggregate?.vetoFired ?? false)
  expect('J1 : vetoAxes', r.aggregate?.vetoAxes, ['correctness'])
  expect('J1 : facts.reviewVerdict', r.facts?.reviewVerdict, 'reject')
  expect('J1 : facts.reviewVetoFired', r.facts?.reviewVetoFired, true)
}

{
  // Deux reviewers : A approve, B request-changes → verdict = request-changes
  const ops = makeOpsPerAgent({
    'factory-reviewer-a': VALID_OUTPUT_APPROVE,
    'factory-reviewer-b': VALID_OUTPUT_REQUEST_CHANGES.replace('"reviewerId":"reviewer-a"', '"reviewerId":"reviewer-b"'),
  })
  const r = await runReview(baseParams(ops, { reviewers: [REVIEWER_A, REVIEWER_B] }))
  expectTrue('J2 : ok=true', r.ok)
  expect('J2 : verdict request-changes (plus sévère)', r.aggregate?.verdict, 'request-changes')
}

{
  // RevisionMeta injectée par le workflow dans les faits
  const ops = makeOps(VALID_OUTPUT_APPROVE)
  const r = await runReview(baseParams(ops, { revisionMeta: { revision: 2, attempt: 3, maxRevisions: 2, maxAttempts: 3 } }))
  expect('J3 : facts.revision', r.facts?.revision, 2)
  expect('J3 : facts.attempt', r.facts?.attempt, 3)
  expect('J3 : facts.maxRevisions', r.facts?.maxRevisions, 2)
  expect('J3 : facts.maxAttempts', r.facts?.maxAttempts, 3)
}

{
  // facts.reviewerIds et reviewerCount
  const ops = makeOpsPerAgent({
    'factory-reviewer-a': VALID_OUTPUT_APPROVE,
    'factory-reviewer-b': VALID_OUTPUT_B_APPROVE,
  })
  const r = await runReview(baseParams(ops, { reviewers: [REVIEWER_A, REVIEWER_B] }))
  expect('J4 : facts.reviewerCount', r.facts?.reviewerCount, 2)
  expect('J4 : facts.reviewerIds', r.facts?.reviewerIds, ['reviewer-a', 'reviewer-b'])
}

// ---------------------------------------------------------------------------
// Bloc K — Registre global SIGTERM (active-case.mjs)
// ---------------------------------------------------------------------------
//
// Ces tests vérifient que runOneReviewer enregistre le caseId dans active-case.mjs
// (registre global du processus), et le retire dans son bloc finally.
// C'est le prérequis pour que SIGTERM puisse tuer les cases reviewers parallèles.
// ---------------------------------------------------------------------------

console.log('\n=== K : Registre global SIGTERM (active-case.mjs) ===\n')

/** Nettoie le registre global entre les sous-tests. */
function clearGlobalRegistry() {
  for (const id of getActiveCaseIds()) {
    unregisterActiveCase(id)
  }
}

{
  // Cas nominal : le registre est vide avant et après runReview.
  clearGlobalRegistry()

  const ops = makeOps(VALID_OUTPUT_APPROVE)
  const before = getActiveCaseIds().length
  const r = await runReview(baseParams(ops))
  const after = getActiveCaseIds().length

  expectTrue('K1 : registre vide avant runReview', before === 0)
  expectTrue('K1 : ok=true', r.ok)
  expect('K1 : registre vide après runReview (finally exécuté)', after, 0)

  clearGlobalRegistry()
}

{
  // Cas d'échec (timeout) : le registre est vide après runReview.
  clearGlobalRegistry()

  const ops = makeOps('', 'work_timeout')
  const r = await runReview(baseParams(ops))
  const after = getActiveCaseIds().length

  expect('K2 : ok=false sur timeout', r.ok, false)
  expect('K2 : registre vide après échec (finally exécuté)', after, 0)

  clearGlobalRegistry()
}

{
  // Deux reviewers en parallèle : les deux cases retirés après runReview.
  clearGlobalRegistry()

  const ops = makeOpsPerAgent({
    'factory-reviewer-a': VALID_OUTPUT_APPROVE,
    'factory-reviewer-b': VALID_OUTPUT_B_APPROVE,
  })
  const r = await runReview(baseParams(ops, { reviewers: [REVIEWER_A, REVIEWER_B] }))
  const after = getActiveCaseIds().length

  expectTrue('K3 : ok=true avec deux reviewers', r.ok)
  expect('K3 : registre vide après deux reviewers (finally exécuté)', after, 0)

  clearGlobalRegistry()
}

{
  // Exception dans createCase (caseId reste null) : le finally ne plante pas.
  clearGlobalRegistry()

  const ops = makeOps('', 'finished', {
    async createCase() { throw new Error('AgentOS indisponible') },
  })
  let threw = false
  try {
    const r = await runReview(baseParams(ops))
    expect('K4 : ok=false si createCase échoue', r.ok, false)
  } catch {
    threw = true
  }
  expect('K4 : pas d\'exception non attrapée', threw, false)
  expect('K4 : registre vide après échec createCase', getActiveCaseIds().length, 0)

  clearGlobalRegistry()
}

{
  // Simulation SIGTERM pendant le tour d'un reviewer :
  // le registre global expose bien le caseId du reviewer pendant runAgentTurn.
  clearGlobalRegistry()

  const createdCaseId = 'sigterm-case-k5'
  let caseIdSeenDuringSigterm = null
  let resolveRunAgentTurn
  const runAgentTurnGate = new Promise((resolve) => { resolveRunAgentTurn = resolve })

  const ops = {
    async createCase() { return { id: createdCaseId } },
    async runAgentTurn(caseId, agentName, brief, opts) {
      // Snapshot du registre global comme le ferait le handler SIGTERM.
      caseIdSeenDuringSigterm = getActiveCaseIds().find((id) => id === caseId) ?? null
      await runAgentTurnGate
      return { status: 'finished', caseStatus: 'IDLE', message: VALID_OUTPUT_APPROVE, events: [], killedByBudget: false, anchored: true }
    },
    async killCase() {},
    async preflightAgent(ns, agentName) {
      return { ok: true, reason: null, agent: { name: agentName, enabled: true, subAgents: [], integrations: {} } }
    },
    async listIntegrations() { return [] },
  }

  const runPromise = runReview(baseParams(ops))

  // Laisser une tick pour que runAgentTurn démarre et lise le registre.
  await new Promise((resolve) => setImmediate(resolve))

  expect(
    'K5 : SIGTERM voit le caseId reviewer dans le registre global pendant runAgentTurn',
    caseIdSeenDuringSigterm,
    createdCaseId,
  )

  // Débloquer le tour et attendre la fin.
  resolveRunAgentTurn()
  await runPromise

  expect('K5 : registre vide après fin du tour', getActiveCaseIds().length, 0)

  clearGlobalRegistry()
}

// ---------------------------------------------------------------------------
// Résultat
// ---------------------------------------------------------------------------

console.log('')
console.log(`Résultat : ${passed} passé(s), ${failed} échoué(s)`)
process.exit(failed > 0 ? 1 : 0)
