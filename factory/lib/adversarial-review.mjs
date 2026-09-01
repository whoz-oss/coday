/**
 * Moteur de revue adversariale — 4 AdversarialReviewers en parallèle.
 *
 * ## Ce que ce module fait
 *
 * `runAdversarialReview(params)` :
 * 1. Compose un review packet immuable (spec, diff, résultats oracles, claims-gate).
 * 2. Lance 4 cases AgentOS en parallèle, un par reviewer.
 * 3. Parse les réponses markdown : extrait VERDICT (PASS/FAIL) et section CRITICAL.
 * 4. Agrège : global PASS si tous PASS, FAIL si au moins un CRITICAL.
 * 5. Retourne un résultat structuré pour logging JSONL.
 *
 * ## Ce que ce module ne fait PAS
 *
 * - Écrire dans le registre JSONL (c'est us-loop qui le fait).
 * - Choisir les transitions de workflow.
 * - Appeler provision-reviewers.mjs (les agents doivent exister au préalable).
 *
 * ## Format de sortie des reviewers
 *
 * Chaque reviewer répond en markdown libre avec :
 *   ### VERDICT
 *   PASS ou FAIL
 *   ### CRITICAL (blocking)
 *   ...
 *   ### WARNINGS (non-blocking)
 *   ...
 *   ### NOTES
 *   ...
 *
 * ## Agrégation
 *
 * - verdict global = PASS si tous les 4 rendent PASS
 * - verdict global = FAIL si au moins un reviewer rend FAIL
 * - Si un reviewer ne peut pas être interrogé (AgentOS indisponible, timeout,
 *   etc.) : warning logé, ce reviewer est compté comme SKIP et n'impacte pas
 *   le verdict. La factory reste propriétaire de la décision finale.
 *
 * ## SIGTERM
 *
 * Les 4 cases sont enregistrés dans active-case.mjs dès leur création.
 * Le handler SIGTERM de shutdown.mjs les tue en best-effort via getActiveCaseIds().
 *
 * ## Ordre déterministe
 *
 * Les résultats sont retournés dans l'ordre des reviewers définis ci-dessous,
 * indépendamment de l'ordre de complétion AgentOS.
 */

import { createCase, runAgentTurn, killCase } from './agentos.mjs'
import { registerActiveCase, unregisterActiveCase } from './active-case.mjs'

// ---------------------------------------------------------------------------
// Constantes
// ---------------------------------------------------------------------------

/** Budget par défaut par reviewer (15 minutes). */
const DEFAULT_REVIEWER_TIMEOUT_MS = 15 * 60 * 1000

/** Budget de démarrage : temps pour voir le case passer à RUNNING. */
const DEFAULT_START_TIMEOUT_MS = 30_000

/** Noms des 4 agents adversariaux. */
export const ADVERSARIAL_REVIEWER_NAMES = [
  'AdversarialReviewer1',
  'AdversarialReviewer2',
  'AdversarialReviewer3',
  'AdversarialReviewer4',
]

// ---------------------------------------------------------------------------
// Types JSDoc
// ---------------------------------------------------------------------------

/**
 * @typedef {{
 *   spec?: string,           // Contenu de la spec (optionnel)
 *   diff: string,            // git diff exact
 *   oracleResults: Array<{   // Résultats des oracles (type-check, tests)
 *     name: string,
 *     exitCode: number,
 *     passed: boolean,
 *     tail: string,          // Derniers extraits de sortie
 *   }>,
 *   claimsGate: {            // Résultat du claims-gate
 *     claimsMatch: boolean,
 *     plannedFiles: string[],
 *     actualFiles: string[],
 *     unplannedFiles: string[],
 *     untouchedPlannedFiles: string[],
 *   },
 *   task: string,            // La tâche originale
 * }} ReviewPacket
 *
 * @typedef {{
 *   reviewerName: string,
 *   caseId: string|null,
 *   status: 'pass' | 'fail' | 'skip',
 *   verdict: 'PASS' | 'FAIL' | null,
 *   hasCritical: boolean,
 *   rawOutput: string|null,
 *   errorCode: string|null,
 * }} ReviewerOutcome
 *
 * @typedef {{
 *   ok: boolean,
 *   globalVerdict: 'PASS' | 'FAIL' | 'SKIP',
 *   reviewerCount: number,
 *   passCount: number,
 *   failCount: number,
 *   skipCount: number,
 *   outcomes: ReviewerOutcome[],
 *   rawOutputs: Record<string, string|null>,
 * }} AdversarialReviewResult
 */

// ---------------------------------------------------------------------------
// API publique
// ---------------------------------------------------------------------------

/**
 * Lance la revue adversariale en parallèle et retourne un résultat structuré.
 *
 * @param {{
 *   namespaceId: string,
 *   reviewPacket: ReviewPacket,
 *   timeoutMs?: number,
 *   startTimeoutMs?: number,
 * }} params
 * @returns {Promise<AdversarialReviewResult>}
 */
export async function runAdversarialReview(params) {
  const {
    namespaceId,
    reviewPacket,
    timeoutMs = DEFAULT_REVIEWER_TIMEOUT_MS,
    startTimeoutMs = DEFAULT_START_TIMEOUT_MS,
  } = params

  const brief = buildReviewPacketBrief(reviewPacket)

  // Map caseId → reviewerName pour le cleanup en cas d'échec partiel.
  /** @type {Map<string, string>} */
  const activeCases = new Map()

  // Lancer les 4 reviewers en parallèle, dans l'ordre défini.
  const promises = ADVERSARIAL_REVIEWER_NAMES.map((reviewerName) =>
    runOneAdversarialReviewer({
      namespaceId,
      reviewerName,
      brief,
      timeoutMs,
      startTimeoutMs,
      activeCases,
    })
  )

  const settled = await Promise.allSettled(promises)

  // Collecter les résultats dans l'ordre d'entrée.
  /** @type {ReviewerOutcome[]} */
  const outcomes = []
  for (let i = 0; i < settled.length; i++) {
    const s = settled[i]
    if (s.status === 'fulfilled') {
      outcomes.push(s.value)
    } else {
      // Exception non attrapée dans runOneAdversarialReviewer (ne devrait pas arriver).
      outcomes.push({
        reviewerName: ADVERSARIAL_REVIEWER_NAMES[i],
        caseId: null,
        status: 'skip',
        verdict: null,
        hasCritical: false,
        rawOutput: null,
        errorCode: 'UNEXPECTED_EXCEPTION',
      })
    }
  }

  // Cleanup des cases encore actifs (best-effort).
  if (activeCases.size > 0) {
    await killActiveCases(activeCases)
  }

  // Raw outputs pour affichage humain.
  /** @type {Record<string, string|null>} */
  const rawOutputs = {}
  for (const o of outcomes) {
    rawOutputs[o.reviewerName] = o.rawOutput
  }

  // Agrégation.
  const passCount = outcomes.filter((o) => o.verdict === 'PASS').length
  const failCount = outcomes.filter((o) => o.verdict === 'FAIL').length
  const skipCount = outcomes.filter((o) => o.status === 'skip').length

  // PASS global si tous les reviewers ayant rendu un verdict disent PASS.
  // FAIL global si au moins un reviewer dit FAIL.
  // SKIP si tous les reviewers ont été skipés (AgentOS indisponible).
  let globalVerdict
  if (failCount > 0) {
    globalVerdict = 'FAIL'
  } else if (passCount > 0) {
    globalVerdict = 'PASS'
  } else {
    globalVerdict = 'SKIP'
  }

  return {
    ok: globalVerdict !== 'FAIL',
    globalVerdict,
    reviewerCount: ADVERSARIAL_REVIEWER_NAMES.length,
    passCount,
    failCount,
    skipCount,
    outcomes,
    rawOutputs,
  }
}

// ---------------------------------------------------------------------------
// runOneAdversarialReviewer
// ---------------------------------------------------------------------------

/**
 * Exécute un reviewer adversarial et retourne son outcome.
 *
 * @param {{
 *   namespaceId: string,
 *   reviewerName: string,
 *   brief: string,
 *   timeoutMs: number,
 *   startTimeoutMs: number,
 *   activeCases: Map<string, string>,
 * }} params
 * @returns {Promise<ReviewerOutcome>}
 */
async function runOneAdversarialReviewer(params) {
  const { namespaceId, reviewerName, brief, timeoutMs, startTimeoutMs, activeCases } = params

  /** @type {string|null} */
  let caseId = null

  try {
    // Créer le case.
    let created
    try {
      created = await createCase(namespaceId, `review:${reviewerName}`)
    } catch (err) {
      // AgentOS indisponible : skip ce reviewer, ne pas bloquer les autres.
      return skip(reviewerName, null, 'CREATE_CASE_FAILED')
    }
    caseId = created.id

    // Enregistrer dans les deux registres pour le SIGTERM.
    activeCases.set(caseId, reviewerName)
    registerActiveCase(caseId, `review:${reviewerName}`)

    // Lancer le tour d'agent.
    const turn = await runAgentTurn(caseId, reviewerName, brief, {
      startTimeoutMs,
      workTimeoutMs: timeoutMs,
    })

    // Le turn est terminé : retirer du registre local.
    activeCases.delete(caseId)

    // Statuts non-finished : skip (pas de verdict exploitable).
    if (turn.status !== 'finished') {
      return skip(reviewerName, caseId, `TURN_STATUS_${turn.status.toUpperCase()}`)
    }

    const rawOutput = turn.message ?? ''

    // Parser la réponse markdown.
    const parsed = parseAdversarialOutput(rawOutput)

    // Si le verdict n'a pas pu être parsé, on retourne skip (pas pass) :
    // un outcome sans verdict n'est ni PASS ni FAIL et ne doit pas compter
    // dans passCount. Le status 'skip' déclenche correctement skipCount.
    if (parsed.verdict === null) {
      return skip(reviewerName, caseId, 'NO_VERDICT_FOUND')
    }

    return {
      reviewerName,
      caseId,
      status: parsed.verdict === 'FAIL' ? 'fail' : 'pass',
      verdict: parsed.verdict,
      hasCritical: parsed.hasCritical,
      rawOutput,
      errorCode: null,
    }

  } catch (err) {
    // Exception réseau ou autre : skip, ne pas bloquer les autres.
    return skip(reviewerName, caseId, 'UNEXPECTED_ERROR')
  } finally {
    // Désenregistrement global SIGTERM : toujours exécuté.
    if (caseId !== null) {
      unregisterActiveCase(caseId)
    }
  }
}

// ---------------------------------------------------------------------------
// Parsing du format markdown adversarial
// ---------------------------------------------------------------------------

/**
 * Parse la sortie markdown d'un reviewer adversarial.
 *
 * Extrait :
 * - verdict : 'PASS' | 'FAIL' | null (si introuvable)
 * - hasCritical : true si la section CRITICAL contient autre chose que "None."
 *
 * @param {string} text
 * @returns {{ verdict: 'PASS' | 'FAIL' | null, hasCritical: boolean }}
 */
function parseAdversarialOutput(text) {
  // Chercher le verdict : ligne contenant PASS ou FAIL (après # / ## / ### VERDICT).
  // On accepte **PASS**, **FAIL**, ou juste PASS/FAIL.
  // Le modèle peut produire H1, H2 ou H3 selon ses préférences de formatage.
  const verdictMatch = text.match(/#{1,3}\s*VERDICT\b[\s\S]*?\b(PASS|FAIL)\b/i)
  const verdictRaw = verdictMatch ? verdictMatch[1].toUpperCase() : null
  const verdict = verdictRaw === 'PASS' || verdictRaw === 'FAIL' ? verdictRaw : null

  // Chercher la section CRITICAL.
  // Elle commence après # / ## / ### CRITICAL et se termine avant la section suivante (# de n'importe quel niveau).
  const criticalMatch = text.match(/#{1,3}\s*CRITICAL[^\n]*\n([\s\S]*?)(?=#{1,3}|$)/i)
  let hasCritical = false
  if (criticalMatch) {
    const criticalContent = criticalMatch[1].trim()
    // "None." ou vide = pas de critical.
    hasCritical = criticalContent.length > 0 && !/^none\.?$/i.test(criticalContent)
  }

  return { verdict, hasCritical }
}

// ---------------------------------------------------------------------------
// Construction du review packet
// ---------------------------------------------------------------------------

/**
 * Construit le brief immuable envoyé à chaque reviewer.
 * Identique pour les 4 : même snapshot.
 *
 * @param {ReviewPacket} packet
 * @returns {string}
 */
function buildReviewPacketBrief(packet) {
  const sections = []

  sections.push('## Review Packet\n\nThis packet is immutable. Do not attempt to fetch additional context.')

  sections.push(`## Task\n${packet.task}`)

  if (packet.spec) {
    sections.push(`## Specification\n\`\`\`\n${packet.spec}\n\`\`\``)
  }

  sections.push(`## Git Diff\n\`\`\`diff\n${packet.diff}\n\`\`\``)

  if (packet.oracleResults.length > 0) {
    const oracleLines = packet.oracleResults.map((o) => {
      const status = o.passed ? 'PASS' : 'FAIL'
      return [
        `### Oracle: ${o.name} — ${status} (exit ${o.exitCode})`,
        o.tail ? `\`\`\`\n${o.tail}\n\`\`\`` : '(no output)',
      ].join('\n')
    }).join('\n\n')
    sections.push(`## Oracle Results\n\n${oracleLines}`)
  }

  const claimsLines = [
    `claimsMatch: ${packet.claimsGate.claimsMatch}`,
    `plannedFiles: ${packet.claimsGate.plannedFiles.join(', ') || '(none)'}`,
    `actualFiles: ${packet.claimsGate.actualFiles.join(', ') || '(none)'}`,
  ]
  if (packet.claimsGate.unplannedFiles.length > 0) {
    claimsLines.push(`unplannedFiles: ${packet.claimsGate.unplannedFiles.join(', ')}`)
  }
  if (packet.claimsGate.untouchedPlannedFiles.length > 0) {
    claimsLines.push(`untouchedPlannedFiles: ${packet.claimsGate.untouchedPlannedFiles.join(', ')}`)
  }
  sections.push(`## Claims Gate\n\`\`\`\n${claimsLines.join('\n')}\n\`\`\``)

  if (Array.isArray(packet.quarantinedOracles) && packet.quarantinedOracles.length > 0) {
    const qLines = packet.quarantinedOracles.map((q) => [
      `### Quarantined Oracle: ${q.oracleName} (${q.classification})`,
      `Reason: ${q.reason}`,
      `Human decision: ${q.humanDecision}${q.humanMessage ? ` — "${q.humanMessage}"` : ''}`,
      `Oracle remains failed/quarantined. Not rewritten as passed.`,
    ].join('\n')).join('\n\n')
    sections.push(`## Quarantined Oracles (failed, not rewritten as passed)\n\n${qLines}`)
  }

  return sections.join('\n\n')
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Construit un outcome "skip" (reviewer non interrogé ou échec non-bloquant).
 *
 * @param {string} reviewerName
 * @param {string|null} caseId
 * @param {string} errorCode
 * @returns {ReviewerOutcome}
 */
function skip(reviewerName, caseId, errorCode) {
  return {
    reviewerName,
    caseId,
    status: 'skip',
    verdict: null,
    hasCritical: false,
    rawOutput: null,
    errorCode,
  }
}

/**
 * Tue tous les cases encore actifs (best-effort).
 *
 * @param {Map<string, string>} activeCases
 */
async function killActiveCases(activeCases) {
  const kills = [...activeCases.keys()].map(async (caseId) => {
    try {
      await killCase(caseId)
    } catch {
      // best-effort
    }
  })
  await Promise.allSettled(kills)
  activeCases.clear()
}
