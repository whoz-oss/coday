/**
 * Tests unitaires pour factory/lib/review.mjs.
 *
 * Couvre parseReviewResult, aggregateReviews et toReviewFacts.
 * Aucun I/O, aucun appel AgentOS, aucune dépendance externe.
 *
 * Organisation :
 *   Bloc A — parseReviewResult : cas valides
 *   Bloc B — parseReviewResult : rejets et codes d'erreur
 *   Bloc C — parseReviewResult : champs optionnels et frontière BMAD
 *   Bloc D — parseReviewResult : champs interdits (budgets de révision)
 *   Bloc E — parseReviewResult : étanchéité de la prose LLM
 *   Bloc F — aggregateReviews : verdict et score
 *   Bloc G — aggregateReviews : politique de véto
 *   Bloc H — aggregateReviews : invalid-output
 *   Bloc I — aggregateReviews : métadonnées (findingCounts, sensitiveAreas, ids)
 *   Bloc J — toReviewFacts : schéma et invariants de registre
 *   Bloc K — toReviewFacts : étanchéité complète (pas de prose, pas de champs racine)
 *
 * Usage : node factory/tests/test-review.mjs
 * Code de sortie : 0 = tous les cas passent, 1 = au moins un échec.
 */

import {
  parseReviewResult,
  aggregateReviews,
  toReviewFacts,
  SUBJECT_TYPES,
  SEVERITIES,
  PARSE_ERROR_CODES,
} from '../lib/review.mjs'

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

function expectOk(name, outcome) {
  const ok = outcome.ok === true && outcome.result !== undefined
  const icon = ok ? '\u2713' : '\u2717'
  console.log(`${icon} ${name}`)
  if (!ok) console.log(`  errorCode: ${outcome.errorCode}`)
  if (ok) passed++
  else failed++
}

function expectFail(name, outcome, expectedCode) {
  const ok = outcome.ok === false
  const icon = ok ? '\u2713' : '\u2717'
  console.log(`${icon} ${name}`)
  if (!ok) {
    console.log(`  attendu ok=false, obtenu ok=true`)
    failed++
    return
  }
  if (expectedCode && outcome.errorCode !== expectedCode) {
    console.log(`  code attendu: "${expectedCode}", obtenu: "${outcome.errorCode}"`)
    failed++
    return
  }
  passed++
}

// Sentinel : chaîne de prose LLM représentative
const PROSE_SENTINEL = 'This is LLM-generated prose that must never reach the JSONL registry'

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

const BASE_CONFIG = {
  subjectType: 'implementation',
  axes: [
    { id: 'correctness', weight: 2, veto: true },
    { id: 'code-quality', weight: 1, veto: false },
  ],
}

const SPEC_CONFIG = {
  subjectType: 'technical-spec',
  axes: [
    { id: 'completeness', weight: 1 },
    { id: 'feasibility', weight: 1 },
    { id: 'consistency', weight: 1, veto: true },
  ],
}

/** ReviewResult brut minimal valide. */
const VALID_RAW = {
  reviewerId: 'reviewer-a',
  subjectType: 'implementation',
  verdict: 'approve',
  scores: [
    { axisId: 'correctness', score: 4 },
    { axisId: 'code-quality', score: 3 },
  ],
  findings: [],
}

/** ReviewResult brut avec findings (prose dans title/evidence/recommendation). */
const RAW_WITH_FINDINGS = {
  reviewerId: 'reviewer-b',
  subjectType: 'implementation',
  verdict: 'request-changes',
  scores: [
    { axisId: 'correctness', score: 2 },
    { axisId: 'code-quality', score: 3 },
  ],
  findings: [
    {
      severity: 'major',
      axisId: 'correctness',
      title: PROSE_SENTINEL + ' [title]',
      evidence: PROSE_SENTINEL + ' [evidence]',
      recommendation: PROSE_SENTINEL + ' [recommendation]',
      file: 'src/user.ts',
      line: 42,
    },
    {
      severity: 'minor',
      axisId: 'code-quality',
      title: 'Short variable name',
      evidence: 'Variable u used in loop',
      recommendation: 'Rename to user',
    },
  ],
}

/** ReviewResult brut avec finding blocking sur axe veto. */
const RAW_VETO = {
  reviewerId: 'reviewer-c',
  subjectType: 'implementation',
  verdict: 'reject',
  scores: [
    { axisId: 'correctness', score: 1 },
    { axisId: 'code-quality', score: 3 },
  ],
  findings: [
    {
      severity: 'blocking',
      axisId: 'correctness',
      title: 'SQL injection',
      evidence: 'User input concatenated into SQL',
      recommendation: 'Use parameterized queries',
      file: 'src/db.ts',
      line: 88,
    },
  ],
}

/** RevisionMeta standard pour les tests toReviewFacts. */
const REV_META = { revision: 1, attempt: 2, maxRevisions: 2, maxAttempts: 3 }

// ---------------------------------------------------------------------------
// Bloc A — parseReviewResult : cas valides
// ---------------------------------------------------------------------------

console.log('\n=== A : parseReviewResult — cas valides ===\n')

{
  const o = parseReviewResult(VALID_RAW, BASE_CONFIG)
  expectOk('A1 : résultat minimal valide', o)
  expect('A1 : reviewerId dans outcome', o.reviewerId, 'reviewer-a')
  expect('A1 : reviewerId dans result', o.result?.reviewerId, 'reviewer-a')
  expect('A1 : subjectType', o.result?.subjectType, 'implementation')
  expect('A1 : verdict', o.result?.verdict, 'approve')
  expect('A1 : scores.length', o.result?.scores.length, 2)
  expect('A1 : findings.length', o.result?.findings.length, 0)
}

{
  const o = parseReviewResult(RAW_WITH_FINDINGS, BASE_CONFIG)
  expectOk('A2 : résultat avec findings', o)
  expect('A2 : findings.length', o.result?.findings.length, 2)
  const f0 = o.result?.findings[0]
  expect('A2 : finding[0].severity', f0?.severity, 'major')
  expect('A2 : finding[0].axisId', f0?.axisId, 'correctness')
  expect('A2 : finding[0].file', f0?.file, 'src/user.ts')
  expect('A2 : finding[0].line', f0?.line, 42)
  const f1 = o.result?.findings[1]
  expectTrue('A2 : finding[1].file absent', f1?.file === undefined)
  expectTrue('A2 : finding[1].line absent', f1?.line === undefined)
}

{
  // Scores partiels : un axe sur deux
  const raw = { ...VALID_RAW, scores: [{ axisId: 'correctness', score: 5 }] }
  const o = parseReviewResult(raw, BASE_CONFIG)
  expectOk('A3 : scores partiels', o)
  expect('A3 : scores.length', o.result?.scores.length, 1)
}

{
  // subjectType technical-spec
  const raw = {
    reviewerId: 'spec-r',
    subjectType: 'technical-spec',
    verdict: 'approve',
    scores: [{ axisId: 'completeness', score: 4 }],
    findings: [],
  }
  const o = parseReviewResult(raw, SPEC_CONFIG)
  expectOk('A4 : subjectType technical-spec', o)
  expect('A4 : subjectType préservé', o.result?.subjectType, 'technical-spec')
}

{
  // Scores aux bornes
  const raw = {
    ...VALID_RAW,
    scores: [
      { axisId: 'correctness', score: 1 },
      { axisId: 'code-quality', score: 5 },
    ],
  }
  const o = parseReviewResult(raw, BASE_CONFIG)
  expectOk('A5 : scores aux bornes (1 et 5)', o)
}

// ---------------------------------------------------------------------------
// Bloc B — parseReviewResult : rejets et codes d'erreur
// ---------------------------------------------------------------------------

console.log('\n=== B : parseReviewResult — rejets et codes d\'erreur ===\n')

{
  expectFail('B1 : null → NOT_AN_OBJECT', parseReviewResult(null, BASE_CONFIG), PARSE_ERROR_CODES.NOT_AN_OBJECT)
  expectFail('B2 : tableau → NOT_AN_OBJECT', parseReviewResult([], BASE_CONFIG), PARSE_ERROR_CODES.NOT_AN_OBJECT)
  expectFail('B3 : string → NOT_AN_OBJECT', parseReviewResult('ok', BASE_CONFIG), PARSE_ERROR_CODES.NOT_AN_OBJECT)
}

{
  expectFail('B4 : reviewerId manquant → INVALID_REVIEWER_ID', parseReviewResult({ ...VALID_RAW, reviewerId: undefined }, BASE_CONFIG), PARSE_ERROR_CODES.INVALID_REVIEWER_ID)
  expectFail('B5 : reviewerId vide → INVALID_REVIEWER_ID', parseReviewResult({ ...VALID_RAW, reviewerId: '  ' }, BASE_CONFIG), PARSE_ERROR_CODES.INVALID_REVIEWER_ID)
  // reviewerId absent du ParseOutcome sur NOT_AN_OBJECT (pas encore lu)
  const o = parseReviewResult(null, BASE_CONFIG)
  expectTrue('B6 : reviewerId absent si NOT_AN_OBJECT', o.reviewerId === undefined)
}

{
  expectFail('B7 : subjectType invalide → INVALID_SUBJECT_TYPE', parseReviewResult({ ...VALID_RAW, subjectType: 'unknown' }, BASE_CONFIG), PARSE_ERROR_CODES.INVALID_SUBJECT_TYPE)
  // reviewerId présent dans le ParseOutcome même si subjectType invalide
  const o = parseReviewResult({ ...VALID_RAW, subjectType: 'unknown' }, BASE_CONFIG)
  expect('B8 : reviewerId présent sur INVALID_SUBJECT_TYPE', o.reviewerId, 'reviewer-a')
}

{
  expectFail('B9 : verdict invalide → INVALID_VERDICT', parseReviewResult({ ...VALID_RAW, verdict: 'maybe' }, BASE_CONFIG), PARSE_ERROR_CODES.INVALID_VERDICT)
}

{
  expectFail('B10 : scores non tableau → INVALID_SCORES', parseReviewResult({ ...VALID_RAW, scores: 'bad' }, BASE_CONFIG), PARSE_ERROR_CODES.INVALID_SCORES)
  const raw2 = { ...VALID_RAW, scores: [{ axisId: 'unknown-axis', score: 3 }] }
  expectFail('B11 : axisId inconnu dans scores → INVALID_SCORES', parseReviewResult(raw2, BASE_CONFIG), PARSE_ERROR_CODES.INVALID_SCORES)
  const raw3 = { ...VALID_RAW, scores: [{ axisId: 'correctness', score: 6 }] }
  expectFail('B12 : score > 5 → INVALID_SCORES', parseReviewResult(raw3, BASE_CONFIG), PARSE_ERROR_CODES.INVALID_SCORES)
  const raw4 = { ...VALID_RAW, scores: [{ axisId: 'correctness', score: 0 }] }
  expectFail('B13 : score < 1 → INVALID_SCORES', parseReviewResult(raw4, BASE_CONFIG), PARSE_ERROR_CODES.INVALID_SCORES)
  const raw5 = {
    ...VALID_RAW,
    scores: [
      { axisId: 'correctness', score: 3 },
      { axisId: 'correctness', score: 4 },
    ],
  }
  expectFail('B14 : axisId dupliqué → INVALID_SCORES', parseReviewResult(raw5, BASE_CONFIG), PARSE_ERROR_CODES.INVALID_SCORES)
}

{
  expectFail('B15 : findings non tableau → INVALID_FINDINGS', parseReviewResult({ ...VALID_RAW, findings: null }, BASE_CONFIG), PARSE_ERROR_CODES.INVALID_FINDINGS)

  const mkF = (overrides) => ({
    ...VALID_RAW,
    findings: [{ severity: 'minor', axisId: 'correctness', title: 'T', evidence: 'E', recommendation: 'R', ...overrides }],
  })

  expectFail('B16 : severity invalide → INVALID_FINDINGS', parseReviewResult(mkF({ severity: 'critical' }), BASE_CONFIG), PARSE_ERROR_CODES.INVALID_FINDINGS)
  expectFail('B17 : axisId inconnu dans finding → INVALID_FINDINGS', parseReviewResult(mkF({ axisId: 'nope' }), BASE_CONFIG), PARSE_ERROR_CODES.INVALID_FINDINGS)
  expectFail('B18 : title vide → INVALID_FINDINGS', parseReviewResult(mkF({ title: '' }), BASE_CONFIG), PARSE_ERROR_CODES.INVALID_FINDINGS)
  expectFail('B19 : evidence vide → INVALID_FINDINGS', parseReviewResult(mkF({ evidence: '' }), BASE_CONFIG), PARSE_ERROR_CODES.INVALID_FINDINGS)
  expectFail('B20 : recommendation vide → INVALID_FINDINGS', parseReviewResult(mkF({ recommendation: '' }), BASE_CONFIG), PARSE_ERROR_CODES.INVALID_FINDINGS)
  expectFail('B21 : file chemin absolu → INVALID_FINDINGS', parseReviewResult(mkF({ file: '/etc/passwd' }), BASE_CONFIG), PARSE_ERROR_CODES.INVALID_FINDINGS)
  expectFail('B22 : file traversal → INVALID_FINDINGS', parseReviewResult(mkF({ file: '../secret' }), BASE_CONFIG), PARSE_ERROR_CODES.INVALID_FINDINGS)
  expectFail('B23 : line = 0 → INVALID_FINDINGS', parseReviewResult(mkF({ file: 'src/a.ts', line: 0 }), BASE_CONFIG), PARSE_ERROR_CODES.INVALID_FINDINGS)
}

// ---------------------------------------------------------------------------
// Bloc C — parseReviewResult : champs optionnels et frontière BMAD
// ---------------------------------------------------------------------------

console.log('\n=== C : parseReviewResult — champs optionnels et BMAD ===\n')

{
  const raw = { ...VALID_RAW, sensitiveAreas: ['auth', 'payments'] }
  const o = parseReviewResult(raw, BASE_CONFIG)
  expectOk('C1 : sensitiveAreas valide', o)
  expect('C1 : sensitiveAreas', o.result?.sensitiveAreas, ['auth', 'payments'])
}

{
  const raw = { ...VALID_RAW, sensitiveAreas: [42] }
  expectFail('C2 : sensitiveAreas[0] non string → INVALID_SENSITIVE_AREAS', parseReviewResult(raw, BASE_CONFIG), PARSE_ERROR_CODES.INVALID_SENSITIVE_AREAS)
}

{
  // artifactDescriptor avec content : content supprimé
  const raw = {
    ...VALID_RAW,
    artifactDescriptor: {
      path: 'docs/spec.md',
      hash: 'sha256:abc123',
      content: PROSE_SENTINEL,
    },
  }
  const o = parseReviewResult(raw, BASE_CONFIG)
  expectOk('C3 : artifactDescriptor avec content : valide', o)
  expect('C3 : path conservé', o.result?.artifactDescriptor?.path, 'docs/spec.md')
  expect('C3 : hash conservé', o.result?.artifactDescriptor?.hash, 'sha256:abc123')
  expectTrue('C3 : content absent du ReviewResult', !('content' in (o.result?.artifactDescriptor ?? {})))
}

{
  const raw = { ...VALID_RAW, artifactDescriptor: { path: '', hash: 'h' } }
  expectFail('C4 : artifactDescriptor.path vide → INVALID_ARTIFACT', parseReviewResult(raw, BASE_CONFIG), PARSE_ERROR_CODES.INVALID_ARTIFACT)
  const raw2 = { ...VALID_RAW, artifactDescriptor: { path: 'p', hash: '' } }
  expectFail('C5 : artifactDescriptor.hash vide → INVALID_ARTIFACT', parseReviewResult(raw2, BASE_CONFIG), PARSE_ERROR_CODES.INVALID_ARTIFACT)
}

{
  // artifactDescriptor sans content : valide
  const raw = { ...VALID_RAW, artifactDescriptor: { path: 'src/a.ts', hash: 'sha256:abc' } }
  const o = parseReviewResult(raw, BASE_CONFIG)
  expectOk('C6 : artifactDescriptor sans content : valide', o)
}

// ---------------------------------------------------------------------------
// Bloc D — parseReviewResult : champs interdits (budgets de révision)
// ---------------------------------------------------------------------------

console.log('\n=== D : parseReviewResult — champs interdits ===\n')

const FORBIDDEN_FIELDS = ['revisionBudget', 'revision', 'attempt', 'maxRevisions', 'maxAttempts']
for (const field of FORBIDDEN_FIELDS) {
  const raw = { ...VALID_RAW, [field]: 42 }
  expectFail(`D : champ interdit "${field}" → FORBIDDEN_FIELD`, parseReviewResult(raw, BASE_CONFIG), PARSE_ERROR_CODES.FORBIDDEN_FIELD)
  // reviewerId doit être présent même en cas de FORBIDDEN_FIELD
  const o = parseReviewResult(raw, BASE_CONFIG)
  expect(`D : reviewerId présent sur FORBIDDEN_FIELD "${field}"`, o.reviewerId, 'reviewer-a')
}

// ---------------------------------------------------------------------------
// Bloc E — parseReviewResult : étanchéité de la prose LLM
// ---------------------------------------------------------------------------

console.log('\n=== E : parseReviewResult — étanchéité prose LLM ===\n')

{
  // Prose dans title/evidence/recommendation : validée mais non conservée.
  const o = parseReviewResult(RAW_WITH_FINDINGS, BASE_CONFIG)
  expectOk('E1 : parse avec prose dans findings réussit', o)

  const serialized = JSON.stringify(o.result)

  // La sentinel ne doit apparaître nulle part dans le ReviewResult sérialisé.
  expectFalse('E1 : PROSE_SENTINEL absent de ReviewResult sérialisé', serialized.includes(PROSE_SENTINEL))

  // Les champs prose ne doivent pas être dans les findings
  const f0 = o.result?.findings[0]
  expectTrue('E1 : title absent de Finding', !('title' in (f0 ?? {})))
  expectTrue('E1 : evidence absent de Finding', !('evidence' in (f0 ?? {})))
  expectTrue('E1 : recommendation absent de Finding', !('recommendation' in (f0 ?? {})))

  // Les champs machines sont présents
  expect('E1 : severity présent', f0?.severity, 'major')
  expect('E1 : axisId présent', f0?.axisId, 'correctness')
  expect('E1 : file présent', f0?.file, 'src/user.ts')
  expect('E1 : line présent', f0?.line, 42)
}

{
  // content de artifactDescriptor : non conservé
  const raw = {
    ...VALID_RAW,
    artifactDescriptor: { path: 'src/a.ts', hash: 'sha256:abc', content: PROSE_SENTINEL },
  }
  const o = parseReviewResult(raw, BASE_CONFIG)
  const serialized = JSON.stringify(o.result)
  expectFalse('E2 : content de artifactDescriptor absent du ReviewResult', serialized.includes(PROSE_SENTINEL))
}

// ---------------------------------------------------------------------------
// Bloc F — aggregateReviews : verdict et score
// ---------------------------------------------------------------------------

console.log('\n=== F : aggregateReviews — verdict et score ===\n')

{
  const outcomes = [parseReviewResult(VALID_RAW, BASE_CONFIG)]
  const r = aggregateReviews(outcomes, BASE_CONFIG)
  expect('F1 : verdict approve', r.verdict, 'approve')
  expect('F1 : reviewerCount', r.reviewerCount, 1)
  expect('F1 : invalidCount', r.invalidCount, 0)
  expectFalse('F1 : vetoFired false', r.vetoFired)
  expect('F1 : vetoAxes vide', r.vetoAxes, [])
  // Score : correctness=4 (w=2), code-quality=3 (w=1) → (8+3)/3 = 11/3
  expect('F1 : aggregateScore', Math.round((r.aggregateScore ?? 0) * 1000) / 1000, Math.round(11/3 * 1000) / 1000)
  expect('F1 : axisScores.correctness', r.axisScores['correctness'], 4)
  expect('F1 : axisScores.code-quality', r.axisScores['code-quality'], 3)
  expect('F1 : reviewerIds', r.reviewerIds, ['reviewer-a'])
}

{
  const outcomes = [
    parseReviewResult(VALID_RAW, BASE_CONFIG),
    parseReviewResult(RAW_WITH_FINDINGS, BASE_CONFIG),
  ]
  const r = aggregateReviews(outcomes, BASE_CONFIG)
  expect('F2 : verdict request-changes (le plus sévère)', r.verdict, 'request-changes')
  expect('F2 : reviewerCount', r.reviewerCount, 2)
  // correctness : (4+2)/2 = 3
  expect('F2 : axisScores.correctness moyenne', r.axisScores['correctness'], 3)
  expect('F2 : reviewerIds', r.reviewerIds, ['reviewer-a', 'reviewer-b'])
}

{
  // Verdict approve mais finding major → escalade
  const raw = {
    ...VALID_RAW,
    verdict: 'approve',
    findings: [{ severity: 'major', axisId: 'correctness', title: 'T', evidence: 'E', recommendation: 'R' }],
  }
  const r = aggregateReviews([parseReviewResult(raw, BASE_CONFIG)], BASE_CONFIG)
  expect('F3 : escalade approve → request-changes sur finding major', r.verdict, 'request-changes')
}

{
  // Scores partiels : reviewer A = correctness, reviewer B = code-quality
  const rawA = { ...VALID_RAW, reviewerId: 'a', scores: [{ axisId: 'correctness', score: 4 }] }
  const rawB = { ...VALID_RAW, reviewerId: 'b', scores: [{ axisId: 'code-quality', score: 2 }] }
  const r = aggregateReviews([
    parseReviewResult(rawA, BASE_CONFIG),
    parseReviewResult(rawB, BASE_CONFIG),
  ], BASE_CONFIG)
  expect('F4 : axisScores.correctness (A seulement)', r.axisScores['correctness'], 4)
  expect('F4 : axisScores.code-quality (B seulement)', r.axisScores['code-quality'], 2)
  // (4*2 + 2*1) / (2+1) = 10/3
  expect('F4 : aggregateScore pondéré', Math.round((r.aggregateScore ?? 0) * 1000) / 1000, Math.round(10/3 * 1000) / 1000)
}

{
  const raw = { ...VALID_RAW, scores: [] }
  const r = aggregateReviews([parseReviewResult(raw, BASE_CONFIG)], BASE_CONFIG)
  expect('F5 : aggregateScore null si aucun score', r.aggregateScore, null)
}

{
  // Score élevé sur axe veto ne sauve pas le verdict
  const raw = {
    ...VALID_RAW,
    verdict: 'reject',
    scores: [{ axisId: 'correctness', score: 5 }, { axisId: 'code-quality', score: 5 }],
    findings: [{ severity: 'blocking', axisId: 'correctness', title: 'T', evidence: 'E', recommendation: 'R' }],
  }
  const r = aggregateReviews([parseReviewResult(raw, BASE_CONFIG)], BASE_CONFIG)
  expect('F6 : score élevé sur axe veto ne sauve pas le verdict', r.verdict, 'reject')
  expectTrue('F6 : vetoFired', r.vetoFired)
}

// ---------------------------------------------------------------------------
// Bloc G — aggregateReviews : politique de véto
// ---------------------------------------------------------------------------

console.log('\n=== G : aggregateReviews — véto ===\n')

{
  const r = aggregateReviews([parseReviewResult(RAW_VETO, BASE_CONFIG)], BASE_CONFIG)
  expect('G1 : verdict reject sur véto', r.verdict, 'reject')
  expectTrue('G1 : vetoFired', r.vetoFired)
  expect('G1 : vetoAxes', r.vetoAxes, ['correctness'])
}

{
  // Blocking sur axe NON veto → request-changes (pas reject)
  const raw = {
    ...VALID_RAW,
    verdict: 'request-changes',
    findings: [{ severity: 'blocking', axisId: 'code-quality', title: 'T', evidence: 'E', recommendation: 'R' }],
  }
  const r = aggregateReviews([parseReviewResult(raw, BASE_CONFIG)], BASE_CONFIG)
  expect('G2 : blocking sur axe non-veto → request-changes', r.verdict, 'request-changes')
  expectFalse('G2 : vetoFired false', r.vetoFired)
  expect('G2 : vetoAxes vide', r.vetoAxes, [])
}

{
  // Véto d'un reviewer parmi deux
  const r = aggregateReviews([
    parseReviewResult(VALID_RAW, BASE_CONFIG),
    parseReviewResult(RAW_VETO, BASE_CONFIG),
  ], BASE_CONFIG)
  expect('G3 : véto d\'un reviewer parmi deux → reject', r.verdict, 'reject')
  expectTrue('G3 : vetoFired', r.vetoFired)
}

{
  // Véto sur SPEC_CONFIG (axe consistency)
  const raw = {
    reviewerId: 'spec-r',
    subjectType: 'technical-spec',
    verdict: 'reject',
    scores: [{ axisId: 'consistency', score: 1 }],
    findings: [{ severity: 'blocking', axisId: 'consistency', title: 'T', evidence: 'E', recommendation: 'R' }],
  }
  const r = aggregateReviews([parseReviewResult(raw, SPEC_CONFIG)], SPEC_CONFIG)
  expect('G4 : véto consistency (SPEC_CONFIG)', r.verdict, 'reject')
  expect('G4 : vetoAxes', r.vetoAxes, ['consistency'])
}

// ---------------------------------------------------------------------------
// Bloc H — aggregateReviews : invalid-output
// ---------------------------------------------------------------------------

console.log('\n=== H : aggregateReviews — invalid-output ===\n')

{
  // Un invalide parmi deux → invalid-output
  const outcomes = [
    parseReviewResult(VALID_RAW, BASE_CONFIG),
    { ok: false, reviewerId: 'reviewer-x', errorCode: PARSE_ERROR_CODES.INVALID_FINDINGS },
  ]
  const r = aggregateReviews(outcomes, BASE_CONFIG)
  expect('H1 : un invalide parmi deux → invalid-output', r.verdict, 'invalid-output')
  expect('H1 : invalidCount', r.invalidCount, 1)
  expect('H1 : errorCodes', r.errorCodes, [PARSE_ERROR_CODES.INVALID_FINDINGS])
  expect('H1 : invalidReviewerIds', r.invalidReviewerIds, ['reviewer-x'])
}

{
  // Tous invalides
  const outcomes = [
    { ok: false, reviewerId: 'r1', errorCode: PARSE_ERROR_CODES.INVALID_VERDICT },
    { ok: false, reviewerId: 'r2', errorCode: PARSE_ERROR_CODES.FORBIDDEN_FIELD },
  ]
  const r = aggregateReviews(outcomes, BASE_CONFIG)
  expect('H2 : tous invalides → invalid-output', r.verdict, 'invalid-output')
  expect('H2 : invalidCount', r.invalidCount, 2)
  expect('H2 : errorCodes', r.errorCodes, [PARSE_ERROR_CODES.INVALID_VERDICT, PARSE_ERROR_CODES.FORBIDDEN_FIELD])
  expect('H2 : invalidReviewerIds', r.invalidReviewerIds, ['r1', 'r2'])
}

{
  // Liste vide → invalid-output avec code NO_RESULTS
  const r = aggregateReviews([], BASE_CONFIG)
  expect('H3 : liste vide → invalid-output', r.verdict, 'invalid-output')
  expect('H3 : reviewerCount', r.reviewerCount, 0)
  expect('H3 : errorCodes', r.errorCodes, ['NO_RESULTS'])
}

{
  // invalid-output : aggregateScore null, axisScores vide
  const r = aggregateReviews([{ ok: false, errorCode: PARSE_ERROR_CODES.NOT_AN_OBJECT }], BASE_CONFIG)
  expect('H4 : aggregateScore null', r.aggregateScore, null)
  expect('H4 : axisScores vide', r.axisScores, {})
  expect('H4 : vetoAxes vide', r.vetoAxes, [])
}

{
  // errorCodes ne doit contenir que des codes machines, pas de prose
  const outcomes = [
    { ok: false, reviewerId: 'r1', errorCode: PARSE_ERROR_CODES.INVALID_FINDINGS },
  ]
  const r = aggregateReviews(outcomes, BASE_CONFIG)
  for (const code of r.errorCodes) {
    // Un code machine est une clé de PARSE_ERROR_CODES ou 'NO_RESULTS'
    const validCodes = [...Object.values(PARSE_ERROR_CODES), 'NO_RESULTS']
    expectTrue(`H5 : errorCode "${code}" est un code machine valide`, validCodes.includes(code))
    expectFalse(`H5 : errorCode "${code}" ne contient pas de prose`, code.includes(' '))
  }
}

// ---------------------------------------------------------------------------
// Bloc I — aggregateReviews : métadonnées
// ---------------------------------------------------------------------------

console.log('\n=== I : aggregateReviews — métadonnées ===\n')

{
  const r = aggregateReviews([
    parseReviewResult(RAW_WITH_FINDINGS, BASE_CONFIG),  // 1 major, 1 minor
    parseReviewResult(RAW_VETO, BASE_CONFIG),           // 1 blocking
  ], BASE_CONFIG)
  expect('I1 : findingCounts.blocking', r.findingCounts.blocking, 1)
  expect('I1 : findingCounts.major', r.findingCounts.major, 1)
  expect('I1 : findingCounts.minor', r.findingCounts.minor, 1)
  expect('I1 : findingCounts.info', r.findingCounts.info, 0)
}

{
  // sensitiveAreas : union dédupliquée triée
  const rawA = { ...VALID_RAW, reviewerId: 'a', sensitiveAreas: ['payments', 'auth'] }
  const rawB = { ...VALID_RAW, reviewerId: 'b', sensitiveAreas: ['auth', 'logging'] }
  const r = aggregateReviews([
    parseReviewResult(rawA, BASE_CONFIG),
    parseReviewResult(rawB, BASE_CONFIG),
  ], BASE_CONFIG)
  expect('I2 : sensitiveAreas union triée', r.sensitiveAreas, ['auth', 'logging', 'payments'])
}

{
  // errorCodes vide sur succès
  const r = aggregateReviews([parseReviewResult(VALID_RAW, BASE_CONFIG)], BASE_CONFIG)
  expect('I3 : errorCodes vide sur succès', r.errorCodes, [])
  expect('I3 : invalidReviewerIds vide', r.invalidReviewerIds, [])
}

{
  // artifactDescriptors agrégés
  const rawA = { ...VALID_RAW, reviewerId: 'a', artifactDescriptor: { path: 'src/a.ts', hash: 'sha256:aaa' } }
  const rawB = { ...VALID_RAW, reviewerId: 'b', artifactDescriptor: { path: 'src/b.ts', hash: 'sha256:bbb' } }
  const r = aggregateReviews([
    parseReviewResult(rawA, BASE_CONFIG),
    parseReviewResult(rawB, BASE_CONFIG),
  ], BASE_CONFIG)
  expect('I4 : artifactDescriptors.length', r.artifactDescriptors.length, 2)
  expect('I4 : artifactDescriptors[0].path', r.artifactDescriptors[0].path, 'src/a.ts')
  expect('I4 : artifactDescriptors[1].hash', r.artifactDescriptors[1].hash, 'sha256:bbb')
}

// ---------------------------------------------------------------------------
// Bloc J — toReviewFacts : schéma et invariants de registre
// ---------------------------------------------------------------------------

console.log('\n=== J : toReviewFacts — schéma et invariants ===\n')

{
  const outcomes = [parseReviewResult(VALID_RAW, BASE_CONFIG)]
  const aggregate = aggregateReviews(outcomes, BASE_CONFIG)
  const facts = toReviewFacts(aggregate, REV_META)

  // Schéma complet
  expect('J1 : reviewVerdict', facts.reviewVerdict, 'approve')
  expect('J1 : reviewVetoFired', facts.reviewVetoFired, false)
  expect('J1 : reviewVetoAxes', facts.reviewVetoAxes, [])
  expect('J1 : reviewAggregateScore', Math.round((facts.reviewAggregateScore ?? 0) * 1000) / 1000, Math.round(11/3 * 1000) / 1000)
  expectTrue('J1 : reviewAxisScores est objet', typeof facts.reviewAxisScores === 'object')
  expect('J1 : reviewAxisScores.correctness', facts.reviewAxisScores['correctness'], 4)
  expect('J1 : reviewFindingCounts.blocking', facts.reviewFindingCounts.blocking, 0)
  expect('J1 : reviewSensitiveAreas', facts.reviewSensitiveAreas, [])
  expect('J1 : reviewerCount', facts.reviewerCount, 1)
  expect('J1 : reviewerIds', facts.reviewerIds, ['reviewer-a'])
  expect('J1 : invalidReviewerIds', facts.invalidReviewerIds, [])
  expect('J1 : reviewErrorCodes', facts.reviewErrorCodes, [])
  expect('J1 : artifactPaths', facts.artifactPaths, [])
  expect('J1 : artifactHashes', facts.artifactHashes, [])
  // Métadonnées de révision injectées par le workflow
  expect('J1 : revision', facts.revision, 1)
  expect('J1 : attempt', facts.attempt, 2)
  expect('J1 : maxRevisions', facts.maxRevisions, 2)
  expect('J1 : maxAttempts', facts.maxAttempts, 3)
}

{
  // toReviewFacts sur invalid-output
  const r = aggregateReviews([], BASE_CONFIG)
  const facts = toReviewFacts(r, REV_META)
  expect('J2 : reviewVerdict invalid-output', facts.reviewVerdict, 'invalid-output')
  expect('J2 : reviewErrorCodes', facts.reviewErrorCodes, ['NO_RESULTS'])
  expect('J2 : reviewerIds vide', facts.reviewerIds, [])
  expect('J2 : revision présent même sur invalid-output', facts.revision, REV_META.revision)
}

{
  // toReviewFacts avec artifactDescriptors
  const raw = { ...VALID_RAW, artifactDescriptor: { path: 'src/a.ts', hash: 'sha256:abc' } }
  const facts = toReviewFacts(aggregateReviews([parseReviewResult(raw, BASE_CONFIG)], BASE_CONFIG), REV_META)
  expect('J3 : artifactPaths', facts.artifactPaths, ['src/a.ts'])
  expect('J3 : artifactHashes', facts.artifactHashes, ['sha256:abc'])
}

{
  // Compatibilité avec l'invariant registry.mjs :
  // facts est placé sous la clé `facts` par endPhase — les champs racine
  // (kind, name, status, durationMs) ne peuvent pas être écrasés.
  // Vérification : aucun de ces champs ne doit apparaître dans ReviewFacts.
  const facts = toReviewFacts(aggregateReviews([parseReviewResult(VALID_RAW, BASE_CONFIG)], BASE_CONFIG), REV_META)
  const IMMUTABLE_ROOT_FIELDS = ['kind', 'name', 'status', 'durationMs']
  for (const field of IMMUTABLE_ROOT_FIELDS) {
    expectTrue(`J4 : champ racine "${field}" absent de ReviewFacts`, !(field in facts))
  }
}

// ---------------------------------------------------------------------------
// Bloc K — toReviewFacts : étanchéité complète
// ---------------------------------------------------------------------------

console.log('\n=== K : toReviewFacts — étanchéité complète ===\n')

{
  // Prose LLM dans findings : ne doit pas apparaître dans les faits JSONL.
  const o = parseReviewResult(RAW_WITH_FINDINGS, BASE_CONFIG)
  const facts = toReviewFacts(aggregateReviews([o], BASE_CONFIG), REV_META)
  const serialized = JSON.stringify(facts)

  expectFalse('K1 : PROSE_SENTINEL absent des faits JSONL', serialized.includes(PROSE_SENTINEL))

  // Les champs de prose ne doivent pas apparaître dans les faits
  expectFalse('K1 : "title" absent des faits', serialized.includes('"title"'))
  expectFalse('K1 : "evidence" absent des faits', serialized.includes('"evidence"'))
  expectFalse('K1 : "recommendation" absent des faits', serialized.includes('"recommendation"'))

  // Les champs machines sont présents
  expectTrue('K1 : "reviewVerdict" présent', serialized.includes('"reviewVerdict"'))
  expectTrue('K1 : "reviewFindingCounts" présent', serialized.includes('"reviewFindingCounts"'))
}

{
  // content de artifactDescriptor ne doit pas apparaître dans les faits
  const raw = {
    ...VALID_RAW,
    artifactDescriptor: { path: 'src/a.ts', hash: 'sha256:abc', content: PROSE_SENTINEL },
  }
  const facts = toReviewFacts(
    aggregateReviews([parseReviewResult(raw, BASE_CONFIG)], BASE_CONFIG),
    REV_META
  )
  const serialized = JSON.stringify(facts)
  expectFalse('K2 : content BMAD absent des faits', serialized.includes(PROSE_SENTINEL))
}

{
  // Erreurs de parsing : codes machines uniquement dans les faits
  const outcomes = [
    { ok: false, reviewerId: 'r1', errorCode: PARSE_ERROR_CODES.FORBIDDEN_FIELD },
  ]
  const facts = toReviewFacts(aggregateReviews(outcomes, BASE_CONFIG), REV_META)
  const serialized = JSON.stringify(facts)

  // Aucune prose dans les faits d'erreur
  expectFalse('K3 : pas de prose dans les faits d\'erreur', serialized.includes(' '))
  // Les codes machines sont présents
  expectTrue('K3 : FORBIDDEN_FIELD présent dans reviewErrorCodes', facts.reviewErrorCodes.includes(PARSE_ERROR_CODES.FORBIDDEN_FIELD))
  // reviewerIds invalides présents
  expect('K3 : invalidReviewerIds', facts.invalidReviewerIds, ['r1'])
}

{
  // Champs racine du registre absents des faits (invariant registry.mjs)
  const facts = toReviewFacts(
    aggregateReviews([parseReviewResult(VALID_RAW, BASE_CONFIG)], BASE_CONFIG),
    { revision: 2, attempt: 1, maxRevisions: 3, maxAttempts: 5 }
  )
  const IMMUTABLE = ['kind', 'name', 'status', 'durationMs', 'startedAt', 'endedAt']
  for (const field of IMMUTABLE) {
    expectTrue(`K4 : champ racine "${field}" absent des faits`, !(field in facts))
  }
}

// ---------------------------------------------------------------------------
// Résultat
// ---------------------------------------------------------------------------

console.log('')
console.log(`Résultat : ${passed} passé(s), ${failed} échoué(s)`)
process.exit(failed > 0 ? 1 : 0)
