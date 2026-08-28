/**
 * Moteur d'exécution de la boucle de revue.
 *
 * ## Ce que ce module fait
 *
 * `runReview(params)` orchestre le lancement parallèle de plusieurs reviewers
 * AgentOS, collecte leurs sorties structurées, les parse et les agrège de
 * façon déterministe.
 *
 * ## Ce que ce module ne fait PAS
 *
 * - Appeler des agents reviewers réels (les fonctions AgentOS sont injectées).
 * - Écrire dans le registre JSONL (c'est le rôle du workflow appelant).
 * - Choisir les transitions de workflow (approve/reject/request-changes).
 * - Modifier us-loop ou tout autre workflow existant.
 * - Exposer les commandes oracle, le JSONL, ou l'accès au checkout aux reviewers.
 *
 * ## Architecture
 *
 * ```
 * runReview({
 *   subject,           // SubjectDescriptor : subjectType, path, hash, content
 *   reviewers,         // ReviewerDef[]     : reviewerId, agentName, axes
 *   config,            // ReviewConfig      : subjectType, axes
 *   revisionMeta,      // RevisionMeta      : revision, attempt, maxRevisions, maxAttempts
 *   agentOps,          // AgentOps          : fonctions injectées (testables)
 *   brief?,            // string optionnel  : contexte supplémentaire
 *   timeoutMs?,        // nombre optionnel  : budget par reviewer
 * })
 * → ReviewExecutionResult
 * ```
 *
 * ## Frontière BMAD
 *
 * Le champ `content` du `SubjectDescriptor` est fourni en mémoire par le
 * workflow. Il est inclus dans le brief posté au reviewer mais n'apparaît
 * jamais dans les faits JSONL ni dans `ReviewExecutionResult.facts`.
 *
 * Si le reviewer inclut un `artifactDescriptor` dans sa sortie JSON,
 * le hash doit correspondre au hash fourni dans le `SubjectDescriptor`.
 * Un écart est une faute de sécurité (le reviewer a travaillé sur un
 * artefact différent) et produit un échec immédiat.
 *
 * ## Capacités admises pour les reviewers
 *
 * Un reviewer est en lecture seule par contrat. Le préflight vérifie :
 * - L'agent existe et est activé.
 * - `subAgents` est vide (pas de délégation parallèle).
 * - Toutes les intégrations `FILE_ACCESS` sont `readOnly: true`.
 * - Aucune intégration mutante (BASH, MCP_STDIO, WEBHOOK, FILE_WRITE)
 *   n'est présente.
 *
 * ## Lifecycle des cases reviewers
 *
 * Chaque reviewer a son propre case AgentOS, créé et suivi indépendamment.
 * Les caseIds sont stockés dans deux registres :
 *
 * 1. `activeCases` (Map local) — pour le cleanup interne quand un reviewer
 *    échoue pendant que d'autres tournent encore.
 * 2. `active-case.mjs` (registre global du processus) — pour que le handler
 *    SIGTERM puisse tuer les cases reviewers au même titre que le case éditeur.
 *
 * Les deux registres sont maintenus en cohérence : enregistrement à la création
 * du case, désenregistrement dans le bloc `finally` de `runOneReviewer`.
 *
 * En cas d'échec (timeout, question, erreur, JSON invalide, résultat invalide,
 * mismatch de hash), tous les cases encore actifs sont tués en best-effort
 * avant de retourner.
 *
 * ## Ordre déterministe
 *
 * Les résultats sont retournés dans l'ordre des `reviewers` d'entrée,
 * indépendamment de l'ordre de complétion AgentOS.
 *
 * ## Invariants de registre
 *
 * `ReviewExecutionResult.facts` est produit par `toReviewFacts` et ne contient
 * aucune prose LLM. Les champs de prose (raw outputs des reviewers) sont dans
 * `ReviewExecutionResult.rawOutputs` pour affichage humain éventuel, jamais
 * dans les faits.
 */

import { parseReviewResult, aggregateReviews, toReviewFacts } from './review.mjs'
import { extractJsonFragment } from './plan.mjs'
import { registerActiveCase, unregisterActiveCase } from './active-case.mjs'

// ---------------------------------------------------------------------------
// Constantes
// ---------------------------------------------------------------------------

/** Budget par défaut par reviewer (15 minutes). */
const DEFAULT_REVIEWER_TIMEOUT_MS = 15 * 60 * 1000

/** Délai de démarrage : budget pour voir le case passer à RUNNING. */
const DEFAULT_START_TIMEOUT_MS = 30_000

/**
 * Intégrations considérées comme mutantes.
 * Un reviewer ne doit pas avoir ces types d'intégration.
 */
const MUTATING_INTEGRATION_TYPES = new Set([
  'BASH',
  'MCP_STDIO',
  'WEBHOOK',
  'FILE_WRITE',
  'GIT',
])

/**
 * Codes d'échec machine-readable pour ReviewExecutionResult.
 * Jamais de prose dans ces valeurs.
 *
 * @readonly
 */
export const ENGINE_ERROR_CODES = /** @type {const} */ ({
  DUPLICATE_REVIEWER_IDS:  'DUPLICATE_REVIEWER_IDS',
  NO_REVIEWERS:            'NO_REVIEWERS',
  PREFLIGHT_FAILED:        'PREFLIGHT_FAILED',
  REVIEWER_TIMEOUT:        'REVIEWER_TIMEOUT',
  REVIEWER_PENDING:        'REVIEWER_PENDING',
  REVIEWER_AGENT_ERROR:    'REVIEWER_AGENT_ERROR',
  REVIEWER_NO_JSON:        'REVIEWER_NO_JSON',
  REVIEWER_INVALID_JSON:   'REVIEWER_INVALID_JSON',
  REVIEWER_INVALID_RESULT: 'REVIEWER_INVALID_RESULT',
  ARTIFACT_HASH_MISMATCH:  'ARTIFACT_HASH_MISMATCH',
  AGGREGATE_INVALID:       'AGGREGATE_INVALID',
})

// ---------------------------------------------------------------------------
// Types JSDoc
// ---------------------------------------------------------------------------

/**
 * @typedef {{
 *   subjectType: 'technical-spec' | 'implementation',
 *   path: string,
 *   hash: string,
 *   content: string,
 * }} SubjectDescriptor
 *
 * @typedef {{
 *   reviewerId: string,
 *   agentName: string,
 *   axes: import('./review.mjs').AxisDef[],
 * }} ReviewerDef
 *
 * @typedef {{
 *   createCase: (namespaceId: string, title: string) => Promise<{ id: string }>,
 *   postMessage: (caseId: string, content: string) => Promise<void>,
 *   runAgentTurn: (
 *     caseId: string,
 *     agentName: string,
 *     brief: string,
 *     opts?: { startTimeoutMs?: number, workTimeoutMs?: number }
 *   ) => Promise<AgentTurnResult>,
 *   killCase: (caseId: string) => Promise<void>,
 *   preflightAgent: (namespaceId: string, agentName: string) => Promise<{ ok: boolean, reason: string|null, agent: object|null }>,
 *   listIntegrations: (namespaceId: string) => Promise<object[]>,
 * }} AgentOps
 *
 * @typedef {{
 *   status: string,
 *   caseStatus: string|null,
 *   message: string,
 *   events: object[],
 *   killedByBudget: boolean,
 *   anchored: boolean,
 * }} AgentTurnResult
 *
 * @typedef {{
 *   ok: boolean,
 *   reviewerId: string,
 *   agentName: string,
 *   caseId: string|null,
 *   turnStatus: string|null,
 *   rawOutput: string|null,
 *   parseOutcome: import('./review.mjs').ParseOutcome|null,
 *   errorCode: string|null,
 *   errorDetail: string|null,
 * }} ReviewerRunResult
 *
 * @typedef {{
 *   ok: boolean,
 *   errorCode: string|null,
 *   errorDetail: string|null,
 *   reviewerResults: ReviewerRunResult[],
 *   aggregate: import('./review.mjs').AggregateResult|null,
 *   facts: import('./review.mjs').ReviewFacts|null,
 *   rawOutputs: Record<string, string|null>,
 * }} ReviewExecutionResult
 */

// ---------------------------------------------------------------------------
// runReview
// ---------------------------------------------------------------------------

/**
 * Lance la revue en parallèle et retourne un résultat structuré.
 *
 * @param {{
 *   namespaceId: string,
 *   subject: SubjectDescriptor,
 *   reviewers: ReviewerDef[],
 *   config: import('./review.mjs').ReviewConfig,
 *   revisionMeta: import('./review.mjs').RevisionMeta,
 *   agentOps: AgentOps,
 *   brief?: string,
 *   timeoutMs?: number,
 *   startTimeoutMs?: number,
 * }} params
 * @returns {Promise<ReviewExecutionResult>}
 */
export async function runReview(params) {
  const {
    namespaceId,
    subject,
    reviewers,
    config,
    revisionMeta,
    agentOps,
    brief = '',
    timeoutMs = DEFAULT_REVIEWER_TIMEOUT_MS,
    startTimeoutMs = DEFAULT_START_TIMEOUT_MS,
  } = params

  // --- Validation d'entrée ---

  if (!reviewers || reviewers.length === 0) {
    return engineFail(ENGINE_ERROR_CODES.NO_REVIEWERS, 'Aucun reviewer fourni', [], null, revisionMeta)
  }

  // IDs dupliqués
  const idSet = new Set()
  for (const r of reviewers) {
    if (idSet.has(r.reviewerId)) {
      return engineFail(
        ENGINE_ERROR_CODES.DUPLICATE_REVIEWER_IDS,
        `reviewerId dupliqué : ${r.reviewerId}`,
        [],
        null,
        revisionMeta,
      )
    }
    idSet.add(r.reviewerId)
  }

  // --- Préflight de tous les reviewers ---

  const preflightErrors = []
  for (const reviewerDef of reviewers) {
    const pf = await preflightReviewer(namespaceId, reviewerDef, agentOps)
    if (!pf.ok) {
      preflightErrors.push(`${reviewerDef.reviewerId}: ${pf.reason}`)
    }
  }

  if (preflightErrors.length > 0) {
    return engineFail(
      ENGINE_ERROR_CODES.PREFLIGHT_FAILED,
      preflightErrors.join(' | '),
      [],
      null,
      revisionMeta,
    )
  }

  // --- Construction du brief commun ---
  // Le brief est identique pour tous les reviewers (même snapshot).
  // Le contenu de l'artefact est inclus en mémoire ; il ne va pas dans les faits.
  const sharedBrief = buildReviewerBrief(subject, config, brief)

  // --- Lancement parallèle ---
  // Map caseId → reviewerId pour le cleanup.
  /** @type {Map<string, string>} */
  const activeCases = new Map()

  // Chaque reviewer tourne indépendamment. On collecte les promesses dans
  // l'ordre d'entrée pour garantir l'ordre déterministe du résultat.
  const promises = reviewers.map((reviewerDef) =>
    runOneReviewer({
      namespaceId,
      reviewerDef,
      config,
      subject,
      sharedBrief,
      agentOps,
      timeoutMs,
      startTimeoutMs,
      activeCases,
    })
  )


  // Promise.allSettled garantit qu'on attend tous même si l'un échoue.
  const settled = await Promise.allSettled(promises)

  // --- Collecte des résultats dans l'ordre d'entrée ---
  /** @type {ReviewerRunResult[]} */
  const reviewerResults = []
  for (const s of settled) {
    if (s.status === 'fulfilled') {
      reviewerResults.push(s.value)
    } else {
      // Une exception non attrapée dans runOneReviewer (ne devrait pas arriver
      // car runOneReviewer attrape tout, mais fail-closed).
      reviewerResults.push({
        ok: false,
        reviewerId: 'unknown',
        agentName: 'unknown',
        caseId: null,
        turnStatus: null,
        rawOutput: null,
        parseOutcome: null,
        errorCode: ENGINE_ERROR_CODES.REVIEWER_AGENT_ERROR,
        errorDetail: 'Exception non attrapée dans runOneReviewer',
      })
    }
  }

  // --- Cleanup des cases encore actifs ---
  // Si au moins un reviewer a échoué, on tue les autres en best-effort.
  const anyFailed = reviewerResults.some((r) => !r.ok)
  if (anyFailed && activeCases.size > 0) {
    await killActiveCases(activeCases, agentOps)
  }

  // --- Raw outputs (prose, pour affichage humain uniquement) ---
  /** @type {Record<string, string|null>} */
  const rawOutputs = {}
  for (const r of reviewerResults) {
    rawOutputs[r.reviewerId] = r.rawOutput
  }

  // --- Si au moins un reviewer a échoué, retourner sans agréger ---
  if (anyFailed) {
    const firstFail = reviewerResults.find((r) => !r.ok)
    return {
      ok: false,
      errorCode: firstFail?.errorCode ?? ENGINE_ERROR_CODES.REVIEWER_AGENT_ERROR,
      errorDetail: null, // pas de prose dans le résultat machine
      reviewerResults,
      aggregate: null,
      facts: null,
      rawOutputs,
    }
  }

  // --- Agrégation ---
  const parseOutcomes = reviewerResults.map((r) => r.parseOutcome)
  const aggregate = aggregateReviews(
    /** @type {import('./review.mjs').ParseOutcome[]} */ (parseOutcomes),
    config,
  )

  if (aggregate.verdict === 'invalid-output') {
    return {
      ok: false,
      errorCode: ENGINE_ERROR_CODES.AGGREGATE_INVALID,
      errorDetail: null,
      reviewerResults,
      aggregate,
      facts: toReviewFacts(aggregate, revisionMeta),
      rawOutputs,
    }
  }

  const facts = toReviewFacts(aggregate, revisionMeta)

  return {
    ok: true,
    errorCode: null,
    errorDetail: null,
    reviewerResults,
    aggregate,
    facts,
    rawOutputs,
  }
}

// ---------------------------------------------------------------------------
// runOneReviewer
// ---------------------------------------------------------------------------

/**
 * Exécute un seul reviewer et retourne un `ReviewerRunResult`.
 * N'est jamais appelé directement par les tests — uniquement via runReview.
 *
 * @param {{
 *   namespaceId: string,
 *   reviewerDef: ReviewerDef,
 *   config: import('./review.mjs').ReviewConfig,
 *   subject: SubjectDescriptor,
 *   sharedBrief: string,
 *   agentOps: AgentOps,
 *   timeoutMs: number,
 *   startTimeoutMs: number,
 *   activeCases: Map<string, string>,
 * }} params
 * @returns {Promise<ReviewerRunResult>}
 */
async function runOneReviewer(params) {
  const { namespaceId, reviewerDef, config, subject, sharedBrief, agentOps, timeoutMs, startTimeoutMs, activeCases } = params
  const { reviewerId, agentName } = reviewerDef

  /** @type {string|null} */
  let caseId = null

  try {
    // Créer le case
    const created = await agentOps.createCase(
      namespaceId,
      `review:${reviewerId}`,
    )
    caseId = created.id

    // Enregistrer dans les deux registres :
    // - activeCases (local) : cleanup interne si un reviewer échoue en cours de route
    // - active-case.mjs (global) : SIGTERM peut tuer ce case au même titre que l'éditeur
    activeCases.set(caseId, reviewerId)
    registerActiveCase(caseId, `review:${reviewerId}`)

    // Lancer le tour d'agent
    const turn = await agentOps.runAgentTurn(
      caseId,
      agentName,
      sharedBrief,
      { startTimeoutMs, workTimeoutMs: timeoutMs },
    )

    // Retirer du registre local (le turn est terminé).
    // Le désenregistrement global se fait dans le bloc finally ci-dessous.
    activeCases.delete(caseId)

    // Vérifier le statut du tour
    if (turn.status === 'start_timeout' || turn.status === 'work_timeout') {
      return reviewerFail(reviewerId, agentName, caseId, turn.status, null, ENGINE_ERROR_CODES.REVIEWER_TIMEOUT)
    }
    if (turn.status === 'pending_question') {
      return reviewerFail(reviewerId, agentName, caseId, turn.status, null, ENGINE_ERROR_CODES.REVIEWER_PENDING)
    }
    if (turn.status === 'killed' || turn.status === 'case_error' || turn.status === 'error') {
      return reviewerFail(reviewerId, agentName, caseId, turn.status, null, ENGINE_ERROR_CODES.REVIEWER_AGENT_ERROR)
    }
    // 'finished' est le seul statut acceptable
    if (turn.status !== 'finished') {
      return reviewerFail(reviewerId, agentName, caseId, turn.status, null, ENGINE_ERROR_CODES.REVIEWER_AGENT_ERROR)
    }

    const rawOutput = turn.message ?? ''

    // Extraire le JSON de la réponse
    const fragment = extractJsonFragment(rawOutput)
    if (!fragment) {
      return reviewerFail(reviewerId, agentName, caseId, turn.status, rawOutput, ENGINE_ERROR_CODES.REVIEWER_NO_JSON)
    }

    let parsed
    try {
      parsed = JSON.parse(fragment)
    } catch {
      return reviewerFail(reviewerId, agentName, caseId, turn.status, rawOutput, ENGINE_ERROR_CODES.REVIEWER_INVALID_JSON)
    }

    // Vérifier le hash de l'artefact si le reviewer l'a inclus
    const reportedHash = parsed?.artifactDescriptor?.hash
    if (reportedHash !== undefined && reportedHash !== subject.hash) {
      return reviewerFail(reviewerId, agentName, caseId, turn.status, rawOutput, ENGINE_ERROR_CODES.ARTIFACT_HASH_MISMATCH)
    }

    // Parser et valider la structure
    const outcome = parseReviewResult(parsed, { subjectType: config.subjectType, axes: reviewerDef.axes })
    if (!outcome.ok) {
      return reviewerFail(reviewerId, agentName, caseId, turn.status, rawOutput, ENGINE_ERROR_CODES.REVIEWER_INVALID_RESULT)
    }

    return {
      ok: true,
      reviewerId,
      agentName,
      caseId,
      turnStatus: turn.status,
      rawOutput,  // prose, pour affichage humain uniquement
      parseOutcome: outcome,
      errorCode: null,
      errorDetail: null,
    }

  } catch (err) {
    // Exception réseau ou autre — NE PAS retirer le case de activeCases ici.
    // Si le case existe, il est peut-être encore actif côté AgentOS.
    // killActiveCases() s'en chargera après Promise.allSettled.
    // Le désenregistrement global se fait dans le bloc finally ci-dessous.
    return reviewerFail(
      reviewerId,
      agentName,
      caseId,
      null,
      null,
      ENGINE_ERROR_CODES.REVIEWER_AGENT_ERROR,
    )
  } finally {
    // Désenregistrement du registre global SIGTERM.
    // Toujours exécuté quel que soit le chemin de sortie (succès, échec, exception).
    // Si caseId est null (createCase a échoué), unregisterActiveCase est une no-op.
    if (caseId !== null) {
      unregisterActiveCase(caseId)
    }
  }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Préflight d'un reviewer : agent valide + intégrations en lecture seule.
 *
 * @param {string} namespaceId
 * @param {ReviewerDef} reviewerDef
 * @param {AgentOps} agentOps
 * @returns {Promise<{ ok: boolean, reason: string|null }>}
 */
async function preflightReviewer(namespaceId, reviewerDef, agentOps) {
  const { reviewerId, agentName } = reviewerDef

  // Contrôle 1-3 : agent existe, activé, subAgents vide
  const agentCheck = await agentOps.preflightAgent(namespaceId, agentName)
  if (!agentCheck.ok) {
    return { ok: false, reason: agentCheck.reason }
  }

  // Contrôle 4 : intégrations
  let integrations
  try {
    integrations = await agentOps.listIntegrations(namespaceId)
  } catch (err) {
    return { ok: false, reason: `Impossible de lister les intégrations : ${err}` }
  }

  const agent = agentCheck.agent
  const declaredKeys = Object.keys(agent?.integrations ?? {})
  const RESERVED = new Set(['QUERY_USER', 'CASE_FILE_EXCHANGE', 'NAMESPACE_FILE_EXCHANGE'])
  const byName = new Map((integrations ?? []).map((c) => [c.name, c]))

  for (const key of declaredKeys) {
    if (RESERVED.has(key)) continue
    const cfg = byName.get(key)
    if (!cfg) {
      // Intégration non trouvée via REST — fail-closed
      return {
        ok: false,
        reason: `Reviewer ${reviewerId}: intégration "${key}" introuvable via REST (fail-closed)`,
      }
    }
    // Refus des intégrations mutantes
    if (MUTATING_INTEGRATION_TYPES.has(cfg.integrationType)) {
      return {
        ok: false,
        reason: `Reviewer ${reviewerId}: intégration mutante "${key}" (${cfg.integrationType}) non admise`,
      }
    }
    // FILE_ACCESS doit être readOnly
    if (cfg.integrationType === 'FILE_ACCESS' && cfg.parameters?.readOnly !== true) {
      return {
        ok: false,
        reason: `Reviewer ${reviewerId}: intégration FILE_ACCESS "${key}" n'est pas readOnly`,
      }
    }
  }

  return { ok: true, reason: null }
}

/**
 * Construit le brief commun envoyé à chaque reviewer.
 * Identique pour tous : même snapshot, même politique.
 *
 * Le contenu de l'artefact est inclus pour que le reviewer puisse travailler,
 * mais ne doit pas apparaître dans les faits JSONL.
 *
 * @param {SubjectDescriptor} subject
 * @param {import('./review.mjs').ReviewConfig} config
 * @param {string} extraBrief
 * @returns {string}
 */
function buildReviewerBrief(subject, config, extraBrief) {
  const axisLines = config.axes
    .map((a) => `- ${a.id}${a.veto ? ' [VETO]' : ''} (poids ${a.weight ?? 1})`)
    .join('\n')

  return [
    `## Revue de ${subject.subjectType}`,
    '',
    `Artefact : ${subject.path}`,
    `Hash SHA-256 : ${subject.hash}`,
    '',
    '### Axes de revue',
    axisLines,
    '',
    '### Contenu à relire',
    '```',
    subject.content,
    '```',
    '',
    extraBrief ? `### Contexte supplémentaire\n${extraBrief}` : '',
    '',
    '### Format de réponse attendu',
    'Répondre avec un bloc JSON contenant :',
    '- reviewerId (string)',
    '- subjectType (string)',
    '- verdict : "approve" | "request-changes" | "reject"',
    '- scores : [{ axisId, score (1-5) }]',
    '- findings : [{ severity, axisId, title, evidence, recommendation, file?, line? }]',
    '- sensitiveAreas? : string[]',
    `- artifactDescriptor : { path: "${subject.path}", hash: "${subject.hash}" }`,
  ].filter((l) => l !== undefined).join('\n')
}

/**
 * Tue tous les cases encore actifs (best-effort, sans propager les erreurs).
 *
 * @param {Map<string, string>} activeCases
 * @param {AgentOps} agentOps
 */
async function killActiveCases(activeCases, agentOps) {
  const kills = [...activeCases.keys()].map(async (caseId) => {
    try {
      await agentOps.killCase(caseId)
    } catch {
      // best-effort : on ne propage pas les erreurs de kill
    }
  })
  await Promise.allSettled(kills)
  activeCases.clear()
}

/**
 * @param {string} reviewerId
 * @param {string} agentName
 * @param {string|null} caseId
 * @param {string|null} turnStatus
 * @param {string|null} rawOutput  prose, ne va pas dans les faits
 * @param {string} errorCode
 * @returns {ReviewerRunResult}
 */
function reviewerFail(reviewerId, agentName, caseId, turnStatus, rawOutput, errorCode) {
  return {
    ok: false,
    reviewerId,
    agentName,
    caseId,
    turnStatus,
    rawOutput,
    parseOutcome: null,
    errorCode,
    errorDetail: null, // pas de prose dans les champs machines
  }
}

/**
 * Construit un ReviewExecutionResult d'échec précoce (avant lancement des reviewers).
 *
 * @param {string} errorCode
 * @param {string} _detail  Utilisé pour le débogage local uniquement, jamais exposé
 * @param {ReviewerRunResult[]} reviewerResults
 * @param {import('./review.mjs').AggregateResult|null} aggregate
 * @param {import('./review.mjs').RevisionMeta} revisionMeta
 * @returns {ReviewExecutionResult}
 */
function engineFail(errorCode, _detail, reviewerResults, aggregate, revisionMeta) {
  return {
    ok: false,
    errorCode,
    errorDetail: null, // pas de prose dans le résultat machine
    reviewerResults,
    aggregate,
    facts: null,
    rawOutputs: {},
  }
}
