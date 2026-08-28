/**
 * Fondation de la boucle de revue — parsing, agrégation et projection JSONL.
 *
 * ## Ce que ce module fait
 *
 * 1. `parseReviewResult(raw, config)` — valide et normalise la sortie structurée
 *    d'un reviewer agent. Aucun LLM n'est appelé ici.
 *
 * 2. `aggregateReviews(outcomes, config)` — agrège plusieurs `ParseOutcome` en
 *    un verdict déterministe. Aucun LLM n'est appelé ici.
 *
 * 3. `toReviewFacts(aggregate, revisionMeta)` — projette un `AggregateResult`
 *    et des métadonnées de révision (propriété du workflow) en un objet plat
 *    sûr pour `passPhase`/`failPhase`. Aucune prose LLM n'entre dans le registre.
 *
 * ## Ce que ce module ne fait PAS
 *
 * - Appeler des agents AgentOS.
 * - Lire ou écrire sur le disque.
 * - Modifier us-loop ou tout autre workflow.
 * - Découvrir ou stocker des artefacts BMAD (voir § Frontière BMAD).
 * - Ajouter des phases dans le registre JSONL (c'est le rôle du workflow).
 *
 * ## Invariant de registre — aucune prose LLM
 *
 * `evidence`, `recommendation` et `title` des findings sont de la prose produite
 * par un LLM. Ils ne doivent jamais entrer dans le registre JSONL. Ce module les
 * valide à l'entrée (non vides, type string) mais ne les conserve PAS dans
 * `Finding` ni dans aucun objet destiné au registre.
 *
 * `toReviewFacts` est la seule fonction autorisant la construction de `facts`
 * JSONL. Elle ne produit que des faits machines : verdicts, codes d'erreur,
 * compteurs, scores numériques, identifiants, chemins, hachages.
 *
 * ## Budgets de révision — propriété du workflow
 *
 * Les budgets de révision (numéro de révision, tentative, maxima) sont connus
 * exclusivement du workflow qui instancie la boucle de revue. Un reviewer agent
 * n'a pas accès à ces valeurs et ne doit pas en produire. Si un output de
 * reviewer contient un champ `revisionBudget`, `revision`, `attempt`, `maxRevisions`
 * ou `maxAttempts`, `parseReviewResult` rejette l'output (champ interdit).
 *
 * Les valeurs de révision sont injectées dans les faits JSONL uniquement via
 * `toReviewFacts(aggregate, { revision, attempt, maxRevisions, maxAttempts })`.
 *
 * ## Scores numériques
 *
 * Les scores (1–5 par axe) sont des indicateurs de risque optionnels. Ils
 * n'influencent jamais les vétos ni les verdicts. Un reviewer qui donne un score
 * élevé sur un axe véto tout en déclarant un finding `blocking` sur ce même axe
 * voit son véto déclenché : le score est ignoré pour la décision.
 *
 * ## Frontière BMAD
 *
 * Un futur adaptateur BMAD fournira un `ArtifactDescriptor` :
 *
 *   { path: string, hash: string, content: string }
 *
 * Ce descripteur sera passé par le workflow dans le brief du reviewer. Le reviewer
 * peut inclure `artifactDescriptor: { path, hash }` dans son output (sans `content`)
 * pour traçabilité. Ce module valide et conserve `path` et `hash`, supprime
 * `content` s'il est présent. L'adaptateur n'est pas implémenté ici.
 *
 * ## Types de sujet
 *
 * - `technical-spec` : revue d'une spécification (cohérence, complétude,
 *   faisabilité, risques). L'objet de la revue est un document.
 * - `implementation` : revue d'une implémentation (qualité du code, respect
 *   du plan). L'objet de la revue est un diff ou des fichiers modifiés.
 *
 * ## Politique d'agrégation
 *
 * 1. Si un `ParseOutcome` est invalide, le résultat agrégé est
 *    `{ verdict: 'invalid-output', ... }`. Pas de dégradation silencieuse.
 *
 * 2. Finding `blocking` sur un axe `veto: true` ⇒ verdict `reject`.
 *
 * 3. Finding `blocking` ou `major` (hors véto) ⇒ verdict `request-changes`.
 *
 * 4. Tous les findings `minor`/`info` ⇒ verdict `approve`.
 *
 * 5. Verdict final : le plus sévère parmi tous les reviewers
 *    (`reject` > `request-changes` > `approve`).
 *
 * 6. Score agrégé = moyenne pondérée des scores par axe (eux-mêmes moyennes
 *    simples sur les reviewers). Axe non scoré exclu de la moyenne.
 */

// ---------------------------------------------------------------------------
// Constantes
// ---------------------------------------------------------------------------

/** Types de sujet de revue acceptés. */
export const SUBJECT_TYPES = /** @type {const} */ (['technical-spec', 'implementation'])

/** Niveaux de sévérité des findings, du plus grave au moins grave. */
export const SEVERITIES = /** @type {const} */ (['blocking', 'major', 'minor', 'info'])

/** Verdicts possibles pour un reviewer individuel. */
export const REVIEWER_VERDICTS = /** @type {const} */ (['approve', 'request-changes', 'reject'])

/** Verdicts possibles pour le résultat agrégé (inclut l'échec d'instrument). */
export const AGGREGATE_VERDICTS = /** @type {const} */ (['approve', 'request-changes', 'reject', 'invalid-output'])

/** Score minimum et maximum acceptés par axe. */
const SCORE_MIN = 1
const SCORE_MAX = 5

/**
 * Champs interdits dans l'output d'un reviewer.
 * Ces valeurs appartiennent au workflow, pas au reviewer.
 */
const FORBIDDEN_REVIEWER_FIELDS = ['revisionBudget', 'revision', 'attempt', 'maxRevisions', 'maxAttempts']

/**
 * Codes d'erreur machine-readable pour les échecs de parsing.
 * Jamais de prose LLM dans ces valeurs.
 *
 * @readonly
 * @enum {string}
 */
export const PARSE_ERROR_CODES = /** @type {const} */ ({
  NOT_AN_OBJECT:          'NOT_AN_OBJECT',
  FORBIDDEN_FIELD:        'FORBIDDEN_FIELD',
  INVALID_REVIEWER_ID:    'INVALID_REVIEWER_ID',
  INVALID_SUBJECT_TYPE:   'INVALID_SUBJECT_TYPE',
  INVALID_VERDICT:        'INVALID_VERDICT',
  INVALID_SCORES:         'INVALID_SCORES',
  INVALID_FINDINGS:       'INVALID_FINDINGS',
  INVALID_SENSITIVE_AREAS:'INVALID_SENSITIVE_AREAS',
  INVALID_ARTIFACT:       'INVALID_ARTIFACT',
})

// ---------------------------------------------------------------------------
// Types (JSDoc uniquement)
// ---------------------------------------------------------------------------

/**
 * @typedef {{ id: string, weight?: number, veto?: boolean }} AxisDef
 *
 * @typedef {{
 *   subjectType: 'technical-spec' | 'implementation',
 *   axes: AxisDef[],
 * }} ReviewConfig
 *
 * Finding : prose (title/evidence/recommendation) validée mais non conservée.
 * Seuls les champs machines sont stockés.
 *
 * @typedef {{
 *   severity: 'blocking' | 'major' | 'minor' | 'info',
 *   axisId: string,
 *   file?: string,
 *   line?: number,
 * }} Finding
 *
 * @typedef {{ reviewerId: string, axisId: string, score: number }} AxisScore
 *
 * @typedef {{
 *   reviewerId: string,
 *   subjectType: 'technical-spec' | 'implementation',
 *   verdict: 'approve' | 'request-changes' | 'reject',
 *   scores: AxisScore[],
 *   findings: Finding[],
 *   sensitiveAreas?: string[],
 *   artifactDescriptor?: { path: string, hash: string },
 * }} ReviewResult
 *
 * @typedef {{
 *   ok: boolean,
 *   reviewerId?: string,
 *   result?: ReviewResult,
 *   errorCode?: string,
 * }} ParseOutcome
 *
 * @typedef {{
 *   verdict: 'approve' | 'request-changes' | 'reject' | 'invalid-output',
 *   reviewerCount: number,
 *   invalidCount: number,
 *   vetoFired: boolean,
 *   vetoAxes: string[],
 *   aggregateScore: number | null,
 *   axisScores: Record<string, number>,
 *   findingCounts: { blocking: number, major: number, minor: number, info: number },
 *   sensitiveAreas: string[],
 *   reviewerIds: string[],
 *   invalidReviewerIds: string[],
 *   errorCodes: string[],
 *   artifactDescriptors: Array<{ path: string, hash: string }>,
 * }} AggregateResult
 *
 * @typedef {{
 *   revision: number,
 *   attempt: number,
 *   maxRevisions: number,
 *   maxAttempts: number,
 * }} RevisionMeta
 *
 * ReviewFacts : objet plat, sûr pour passPhase/failPhase.
 * Aucune prose LLM. Aucun champ réinscriptible (pas de kind/name/status/durationMs).
 *
 * @typedef {{
 *   reviewVerdict: string,
 *   reviewVetoFired: boolean,
 *   reviewVetoAxes: string[],
 *   reviewAggregateScore: number | null,
 *   reviewAxisScores: Record<string, number>,
 *   reviewFindingCounts: { blocking: number, major: number, minor: number, info: number },
 *   reviewSensitiveAreas: string[],
 *   reviewerCount: number,
 *   reviewerIds: string[],
 *   invalidReviewerIds: string[],
 *   reviewErrorCodes: string[],
 *   artifactPaths: string[],
 *   artifactHashes: string[],
 *   revision: number,
 *   attempt: number,
 *   maxRevisions: number,
 *   maxAttempts: number,
 * }} ReviewFacts
 */

// ---------------------------------------------------------------------------
// parseReviewResult
// ---------------------------------------------------------------------------

/**
 * Valide et normalise la sortie structurée d'un reviewer agent.
 *
 * Prose LLM (title, evidence, recommendation de chaque finding) : validée
 * (non vide, type string) mais NON conservée dans `Finding`. Elle ne doit
 * pas entrer dans le registre.
 *
 * Champs interdits (propriété du workflow) : `revisionBudget`, `revision`,
 * `attempt`, `maxRevisions`, `maxAttempts`. Leur présence dans l'output du
 * reviewer est un échec de parsing (code FORBIDDEN_FIELD).
 *
 * @param {unknown} raw  Valeur brute (typiquement issue de JSON.parse).
 * @param {ReviewConfig} config
 * @returns {ParseOutcome}
 */
export function parseReviewResult(raw, config) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
    return fail(undefined, PARSE_ERROR_CODES.NOT_AN_OBJECT)
  }

  const r = /** @type {Record<string, unknown>} */ (raw)

  // --- reviewerId (lu en premier pour l'inclure dans les échecs ultérieurs) ---
  const reviewerId = r['reviewerId']
  const rid = typeof reviewerId === 'string' && reviewerId.trim() !== ''
    ? reviewerId.trim()
    : undefined

  if (rid === undefined) {
    return fail(undefined, PARSE_ERROR_CODES.INVALID_REVIEWER_ID)
  }

  // --- Champs interdits ---
  for (const field of FORBIDDEN_REVIEWER_FIELDS) {
    if (field in r) {
      return fail(rid, PARSE_ERROR_CODES.FORBIDDEN_FIELD)
    }
  }

  // --- subjectType ---
  const subjectType = r['subjectType']
  if (!SUBJECT_TYPES.includes(/** @type {any} */ (subjectType))) {
    return fail(rid, PARSE_ERROR_CODES.INVALID_SUBJECT_TYPE)
  }

  // --- verdict ---
  const verdict = r['verdict']
  if (!REVIEWER_VERDICTS.includes(/** @type {any} */ (verdict))) {
    return fail(rid, PARSE_ERROR_CODES.INVALID_VERDICT)
  }

  // --- scores ---
  const rawScores = r['scores']
  if (!Array.isArray(rawScores)) {
    return fail(rid, PARSE_ERROR_CODES.INVALID_SCORES)
  }

  const knownAxisIds = new Set(config.axes.map((a) => a.id))
  const seenAxisIds = new Set()

  /** @type {AxisScore[]} */
  const scores = []
  for (let i = 0; i < rawScores.length; i++) {
    const s = rawScores[i]
    if (!s || typeof s !== 'object' || Array.isArray(s)) {
      return fail(rid, PARSE_ERROR_CODES.INVALID_SCORES)
    }
    const ss = /** @type {Record<string, unknown>} */ (s)

    const axisId = ss['axisId']
    if (typeof axisId !== 'string' || axisId.trim() === '') {
      return fail(rid, PARSE_ERROR_CODES.INVALID_SCORES)
    }
    if (!knownAxisIds.has(axisId)) {
      return fail(rid, PARSE_ERROR_CODES.INVALID_SCORES)
    }
    if (seenAxisIds.has(axisId)) {
      return fail(rid, PARSE_ERROR_CODES.INVALID_SCORES)
    }
    seenAxisIds.add(axisId)

    const score = ss['score']
    if (typeof score !== 'number' || !Number.isFinite(score) ||
        score < SCORE_MIN || score > SCORE_MAX) {
      return fail(rid, PARSE_ERROR_CODES.INVALID_SCORES)
    }

    scores.push({ reviewerId: rid, axisId, score })
  }

  // --- findings ---
  const rawFindings = r['findings']
  if (!Array.isArray(rawFindings)) {
    return fail(rid, PARSE_ERROR_CODES.INVALID_FINDINGS)
  }

  /** @type {Finding[]} */
  const findings = []
  for (let i = 0; i < rawFindings.length; i++) {
    const f = rawFindings[i]
    if (!f || typeof f !== 'object' || Array.isArray(f)) {
      return fail(rid, PARSE_ERROR_CODES.INVALID_FINDINGS)
    }
    const ff = /** @type {Record<string, unknown>} */ (f)

    const severity = ff['severity']
    if (!SEVERITIES.includes(/** @type {any} */ (severity))) {
      return fail(rid, PARSE_ERROR_CODES.INVALID_FINDINGS)
    }

    const fAxisId = ff['axisId']
    if (typeof fAxisId !== 'string' || fAxisId.trim() === '') {
      return fail(rid, PARSE_ERROR_CODES.INVALID_FINDINGS)
    }
    if (!knownAxisIds.has(fAxisId)) {
      return fail(rid, PARSE_ERROR_CODES.INVALID_FINDINGS)
    }

    // Prose fields (title, evidence, recommendation) : validés, non conservés.
    // Un reviewer qui omet ces champs ou les laisse vides produit un output
    // invalide — la validité structurelle est vérifiée, mais le contenu ne
    // voyage pas dans le registre.
    const title = ff['title']
    if (typeof title !== 'string' || title.trim() === '') {
      return fail(rid, PARSE_ERROR_CODES.INVALID_FINDINGS)
    }

    const evidence = ff['evidence']
    if (typeof evidence !== 'string' || evidence.trim() === '') {
      return fail(rid, PARSE_ERROR_CODES.INVALID_FINDINGS)
    }

    const recommendation = ff['recommendation']
    if (typeof recommendation !== 'string' || recommendation.trim() === '') {
      return fail(rid, PARSE_ERROR_CODES.INVALID_FINDINGS)
    }

    /** @type {Finding} */
    const finding = {
      severity: /** @type {any} */ (severity),
      axisId: fAxisId,
      // title, evidence, recommendation : validés ci-dessus, NON stockés.
    }

    // Champs optionnels machines
    const file = ff['file']
    if (file !== undefined) {
      if (typeof file !== 'string' || file.trim() === '') {
        return fail(rid, PARSE_ERROR_CODES.INVALID_FINDINGS)
      }
      if (file.startsWith('/') || file.includes('..')) {
        return fail(rid, PARSE_ERROR_CODES.INVALID_FINDINGS)
      }
      finding.file = file.trim()
    }

    const line = ff['line']
    if (line !== undefined) {
      if (typeof line !== 'number' || !Number.isInteger(line) || line < 1) {
        return fail(rid, PARSE_ERROR_CODES.INVALID_FINDINGS)
      }
      finding.line = line
    }

    findings.push(finding)
  }

  // --- sensitiveAreas (optionnel) ---
  /** @type {string[] | undefined} */
  let sensitiveAreas
  const rawSensitive = r['sensitiveAreas']
  if (rawSensitive !== undefined) {
    if (!Array.isArray(rawSensitive)) {
      return fail(rid, PARSE_ERROR_CODES.INVALID_SENSITIVE_AREAS)
    }
    sensitiveAreas = []
    for (let i = 0; i < rawSensitive.length; i++) {
      const s = rawSensitive[i]
      if (typeof s !== 'string' || s.trim() === '') {
        return fail(rid, PARSE_ERROR_CODES.INVALID_SENSITIVE_AREAS)
      }
      sensitiveAreas.push(s.trim())
    }
  }

  // --- artifactDescriptor (optionnel, fourni par l'adaptateur BMAD) ---
  // content est supprimé même s'il est présent : il ne doit pas entrer dans le registre.
  /** @type {{ path: string, hash: string } | undefined} */
  let artifactDescriptor
  const rawAd = r['artifactDescriptor']
  if (rawAd !== undefined) {
    if (!rawAd || typeof rawAd !== 'object' || Array.isArray(rawAd)) {
      return fail(rid, PARSE_ERROR_CODES.INVALID_ARTIFACT)
    }
    const ad = /** @type {Record<string, unknown>} */ (rawAd)
    const adPath = ad['path']
    const adHash = ad['hash']
    if (typeof adPath !== 'string' || adPath.trim() === '') {
      return fail(rid, PARSE_ERROR_CODES.INVALID_ARTIFACT)
    }
    if (typeof adHash !== 'string' || adHash.trim() === '') {
      return fail(rid, PARSE_ERROR_CODES.INVALID_ARTIFACT)
    }
    // content intentionnellement omis.
    artifactDescriptor = { path: adPath.trim(), hash: adHash.trim() }
  }

  /** @type {ReviewResult} */
  const result = {
    reviewerId: rid,
    subjectType: /** @type {any} */ (subjectType),
    verdict: /** @type {any} */ (verdict),
    scores,
    findings,
  }
  if (sensitiveAreas !== undefined) result.sensitiveAreas = sensitiveAreas
  if (artifactDescriptor !== undefined) result.artifactDescriptor = artifactDescriptor

  return { ok: true, reviewerId: rid, result }
}

/**
 * @param {string|undefined} reviewerId
 * @param {string} errorCode
 * @returns {ParseOutcome}
 */
function fail(reviewerId, errorCode) {
  /** @type {ParseOutcome} */
  const o = { ok: false, errorCode }
  if (reviewerId !== undefined) o.reviewerId = reviewerId
  return o
}

// ---------------------------------------------------------------------------
// aggregateReviews
// ---------------------------------------------------------------------------

/**
 * Agrège plusieurs résultats de revue en un verdict déterministe.
 *
 * - Tout outcome invalide ⇒ `verdict: 'invalid-output'` immédiat.
 * - Les `errors` de l'ancienne API sont remplacés par `errorCodes` (codes
 *   machines) et `invalidReviewerIds` (identifiants des reviewers en échec).
 *   Aucune prose LLM ne circule dans ces champs.
 *
 * @param {ParseOutcome[]} outcomes
 * @param {ReviewConfig} config
 * @returns {AggregateResult}
 */
export function aggregateReviews(outcomes, config) {
  const reviewerCount = outcomes.length

  /** @type {string[]} */
  const errorCodes = []
  /** @type {string[]} */
  const invalidReviewerIds = []
  let invalidCount = 0

  /** @type {ReviewResult[]} */
  const valid = []
  for (const o of outcomes) {
    if (!o.ok) {
      invalidCount++
      if (o.errorCode) errorCodes.push(o.errorCode)
      if (o.reviewerId) invalidReviewerIds.push(o.reviewerId)
    } else {
      valid.push(/** @type {ReviewResult} */ (o.result))
    }
  }

  // Tout outcome invalide ⇒ échec d'instrument, pas de dégradation silencieuse.
  if (invalidCount > 0 || valid.length === 0) {
    /** @type {string[]} */
    const codes = invalidCount > 0 ? errorCodes : ['NO_RESULTS']
    return {
      verdict: 'invalid-output',
      reviewerCount,
      invalidCount,
      vetoFired: false,
      vetoAxes: [],
      aggregateScore: null,
      axisScores: {},
      findingCounts: { blocking: 0, major: 0, minor: 0, info: 0 },
      sensitiveAreas: [],
      reviewerIds: valid.map((r) => r.reviewerId),
      invalidReviewerIds,
      errorCodes: codes,
      artifactDescriptors: [],
    }
  }

  // --- Axis index ---
  /** @type {Map<string, AxisDef>} */
  const axisMap = new Map(config.axes.map((a) => [a.id, a]))

  // --- All findings ---
  /** @type {Finding[]} */
  const allFindings = valid.flatMap((r) => r.findings)

  // --- Finding counts ---
  const findingCounts = { blocking: 0, major: 0, minor: 0, info: 0 }
  for (const f of allFindings) findingCounts[f.severity]++

  // --- Veto check ---
  let vetoFired = false
  /** @type {string[]} */
  const vetoAxes = []
  for (const f of allFindings) {
    if (f.severity === 'blocking') {
      const axis = axisMap.get(f.axisId)
      if (axis?.veto && !vetoAxes.includes(f.axisId)) {
        vetoFired = true
        vetoAxes.push(f.axisId)
      }
    }
  }

  // --- Verdict ---
  /** @type {'approve' | 'request-changes' | 'reject'} */
  let verdict = 'approve'

  if (vetoFired) {
    verdict = 'reject'
  } else {
    for (const r of valid) {
      if (r.verdict === 'reject') { verdict = 'reject'; break }
      if (r.verdict === 'request-changes' && verdict !== 'reject') verdict = 'request-changes'
    }
    // Escalation : finding sévère déclaré malgré verdict lénient.
    if (verdict === 'approve' && (findingCounts.blocking > 0 || findingCounts.major > 0)) {
      verdict = 'request-changes'
    }
  }

  // --- Score aggregation ---
  // Scores sont des indicateurs de risque, jamais des overrides de verdict.
  /** @type {Record<string, number>} */
  const axisScores = {}
  for (const axis of config.axes) {
    const vals = valid.flatMap((r) => r.scores)
      .filter((s) => s.axisId === axis.id)
      .map((s) => s.score)
    if (vals.length > 0) axisScores[axis.id] = vals.reduce((a, b) => a + b, 0) / vals.length
  }

  let weightedSum = 0, totalWeight = 0
  for (const axis of config.axes) {
    const score = axisScores[axis.id]
    if (score !== undefined) {
      const w = axis.weight ?? 1
      weightedSum += score * w
      totalWeight += w
    }
  }
  const aggregateScore = totalWeight > 0 ? weightedSum / totalWeight : null

  // --- Sensitive areas ---
  const sensitiveSet = new Set()
  for (const r of valid) for (const s of r.sensitiveAreas ?? []) sensitiveSet.add(s)
  const sensitiveAreas = [...sensitiveSet].sort()

  // --- Reviewer IDs ---
  const reviewerIds = valid.map((r) => r.reviewerId)

  // --- Artifact descriptors ---
  const artifactDescriptors = valid
    .map((r) => r.artifactDescriptor)
    .filter(/** @type {(x: any) => x is { path: string, hash: string }} */ (x) => x !== undefined)

  return {
    verdict,
    reviewerCount,
    invalidCount: 0,
    vetoFired,
    vetoAxes,
    aggregateScore,
    axisScores,
    findingCounts,
    sensitiveAreas,
    reviewerIds,
    invalidReviewerIds: [],
    errorCodes: [],
    artifactDescriptors,
  }
}

// ---------------------------------------------------------------------------
// toReviewFacts
// ---------------------------------------------------------------------------

/**
 * Projette un `AggregateResult` et des métadonnées de révision en un objet
 * plat sûr pour `passPhase`/`failPhase` `facts`.
 *
 * ## Garanties
 *
 * - Aucune prose LLM : pas de title, evidence, recommendation.
 * - Aucun champ réinscriptible : pas de `kind`, `name`, `status`, `durationMs`.
 *   (Ces champs sont écrits par `endPhase` et ne peuvent pas être écrasés
 *   car les facts sont placés sous la clé `facts` par registry.mjs.)
 * - Budgets de révision injectés par le workflow via `revisionMeta`,
 *   jamais extraits de l'output du reviewer.
 * - Sur `invalid-output` : seuls des codes machines et des identifiants
 *   sont inclus. Pas de message d'erreur brut.
 *
 * ## Schéma produit
 *
 * ```
 * reviewVerdict          : string  ('approve'|'request-changes'|'reject'|'invalid-output')
 * reviewVetoFired        : boolean
 * reviewVetoAxes         : string[]
 * reviewAggregateScore   : number|null
 * reviewAxisScores       : Record<string, number>
 * reviewFindingCounts    : { blocking, major, minor, info }
 * reviewSensitiveAreas   : string[]
 * reviewerCount          : number
 * reviewerIds            : string[]
 * invalidReviewerIds     : string[]
 * reviewErrorCodes       : string[]
 * artifactPaths          : string[]
 * artifactHashes         : string[]
 * revision               : number
 * attempt                : number
 * maxRevisions           : number
 * maxAttempts            : number
 * ```
 *
 * @param {AggregateResult} aggregate
 * @param {RevisionMeta} revisionMeta  Fourni par le workflow, jamais par le reviewer.
 * @returns {ReviewFacts}
 */
export function toReviewFacts(aggregate, revisionMeta) {
  return {
    reviewVerdict:        aggregate.verdict,
    reviewVetoFired:      aggregate.vetoFired,
    reviewVetoAxes:       aggregate.vetoAxes,
    reviewAggregateScore: aggregate.aggregateScore,
    reviewAxisScores:     aggregate.axisScores,
    reviewFindingCounts:  aggregate.findingCounts,
    reviewSensitiveAreas: aggregate.sensitiveAreas,
    reviewerCount:        aggregate.reviewerCount,
    reviewerIds:          aggregate.reviewerIds,
    invalidReviewerIds:   aggregate.invalidReviewerIds,
    reviewErrorCodes:     aggregate.errorCodes,
    artifactPaths:        aggregate.artifactDescriptors.map((d) => d.path),
    artifactHashes:       aggregate.artifactDescriptors.map((d) => d.hash),
    revision:             revisionMeta.revision,
    attempt:              revisionMeta.attempt,
    maxRevisions:         revisionMeta.maxRevisions,
    maxAttempts:          revisionMeta.maxAttempts,
  }
}
