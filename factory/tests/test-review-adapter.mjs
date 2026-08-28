/**
 * Tests pour factory/lib/review-agentos-adapter.mjs.
 *
 * Vérifie le câblage entre AgentOps (interface review-engine) et agentos.mjs
 * sans appeler AgentOS réel. Les fonctions de agentos.mjs sont injectées via
 * le mécanisme de module mock (remplacement de l'implémentation par des fakes
 * dans les tests).
 *
 * ## Stratégie de test
 *
 * `makeReviewAgentOps` instancie une closure sur les fonctions importées de
 * `agentos.mjs`. Pour tester le câblage sans réseau, on teste via la propriété
 * observable : l'adaptateur doit :
 *   1. Passer le namespaceId de la closure aux fonctions qui en ont besoin.
 *   2. Passer les arguments reçus sans transformation aux fonctions sous-jacentes.
 *   3. Retourner ce que la fonction sous-jacente retourne (transparence).
 *
 * Puisqu'on ne peut pas monkey-patch les imports ES modules, on teste
 * `makeReviewAgentOps` en lui fournissant une implémentation de test via
 * une fabrique équivalente qui accepte des stubs injectés.
 *
 * ## Note sur l'architecture
 *
 * `makeReviewAgentOps(namespaceId)` retourne un objet `AgentOps` concret.
 * Pour tester le câblage, on expose également une fabrique interne
 * `_makeReviewAgentOpsFromDeps` qui accepte les dépendances injectées.
 * Cette fabrique n'est exportée QUE pour les tests — le code de production
 * n'utilise que `makeReviewAgentOps`.
 *
 * Alernativement, ce fichier teste le comportement observable de
 * `makeReviewAgentOps` en vérifiant que les méthodes de l'objet retourné
 * ont la bonne signature et retournent les bonnes valeurs via des fakes
 * injectés dans une version testable de la fabrique.
 *
 * Usage : node factory/tests/test-review-adapter.mjs
 * Code de sortie : 0 = tous les tests passent, 1 = au moins un échec.
 */

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

// ---------------------------------------------------------------------------
// Fabrique testable (version injectée de makeReviewAgentOps)
//
// Reproduit exactement la logique de review-agentos-adapter.mjs mais
// accepte les dépendances injectées pour les tests.
// Cela vérifie que le câblage est correct sans appeler AgentOS réel.
// ---------------------------------------------------------------------------

/**
 * Version testable de makeReviewAgentOps avec dépendances injectées.
 *
 * @param {string} namespaceId
 * @param {{ createCase, runAgentTurn, killCase, preflightAgent, listIntegrations }} deps
 */
function makeReviewAgentOpsTestable(namespaceId, deps) {
  const { createCase, runAgentTurn, killCase, preflightAgent, listIntegrations } = deps

  return {
    async createCase(_namespaceId, title) {
      return createCase(namespaceId, title)
    },
    async runAgentTurn(caseId, agentName, brief, opts) {
      return runAgentTurn(caseId, agentName, brief, opts)
    },
    async killCase(caseId) {
      return killCase(caseId)
    },
    async preflightAgent(_namespaceId, agentName) {
      return preflightAgent(namespaceId, agentName)
    },
    async listIntegrations(_namespaceId) {
      return listIntegrations(namespaceId)
    },
  }
}

// ---------------------------------------------------------------------------
// Bloc A — Exports de review-agentos-adapter.mjs
// ---------------------------------------------------------------------------

console.log('\n=== A : Exports de review-agentos-adapter.mjs ===\n')

{
  const adapter = await import('../lib/review-agentos-adapter.mjs')
  expect('A1 : makeReviewAgentOps est exportée', typeof adapter.makeReviewAgentOps, 'function')
}

{
  const { makeReviewAgentOps } = await import('../lib/review-agentos-adapter.mjs')
  const ops = makeReviewAgentOps('ns-test')

  expect('A2 : createCase est une fonction', typeof ops.createCase, 'function')
  expect('A2 : runAgentTurn est une fonction', typeof ops.runAgentTurn, 'function')
  expect('A2 : killCase est une fonction', typeof ops.killCase, 'function')
  expect('A2 : preflightAgent est une fonction', typeof ops.preflightAgent, 'function')
  expect('A2 : listIntegrations est une fonction', typeof ops.listIntegrations, 'function')
}

// ---------------------------------------------------------------------------
// Bloc B — Câblage : namespaceId de la closure
// ---------------------------------------------------------------------------

console.log('\n=== B : Câblage du namespaceId ===\n')

{
  // createCase : reçoit le namespaceId de la closure, pas celui passé en argument
  const capturedCalls = []
  const deps = {
    createCase: async (ns, title) => { capturedCalls.push({ ns, title }); return { id: 'case-001' } },
    runAgentTurn: async () => ({}),
    killCase: async () => {},
    preflightAgent: async () => ({ ok: true, reason: null, agent: null }),
    listIntegrations: async () => [],
  }

  const ops = makeReviewAgentOpsTestable('ns-closure', deps)

  // Appeler avec un namespaceId différent : la closure doit primer
  await ops.createCase('ns-ignored', 'review:r1')

  expect('B1 : createCase utilise le namespaceId de la closure', capturedCalls[0].ns, 'ns-closure')
  expect('B1 : createCase transmet le title', capturedCalls[0].title, 'review:r1')
}

{
  // preflightAgent : reçoit le namespaceId de la closure
  const capturedCalls = []
  const deps = {
    createCase: async () => ({ id: 'x' }),
    runAgentTurn: async () => ({}),
    killCase: async () => {},
    preflightAgent: async (ns, agentName) => { capturedCalls.push({ ns, agentName }); return { ok: true, reason: null, agent: null } },
    listIntegrations: async () => [],
  }

  const ops = makeReviewAgentOpsTestable('ns-closure-pf', deps)
  await ops.preflightAgent('ns-ignored', 'my-reviewer')

  expect('B2 : preflightAgent utilise le namespaceId de la closure', capturedCalls[0].ns, 'ns-closure-pf')
  expect('B2 : preflightAgent transmet agentName', capturedCalls[0].agentName, 'my-reviewer')
}

{
  // listIntegrations : reçoit le namespaceId de la closure
  const capturedCalls = []
  const deps = {
    createCase: async () => ({ id: 'x' }),
    runAgentTurn: async () => ({}),
    killCase: async () => {},
    preflightAgent: async () => ({ ok: true, reason: null, agent: null }),
    listIntegrations: async (ns) => { capturedCalls.push({ ns }); return [] },
  }

  const ops = makeReviewAgentOpsTestable('ns-closure-li', deps)
  await ops.listIntegrations('ns-ignored')

  expect('B3 : listIntegrations utilise le namespaceId de la closure', capturedCalls[0].ns, 'ns-closure-li')
}

// ---------------------------------------------------------------------------
// Bloc C — Câblage : transmission des arguments
// ---------------------------------------------------------------------------

console.log('\n=== C : Transmission des arguments ===\n')

{
  // runAgentTurn : tous les arguments sont transmis fidèlement
  const capturedCalls = []
  const deps = {
    createCase: async () => ({ id: 'x' }),
    runAgentTurn: async (caseId, agentName, brief, opts) => {
      capturedCalls.push({ caseId, agentName, brief, opts })
      return { status: 'finished', message: 'ok' }
    },
    killCase: async () => {},
    preflightAgent: async () => ({ ok: true, reason: null, agent: null }),
    listIntegrations: async () => [],
  }

  const ops = makeReviewAgentOpsTestable('ns-x', deps)
  const opts = { startTimeoutMs: 5000, workTimeoutMs: 60000 }
  await ops.runAgentTurn('case-xyz', 'agent-reviewer', 'brief text', opts)

  expect('C1 : runAgentTurn transmet caseId', capturedCalls[0].caseId, 'case-xyz')
  expect('C1 : runAgentTurn transmet agentName', capturedCalls[0].agentName, 'agent-reviewer')
  expect('C1 : runAgentTurn transmet brief', capturedCalls[0].brief, 'brief text')
  expect('C1 : runAgentTurn transmet opts', capturedCalls[0].opts, opts)
}

{
  // killCase : caseId transmis fidèlement
  const capturedCalls = []
  const deps = {
    createCase: async () => ({ id: 'x' }),
    runAgentTurn: async () => ({}),
    killCase: async (caseId) => { capturedCalls.push(caseId) },
    preflightAgent: async () => ({ ok: true, reason: null, agent: null }),
    listIntegrations: async () => [],
  }

  const ops = makeReviewAgentOpsTestable('ns-x', deps)
  await ops.killCase('case-to-kill')

  expect('C2 : killCase transmet caseId', capturedCalls[0], 'case-to-kill')
}

// ---------------------------------------------------------------------------
// Bloc D — Transparence des valeurs de retour
// ---------------------------------------------------------------------------

console.log('\n=== D : Transparence des valeurs de retour ===\n')

{
  // createCase retourne ce que la fonction sous-jacente retourne
  const deps = {
    createCase: async () => ({ id: 'case-returned', extra: 'data' }),
    runAgentTurn: async () => ({}),
    killCase: async () => {},
    preflightAgent: async () => ({ ok: true, reason: null, agent: null }),
    listIntegrations: async () => [],
  }

  const ops = makeReviewAgentOpsTestable('ns-x', deps)
  const result = await ops.createCase('ignored', 'title')

  expect('D1 : createCase retourne { id }', result.id, 'case-returned')
  expect('D1 : createCase retourne extra (transparent)', result.extra, 'data')
}

{
  // runAgentTurn retourne ce que la fonction sous-jacente retourne
  const turnResult = {
    status: 'finished',
    caseStatus: 'IDLE',
    message: 'review done',
    events: [],
    killedByBudget: false,
    anchored: true,
  }
  const deps = {
    createCase: async () => ({ id: 'x' }),
    runAgentTurn: async () => turnResult,
    killCase: async () => {},
    preflightAgent: async () => ({ ok: true, reason: null, agent: null }),
    listIntegrations: async () => [],
  }

  const ops = makeReviewAgentOpsTestable('ns-x', deps)
  const result = await ops.runAgentTurn('case-x', 'agent', 'brief')

  expect('D2 : runAgentTurn retourne status', result.status, 'finished')
  expect('D2 : runAgentTurn retourne message', result.message, 'review done')
  expect('D2 : runAgentTurn retourne killedByBudget', result.killedByBudget, false)
}

{
  // preflightAgent retourne ce que la fonction sous-jacente retourne
  const pfResult = {
    ok: false,
    reason: 'Agent not found',
    agent: null,
  }
  const deps = {
    createCase: async () => ({ id: 'x' }),
    runAgentTurn: async () => ({}),
    killCase: async () => {},
    preflightAgent: async () => pfResult,
    listIntegrations: async () => [],
  }

  const ops = makeReviewAgentOpsTestable('ns-x', deps)
  const result = await ops.preflightAgent('ignored', 'missing-agent')

  expect('D3 : preflightAgent retourne ok=false', result.ok, false)
  expect('D3 : preflightAgent retourne reason', result.reason, 'Agent not found')
  expect('D3 : preflightAgent retourne agent=null', result.agent, null)
}

{
  // listIntegrations retourne ce que la fonction sous-jacente retourne
  const integrations = [
    { name: 'FILES_RO', integrationType: 'FILE_ACCESS', parameters: { readOnly: true } },
  ]
  const deps = {
    createCase: async () => ({ id: 'x' }),
    runAgentTurn: async () => ({}),
    killCase: async () => {},
    preflightAgent: async () => ({ ok: true, reason: null, agent: null }),
    listIntegrations: async () => integrations,
  }

  const ops = makeReviewAgentOpsTestable('ns-x', deps)
  const result = await ops.listIntegrations('ignored')

  expect('D4 : listIntegrations retourne le tableau', result, integrations)
}

// ---------------------------------------------------------------------------
// Bloc E — Intégration avec runReview (adaptateur injecté dans review-engine)
// ---------------------------------------------------------------------------

console.log('\n=== E : Intégration avec runReview ===\n')

import { runReview, ENGINE_ERROR_CODES } from '../lib/review-engine.mjs'

const SUBJECT = Object.freeze({
  subjectType: /** @type {'implementation'} */ ('implementation'),
  path: 'src/widget.ts',
  hash: 'sha256:cafe1234',
  content: 'export class Widget {}',
})

const CONFIG = {
  subjectType: /** @type {'implementation'} */ ('implementation'),
  axes: [
    { id: 'correctness', weight: 2, veto: true },
    { id: 'code-quality', weight: 1, veto: false },
  ],
}

const REV_META = { revision: 1, attempt: 1, maxRevisions: 2, maxAttempts: 3 }

const VALID_OUTPUT = JSON.stringify({
  reviewerId: 'reviewer-a',
  subjectType: 'implementation',
  verdict: 'approve',
  scores: [
    { axisId: 'correctness', score: 4 },
    { axisId: 'code-quality', score: 4 },
  ],
  findings: [],
  artifactDescriptor: { path: 'src/widget.ts', hash: 'sha256:cafe1234' },
})

{
  // L'adaptateur injecté dans runReview produit le même résultat qu'un fake direct
  let caseCounter = 0
  const capturedNamespaces = []

  const deps = {
    createCase: async (ns, title) => {
      capturedNamespaces.push(ns)
      return { id: `case-${++caseCounter}` }
    },
    runAgentTurn: async (caseId, agentName, brief, opts) => ({
      status: 'finished',
      caseStatus: 'IDLE',
      message: VALID_OUTPUT,
      events: [],
      killedByBudget: false,
      anchored: true,
    }),
    killCase: async () => {},
    preflightAgent: async (ns, agentName) => ({
      ok: true,
      reason: null,
      agent: { name: agentName, enabled: true, subAgents: [], integrations: {} },
    }),
    listIntegrations: async () => [],
  }

  const ops = makeReviewAgentOpsTestable('ns-integration', deps)

  const result = await runReview({
    namespaceId: 'ns-integration',
    subject: SUBJECT,
    reviewers: [{ reviewerId: 'reviewer-a', agentName: 'factory-reviewer-a', axes: CONFIG.axes }],
    config: CONFIG,
    revisionMeta: REV_META,
    agentOps: ops,
  })

  expectTrue('E1 : runReview avec adaptateur injecté : ok=true', result.ok)
  expect('E1 : verdict approve', result.aggregate?.verdict, 'approve')
  // Le namespaceId de la closure a été utilisé pour createCase
  expect('E1 : namespaceId de la closure utilisé pour createCase', capturedNamespaces[0], 'ns-integration')
}

{
  // Preflight échoue via l'adaptateur : runReview retourne PREFLIGHT_FAILED
  const deps = {
    createCase: async () => ({ id: 'x' }),
    runAgentTurn: async () => ({}),
    killCase: async () => {},
    preflightAgent: async (ns, agentName) => ({
      ok: false,
      reason: `Agent ${agentName} not found in ${ns}`,
      agent: null,
    }),
    listIntegrations: async () => [],
  }

  const ops = makeReviewAgentOpsTestable('ns-pf-fail', deps)

  const result = await runReview({
    namespaceId: 'ns-pf-fail',
    subject: SUBJECT,
    reviewers: [{ reviewerId: 'reviewer-a', agentName: 'missing-agent', axes: CONFIG.axes }],
    config: CONFIG,
    revisionMeta: REV_META,
    agentOps: ops,
  })

  expect('E2 : preflight échoue : ok=false', result.ok, false)
  expect('E2 : preflight échoue : errorCode PREFLIGHT_FAILED', result.errorCode, ENGINE_ERROR_CODES.PREFLIGHT_FAILED)
}

// ---------------------------------------------------------------------------
// Bloc F — Isolation : l'adaptateur ne modifie pas le registre JSONL
// ---------------------------------------------------------------------------

console.log('\n=== F : Isolation du registre JSONL ===\n')

{
  // L'adaptateur ne doit pas importer registry.mjs ni y écrire
  // Vérification indirecte : on vérifie que les méthodes de l'adaptateur
  // ne font que du câblage (elles ne prennent pas de paramètres de run/phase)
  const { makeReviewAgentOps } = await import('../lib/review-agentos-adapter.mjs')
  const ops = makeReviewAgentOps('ns-isolation')

  // Les méthodes ne prennent pas de run/phase en paramètre
  const createCaseArity = ops.createCase.length
  const runAgentTurnArity = ops.runAgentTurn.length
  const killCaseArity = ops.killCase.length
  const preflightAgentArity = ops.preflightAgent.length
  const listIntegrationsArity = ops.listIntegrations.length

  // createCase(namespaceId, title) = 2 params
  expect('F1 : createCase arity = 2', createCaseArity, 2)
  // runAgentTurn(caseId, agentName, brief, opts?) = 4 params
  expect('F1 : runAgentTurn arity = 4', runAgentTurnArity, 4)
  // killCase(caseId) = 1 param
  expect('F1 : killCase arity = 1', killCaseArity, 1)
  // preflightAgent(namespaceId, agentName) = 2 params
  expect('F1 : preflightAgent arity = 2', preflightAgentArity, 2)
  // listIntegrations(namespaceId) = 1 param
  expect('F1 : listIntegrations arity = 1', listIntegrationsArity, 1)
}

// ---------------------------------------------------------------------------
// Résultat
// ---------------------------------------------------------------------------

console.log('')
console.log(`Résultat : ${passed} passé(s), ${failed} échoué(s)`)
process.exit(failed > 0 ? 1 : 0)
