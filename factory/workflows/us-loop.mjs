/**
 * Workflow us-loop — analyste + éditeur en boucle structurée.
 *
 * Ce workflow déroule la chaîne qu'un agent architecte faisait par délégation :
 * analyser d'abord, implémenter ensuite, avec deux portes de vérification.
 *
 * ## Séquence
 *
 *   préflight     code  — deux rôles + colocalisation de l'éditeur
 *     ↓
 *   ┌─ révision R ─────────────────────────────────────────────────────────┐
 *   │ analyse-R     agent  — l'analyste (LECTURE SEULE) rend un plan JSON   │
 *   │ plan-gate-R   code   — les fichiers cités dans le plan existent-ils ? │
 *   │                                                                        │
 *   │ ┌─ tentative T ──────────────────────────────────────────────────┐    │
 *   │ │ edit-R-T    agent  — l'éditeur reçoit le plan et implémente    │    │
 *   │ │ verify-R-T  code   — l'oracle, verdict = exitCode === 0         │    │
 *   │ └──────────────────────────────────────────────────────────────┘    │
 *   │   échec → tentative T+1 (budget MAX_FIX_LOOPS)                       │
 *   │   épuisement des tentatives → révision R+1 (budget MAX_REVISION_LOOPS) │
 *   │                                                                        │
 *   │ claims-gate-R code  — diff réel vs fichiers annoncés par le plan     │
 *   └───────────────────────────────────────────────────────────────────┘
 *
 * ## Nommage des phases
 *
 * Le premier indice est la révision (boucle externe, retour à l'analyste),
 * le second est la tentative de correction (boucle interne, éditeur seul).
 * Les phases de vérification portent en plus le nom de l'oracle :
 *
 *   analyse-1, plan-gate-1, edit-1-1,
 *   verify-types-1-1, verify-tests-1-1,
 *   edit-1-2, verify-types-1-2, verify-tests-1-2 ...
 *   claims-gate-1
 *   analyse-2, plan-gate-2, edit-2-1,
 *   verify-types-2-1, verify-tests-2-1 ...
 *   claims-gate-2
 *
 * Si le type-check échoue, la phase `verify-tests-R-T` n'existe pas dans le
 * registre pour cette tentative : une phase non exécutée n'est ni pass ni fail.
 *
 * ## Deux boucles, deux raisons
 *
 * MAX_FIX_LOOPS (boucle interne) : une erreur de compilation est un fait local.
 * L'éditeur l'encaisse et corrige. Rouvrir le plan à chaque erreur rouvrirait
 * des décisions déjà prises, à grands frais.
 *
 * MAX_REVISION_LOOPS (boucle externe) : si trois tentatives échouent,
 * l'hypothèse « le plan est faux » devient la plus probable. On remonte
 * alors à l'analyste avec les erreurs accumulées en entrée.
 *
 * ## Le plan voyage en mémoire, jamais sur disque
 *
 * runAgentTurn retourne un champ `message` (produit par extractLastAgentMessage).
 * Le workflow lit le plan DEPUIS CE CHAMP et le passe au brief de l'éditeur.
 * L'analyste étant en readOnly, il ne PEUT pas écrire. Le workflow ne doit pas
 * non plus écrire le plan sur disque de son côté : snapshotDiff le compterait
 * comme une écriture, la garde wroteNothing serait satisfaite alors qu'aucun
 * code n'a changé.
 *
 * ## Un case NEUF par tentative
 *
 * Même raison que dans fix-loop : réutiliser un case conserverait le récit de
 * l'agent sur ce qu'il croit avoir fait, qui entrerait en concurrence avec les
 * faits. Avec un case neuf, l'agent reçoit l'état du dossier et l'erreur brute.
 * Effet de bord utile : un case neuf est trivialement quiescent, la garde
 * `case_busy` de `runAgentTurn` ne peut pas se déclencher.
 *
 * ## Variables d'environnement
 *
 *   FACTORY_NAMESPACE_ID    — namespace AgentOS (requis)
 *   FACTORY_TASK            — la tâche à accomplir (requis si FACTORY_TICKET absent)
 *   FACTORY_DOMAIN          — `back` ou `front` (défaut : `front`)
 *   FACTORY_SCOPE           — périmètre autorisé, texte libre (optionnel)
 *   FACTORY_AGENT_ANALYST   — nom du rôle analyste (défaut : factory-analyst)
 *   FACTORY_AGENT_EDITOR    — nom du rôle éditeur (défaut : factory-editor)
 *   FACTORY_ROOT            — racine du dépôt cible (optionnel)
 *   FACTORY_TICKET          — identifiant ou URL du ticket Jira (optionnel)
 *   JIRA_BASE_URL           — ex. https://monentreprise.atlassian.net (requis si FACTORY_TICKET présent)
 *   JIRA_EMAIL              — email du compte Jira (requis si FACTORY_TICKET présent)
 *   JIRA_API_TOKEN          — token API Jira (requis si FACTORY_TICKET présent)
 *
 * NOTE : FACTORY_AGENT n'est pas utilisé par ce workflow. Deux rôles distincts
 * sont nécessaires : l'analyste ne peut pas écrire, l'éditeur ne produit pas
 * de plan structuré.
 */

import { createRun, startPhase, passPhase, failPhase, endRun } from '../lib/registry.mjs'
import { runCommand, snapshotDiff, diffSince, countTaskOutcomes } from '../lib/oracle.mjs'
import { createCase, runAgentTurn, preflightAgent, preflightWorkspace } from '../lib/agentos.mjs'
import { parsePlan, checkPlanFiles, compareClaims } from '../lib/plan.mjs'
import { domains } from '../lib/domains.mjs'
import { buildOracleCommand, resolveOwnerProjects } from '../lib/oracle-command.mjs'
import { extractTicketId, extractAdfText, fetchJiraTicket } from '../lib/jira.mjs'
import { runAdversarialReview } from '../lib/adversarial-review.mjs'
import { emitGateOpen, emitOracleGateOpen, waitForHumanDecision } from '../lib/review-gate.mjs'
import {
  runBaselineOracle,
  classifyOracleResult,
  buildQuarantineRecord,
  extractOracleDiagnostics,
} from '../lib/oracle-baseline.mjs'
import { join, dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { execSync } from 'node:child_process'
import { existsSync } from 'node:fs'

const __dirname = dirname(fileURLToPath(import.meta.url))

/**
 * Racine du dépôt cible.
 * Doit être égale au rootPath de l'intégration FACTORY_FILES de l'éditeur.
 */
const REPO_ROOT = process.env.FACTORY_ROOT
  ? resolve(process.env.FACTORY_ROOT)
  : join(__dirname, '..', '..')

/**
 * Budget de la boucle interne : l'éditeur corrige seul.
 * Après MAX_FIX_LOOPS échecs, on remonte à l'analyste.
 */
const MAX_FIX_LOOPS = 3

/**
 * Budget de la boucle externe : retour à l'analyste.
 * Après MAX_REVISION_LOOPS épuisements de la boucle interne, le run échoue.
 */
const MAX_REVISION_LOOPS = 2

/**
 * Budget de reformulation du plan mal formé.
 * En cas d'échec de parsing, on redemande dans un case neuf jusqu'à
 * JSON_FIX_ATTEMPTS tentatives supplémentaires, puis on échoue.
 */
const JSON_FIX_ATTEMPTS = 2

/** Lignes d'erreur transmises à l'éditeur au tour suivant. */
const ERROR_LINES_FOR_AGENT = 60

/** Lignes conservées dans le registre en cas d'échec (diagnostic humain). */
const TAIL_LINES = 40

/** Budget de démarrage d'un tour d'agent. */
const START_TIMEOUT_MS = 30 * 1000

/** Budget de travail d'un tour d'agent. */
const WORK_TIMEOUT_MS = 15 * 60 * 1000

/** Budget d'une commande de vérité. */
const ORACLE_TIMEOUT_MS = 20 * 60 * 1000

// --------------------------------------------------------------------------
// Utilitaires
// --------------------------------------------------------------------------

/**
 * Retourne les N dernières lignes non vides d'une chaîne.
 *
 * @param {string} text
 * @param {number} n
 * @returns {string[]}
 */
function tailLines(text, n) {
  return text
    .split('\n')
    .filter((line) => line.trim().length > 0)
    .slice(-n)
}

/**
 * Extrait les lignes de diagnostic TypeScript d'une sortie de type-check.
 *
 * PROBLÈME : `pnpm nx run-many --target=type-check` émet les diagnostics TS
 * (fichier, ligne, colonne, code, message) dans stdout, mais le résumé Nx
 * ("Running target type-check for N projects failed", noms des projets) apparaît
 * en dernière position dans stderr ou en queue de stdout. Avec `tailLines`, ce
 * résumé déplace les lignes de diagnostic actionnables : l'éditeur reçoit
 * uniquement "type-check for 4 projects failed" sans aucun TS2345 ni position.
 *
 * STRATÉGIE :
 *   1. Chercher les lignes de diagnostic TS dans stdout (pattern `error TS`).
 *      Ces lignes incluent le chemin de fichier, la ligne/colonne et le message.
 *   2. Si trouvées, retourner les N dernières (bornées à maxLines).
 *   3. Si aucune ligne de diagnostic n'est trouvée dans stdout, fallback sur
 *      tailLines du source combiné (comportement original).
 *
 * Ce fallback garantit que le comportement est identique pour les oracles
 * non-types (Gradle, tests) qui n'ont pas de lignes `error TS`.
 *
 * @param {string} stdout
 * @param {string} stderr
 * @param {number} maxLines
 * @returns {string[]}
 */
export function extractTypeDiagnostics(stdout, stderr, maxLines) {
  // Les lignes de diagnostic TS contiennent `error TS` suivi d'un code numérique.
  // Format nominal : `path/to/file.ts(line,col): error TSxxxx: message`
  // On inclut aussi les lignes de contexte qui suivent immédiatement.
  const stdoutLines = stdout.split('\n')
  const diagnosticIndices = new Set()

  for (let i = 0; i < stdoutLines.length; i++) {
    if (stdoutLines[i].includes('error TS')) {
      // Inclure la ligne précédente si non vide (certains formateurs émettent
      // le chemin de fichier sur la ligne précédente).
      if (i > 0 && stdoutLines[i - 1].trim().length > 0) {
        diagnosticIndices.add(i - 1)
      }
      diagnosticIndices.add(i)
      // Inclure jusqu'à 2 lignes de contexte suivantes (annotation, caret `~`).
      if (i + 1 < stdoutLines.length && stdoutLines[i + 1].trim().length > 0) {
        diagnosticIndices.add(i + 1)
      }
      if (i + 2 < stdoutLines.length && stdoutLines[i + 2].trim().includes('~')) {
        diagnosticIndices.add(i + 2)
      }
    }
  }

  if (diagnosticIndices.size === 0) {
    // Aucun diagnostic TS dans stdout : fallback sur tailLines du source combiné.
    const source = stderr.trim().length > 0 ? stderr : stdout
    return tailLines(source, maxLines)
  }

  // Reconstruire la liste ordonnée, filtrer les vides, borner à maxLines.
  const sorted = [...diagnosticIndices].sort((a, b) => a - b)
  return sorted
    .map((i) => stdoutLines[i])
    .filter((line) => line.trim().length > 0)
    .slice(-maxLines)
}

/**
 * Extrait les lignes de diagnostic Jest/frontend-test actionnables depuis stdout.
 *
 * PROBLÈME : `pnpm nx run-many --target=frontend-test` émet les assertions Jest
 * (fichier, ligne, Expected/Received) dans stdout, suivi du résumé Nx en queue
 * ("Running target frontend-test for N projects failed"). Avec `tailLines`, ce
 * résumé déplace les blocs d'assertion actionnables hors de la fenêtre.
 *
 * STRATÉGIE :
 *   1. Chercher dans stdout les marqueurs de début de bloc d'échec Jest :
 *      `● <suite> › <test>` ou `● <suite> > <test>` (bullet Jest).
 *   2. Pour chaque marqueur trouvé, inclure les lignes suivantes jusqu'au
 *      prochain marqueur ou jusqu'à la fin du bloc (ligne vide suivie d'une
 *      ligne non indentée), en incluant les blocs Expected/Received et les
 *      références de fichier:ligne.
 *   3. Borner à maxLines.
 *   4. Fallback sur tailLines du source combiné si aucun marqueur trouvé.
 *
 * @param {string} stdout
 * @param {string} stderr
 * @param {number} maxLines
 * @returns {string[]}
 */
export function extractTestDiagnostics(stdout, stderr, maxLines) {
  // eslint-disable-next-line no-control-regex
  const plain = stdout.replace(/\u001b\[[0-9;]*m/g, '')
  const lines = plain.split('\n')

  // Marqueurs de début de bloc d'échec Jest.
  // Formats observés :
  //   ● <suite> › <test name>
  //   ● <suite> > <test name>
  //   FAIL <path>
  // On cherche le bullet Jest (●) et les références at <path>:<line>:<col>.
  const diagnosticIndices = new Set()

  for (let i = 0; i < lines.length; i++) {
    const trimmed = lines[i].trim()

    // Début de bloc d'échec Jest : bullet ●
    if (trimmed.startsWith('\u25cf ') || trimmed.startsWith('\u25cf\u25cf')) {
      diagnosticIndices.add(i)
      // Inclure les lignes suivantes jusqu'au prochain bullet ou ligne de résumé Nx.
      let j = i + 1
      while (j < lines.length) {
        const next = lines[j].trim()
        // Arrêt sur un autre bullet ou sur le résumé Nx (ligne de résumé vide ou "Running target").
        if (next.startsWith('\u25cf ') || next.includes('Running target') || next.includes('Failed tasks')) break
        diagnosticIndices.add(j)
        j++
      }
      continue
    }

    // Ligne FAIL <chemin> — entête de fichier de test échoué.
    if (trimmed.startsWith('FAIL ') && (trimmed.includes('.spec.') || trimmed.includes('.test.'))) {
      diagnosticIndices.add(i)
      continue
    }

    // Références at <chemin>:<ligne>:<col> dans les stack frames Jest.
    if (trimmed.startsWith('at ') && trimmed.includes('.spec.') || trimmed.startsWith('at ') && trimmed.includes('.test.')) {
      diagnosticIndices.add(i)
      continue
    }

    // Blocs Expected / Received (peuvent apparaître sans bullet si le bloc est long).
    if (trimmed.startsWith('Expected:') || trimmed.startsWith('Received:') ||
        trimmed.startsWith('Expected value') || trimmed.startsWith('Received value') ||
        trimmed.startsWith('expect(') || trimmed.startsWith('- Expected') || trimmed.startsWith('+ Received')) {
      // Inclure aussi la ligne précédente pour le contexte.
      if (i > 0) diagnosticIndices.add(i - 1)
      diagnosticIndices.add(i)
      if (i + 1 < lines.length) diagnosticIndices.add(i + 1)
      continue
    }
  }

  if (diagnosticIndices.size === 0) {
    // Aucun marqueur Jest dans stdout : fallback sur tailLines du source combiné.
    const source = stderr.trim().length > 0 ? stderr : stdout
    return tailLines(source, maxLines)
  }

  // Reconstruire la liste ordonnée, filtrer les vides, borner à maxLines.
  const sorted = [...diagnosticIndices].sort((a, b) => a - b)
  return sorted
    .map((i) => lines[i])
    .filter((line) => line.trim().length > 0)
    .slice(-maxLines)
}

/**
 * Extrait depuis des lignes de diagnostic les chemins de fichiers existants
 * dans le dépôt, pour augmenter le périmètre Files in scope du brief de retry.
 *
 * Recherche les chemins relatifs au dépôt dans les lignes de diagnostic.
 * Formats reconnus :
 *   - `path/to/file.ts(line,col):` (TypeScript)
 *   - `FAIL path/to/file.spec.ts`
 *   - `at Object.<anonymous> (path/to/file.spec.ts:41:5)`
 *   - `path/to/file.spec.ts:41:5`
 *
 * Filtre les chemins inexistants sur disque et déduplique.
 *
 * @param {string[]} errorLines  Lignes de diagnostic (oracleErrorLines).
 * @param {string}   repoRoot    Racine absolue du dépôt.
 * @returns {string[]}  Chemins relatifs existants, dédupliqués.
 */
export function extractReferencedFiles(errorLines, repoRoot) {
  if (!errorLines || errorLines.length === 0) return []

  const found = new Set()

  // Patterns de chemin dans les diagnostics :
  //   path/to/file.ts(42,7):       — TypeScript
  //   FAIL path/to/file.spec.ts    — Jest header
  //   (path/to/file.spec.ts:41:5)  — stack frame entre parenthèses
  //   path/to/file.spec.ts:41:5    — référence directe
  // On extrait la partie chemin (avant le premier `(` ou `:` de position).
  // La regex est réutilisée par ligne : lastIndex est remis à 0 à chaque ligne.
  const pathPattern = /([\w./\-@]+\.(?:ts|tsx|js|jsx))(?:[:(]|$)/g

  for (const line of errorLines) {
    pathPattern.lastIndex = 0
    let match
    while ((match = pathPattern.exec(line)) !== null) {
      const candidate = match[1]
      // Ignorer les chemins absolus ou avec ..
      if (candidate.startsWith('/') || candidate.includes('..')) continue
      found.add(candidate)
    }
  }

  // Filtrer les chemins qui n'existent pas sur disque.
  // existsSync est importé en tête de module (import statique).
  return [...found].filter((p) => {
    try {
      return existsSync(join(repoRoot, p))
    } catch {
      return false
    }
  })
}

// Human gate is now run-scoped. See lib/review-gate.mjs for the full architecture.
// emitGateOpen() signals the dashboard server via stdout IPC.
// waitForHumanDecision() polls factory/runs/<runId>.gate-reply — no timeout.

// --------------------------------------------------------------------------
// Gabarits de brief
//
// Les gabarits sont rendus PAR LE CODE, jamais par un modèle.
// Trois règles :
//   1. L'AGENT NE CONNAÎt PAS SON ORACLE. Ni l'analyste ni l'éditeur ne savent
//      quelle commande sera lancée. Un acteur qui connaît son oracle optimise
//      pour l'oracle.
//   2. LA SORTIE HONORABLE EST UNE CONSTANTE DU GABARIT. Dire explicitement
//      que rapporter « ce n'est pas le bon endroit » est un résultat acceptable.
//   3. Le brief de l'analyste dit qu'il est en lecture seule et qu'il ne doit
//      rien modifier, en plus de la contrainte technique.
// --------------------------------------------------------------------------

/**
 * Brief de la première analyse.
 *
 * @param {string|null} task
 * @param {string|null} scope
 * @param {string|null} ticketContent  Contenu du ticket Jira en markdown (optionnel).
 * @returns {string}
 */
function buildAnalystBrief(task, scope, ticketContent = null) {
  const sections = []

  if (ticketContent) {
    sections.push(`## Ticket\n${ticketContent}`)
  }

  if (task) {
    sections.push(`## Task\n${task}`)
  }

  if (scope) {
    sections.push(`## Scope\n${scope}`)
  }

  sections.push(
    '## Your role\n' +
    'You are a read-only analyst. Your job is to produce a structured plan ' +
    'that a separate editor agent will execute. You must NOT modify any file. ' +
    'You do not have write access and must not attempt to write anything.'
  )

  sections.push(
    '## Output format\n' +
    'Respond with a JSON object in a ```json code fence. Required fields:\n\n' +
    '```json\n' +
    '{\n' +
    '  "files": ["path/relative/to/repo/root.ts"],\n' +
    '  "doneWhen": "verifiable completion criterion",\n' +
    '  "steps": ["step 1", "step 2"]\n' +
    '}\n' +
    '```\n\n' +
    '- `files` : RELATIVE paths from repo root, files the editor will need to modify. Required, non-empty.\n' +
    '- `doneWhen` : required, non-empty.\n' +
    '- `steps` : optional ordered implementation steps.\n\n' +
    'Only list files that already exist. Do not list files that need to be created.'
  )

  sections.push(
    '## If this is not the right place\n' +
    'If the work required is outside the scope above, or if you determine the real ' +
    'problem lies elsewhere, say so explicitly and stop. Reporting "this is not the ' +
    'right place to fix it" is a successful outcome, not a failure.'
  )

  return sections.join('\n\n')
}

/**
 * Brief de l'analyste sur une révision (après épuisement des tentatives d'édition).
 *
 * @param {string} task
 * @param {string|null} scope
 * @param {string[]} errorLines  Derniers extraits du compilateur.
 * @param {number} revision
 * @returns {string}
 */
function buildRevisionAnalystBrief(task, scope, errorLines, revision) {
  const sections = [
    `## Task\n${task}`,
  ]

  if (scope) {
    sections.push(`## Scope\n${scope}`)
  }

  sections.push(
    '## Your role\n' +
    'You are a read-only analyst. Your job is to produce a revised structured plan ' +
    'that a separate editor agent will execute. You must NOT modify any file. ' +
    'You do not have write access and must not attempt to write anything.'
  )

  sections.push(
    '## Context\n' +
    `A previous plan (revision ${revision - 1}) was attempted but failed after ` +
    `${MAX_FIX_LOOPS} correction attempts. Read the current state of the files ` +
    `before producing a new plan — do not assume what was done.`
  )

  sections.push(
    `## Last compiler output\n\`\`\`\n${errorLines.join('\n')}\n\`\`\``
  )

  sections.push(
    '## Output format\n' +
    'Respond with a JSON object in a ```json code fence. Required fields:\n\n' +
    '```json\n' +
    '{\n' +
    '  "files": ["path/relative/to/repo/root.ts"],\n' +
    '  "doneWhen": "verifiable completion criterion",\n' +
    '  "steps": ["step 1", "step 2"]\n' +
    '}\n' +
    '```\n\n' +
    '- `files` : RELATIVE paths from repo root, files the editor will need to modify. Required, non-empty.\n' +
    '- `doneWhen` : required, non-empty.\n' +
    '- `steps` : optional ordered implementation steps.\n\n' +
    'Only list files that already exist. Do not list files that need to be created.'
  )

  sections.push(
    '## If this is not the right place\n' +
    'If the compiler output indicates the real problem lies outside the scope above, ' +
    'say so explicitly and stop. Reporting that is a successful outcome, not a failure.'
  )

  return sections.join('\n\n')
}

/**
 * Brief de reformulation de plan (format JSON invalide).
 *
 * @param {string} parseError  Message d'erreur de parsePlan.
 * @returns {string}
 */
function buildJsonFixBrief(parseError) {
  return [
    '## Plan format error\n' +
    'Your previous response could not be parsed as a valid plan. ' +
    `Error: ${parseError}`,

    '## Output format\n' +
    'Respond ONLY with a JSON object in a ```json code fence, nothing else:\n\n' +
    '```json\n' +
    '{\n' +
    '  "files": ["path/relative/to/repo/root.ts"],\n' +
    '  "doneWhen": "verifiable completion criterion",\n' +
    '  "steps": ["step 1", "step 2"]\n' +
    '}\n' +
    '```\n\n' +
    '- `files` : RELATIVE paths from repo root, existing files the editor will modify. Required, non-empty.\n' +
    '- `doneWhen` : required, non-empty.\n' +
    '- `steps` : optional.',
  ].join('\n\n')
}

/**
 * Brief initial de l'éditeur.
 *
 * @param {string} task
 * @param {string|null} scope
 * @param {object} plan  L'objet plan validé par parsePlan.
 * @returns {string}
 */
function buildEditorBrief(task, scope, plan) {
  const sections = [
    `## Task\n${task}`,
  ]

  if (scope) {
    sections.push(`## Scope\n${scope}`)
  }

  sections.push(
    '## Implementation plan\n' +
    `Files to modify (already verified to exist):\n` +
    plan.files.map((f) => `- ${f}`).join('\n') +
    (plan.steps && plan.steps.length > 0
      ? '\n\nSteps:\n' + plan.steps.map((s, i) => `${i + 1}. ${s}`).join('\n')
      : '')
  )

  sections.push(
    '## Done when\n' +
    `${plan.doneWhen}\n\n` +
    'The change is written to disk. Do not attempt to build, compile, lint or test — ' +
    'verification is performed independently and is not your responsibility.'
  )

  sections.push(
    '## If this is not the right place\n' +
    'If the work required falls outside the scope above, or if you determine the real ' +
    'problem lies elsewhere, say so explicitly and stop without changing anything. ' +
    'Reporting "this is not the right place to fix it" is a successful outcome, not a failure.'
  )

  return sections.join('\n\n')
}

/**
 * Brief de correction de l'éditeur (tentative suivante).
 *
 * Compressé par rapport au brief initial : les étapes du plan ne sont PAS
 * rejouées (l'éditeur les a déjà vues et elles n'ont pas suffi). Seuls le
 * périmètre de fichiers et le critère de done-when sont conservés pour que
 * l'éditeur sache quoi corriger et quand s'arrêter.
 *
 * @param {string} task
 * @param {string|null} scope
 * @param {object} plan
 * @param {string[]} errorLines
 * @param {number} attempt
 * @returns {string}
 */
function buildEditorFixBrief(task, scope, plan, errorLines, attempt) {
  const sections = [
    `## Task\n${task}`,

    '## Current state\n' +
    `A previous attempt (#${attempt - 1}) left changes on disk that do not pass verification. ` +
    'Read the current state of the files before changing anything — do not assume ' +
    'what was done.',

    `## Compiler output\n\`\`\`\n${(errorLines ?? []).join('\n')}\n\`\`\``,
  ]

  if (scope) {
    sections.push(`## Scope\n${scope}`)
  }

  // Périmètre de fichiers uniquement — les étapes du plan ne sont pas rejouées.
  // L'éditeur qui a déjà tenté le plan complet doit corriger l'erreur ci-dessus,
  // pas relire la prose d'implémentation.
  sections.push(
    '## Files in scope\n' +
    plan.files.map((f) => `- ${f}`).join('\n')
  )

  sections.push(
    '## Done when\n' +
    `${plan.doneWhen}\n\n` +
    'The change is written to disk. Do not attempt to build, compile, lint or test — ' +
    'verification is performed independently and is not your responsibility.'
  )

  sections.push(
    '## If this is not the right place\n' +
    'If the error indicates the real problem lies outside the scope above, say so ' +
    'explicitly and stop. Reporting that is a successful outcome, not a failure.'
  )

  return sections.join('\n\n')
}

/**
 * Brief de correction suite à un échec de review adversariale.
 *
 * L'éditeur reçoit les findings des reviewers FAIL + le message humain optionnel.
 * Il ne doit PAS relire le plan de l'analyste — il corrige le code existant
 * en réponse aux critiques de la review.
 *
 * @param {string|null} task
 * @param {string|null} scope
 * @param {object} claimsGate  Fichiers touchés lors de la dernière révision.
 * @param {string} reviewFindings  Findings concaténés des reviewers FAIL.
 * @param {string} humanMessage  Message optionnel de l'humain (peut être vide).
 * @returns {string}
 */
function buildReviewRetryBrief(task, scope, claimsGate, reviewFindings, humanMessage) {
  const sections = []

  if (task) sections.push(`## Task\n${task}`)
  if (scope) sections.push(`## Scope\n${scope}`)

  sections.push(
    '## Context\n' +
    'A previous implementation passed type-check and tests but was rejected by code reviewers. ' +
    'Read the current state of the files before making any change — do not assume what was done.'
  )

  if (claimsGate?.actualFiles?.length) {
    sections.push(
      '## Files modified in the previous attempt\n' +
      claimsGate.actualFiles.map((f) => `- ${f}`).join('\n')
    )
  }

  if (humanMessage) {
    sections.push(`## Human guidance\n${humanMessage}`)
  }

  sections.push(
    `## Review findings (address ALL blocking issues)\n${reviewFindings}`
  )

  sections.push(
    '## Done when\n' +
    'All CRITICAL and blocking findings from the review are addressed in the code. ' +
    'Do not attempt to build, compile, lint or test — verification is performed independently.'
  )

  sections.push(
    '## If this is not the right place\n' +
    'If the findings point to issues outside your scope, say so explicitly and stop without ' +
    'changing anything. Reporting that is a successful outcome, not a failure.'
  )

  return sections.join('\n\n')
}

// --------------------------------------------------------------------------
// Point d'entrée
// --------------------------------------------------------------------------

/**
 * Point d'entrée du workflow us-loop.
 *
 * @param {object} log  Logger fourni par `run.mjs`.
 */
export async function run(log) {
  const namespaceId = process.env.FACTORY_NAMESPACE_ID
  const analystName = process.env.FACTORY_AGENT_ANALYST ?? 'factory-analyst'
  const editorName = process.env.FACTORY_AGENT_EDITOR ?? 'factory-editor'
  const task = process.env.FACTORY_TASK ?? null
  const scope = process.env.FACTORY_SCOPE ?? null
  const domainName = process.env.FACTORY_DOMAIN ?? 'front'
  const factoryTicketRaw = process.env.FACTORY_TICKET ?? null

  // Validation des variables obligatoires
  const missing = []
  if (!namespaceId) missing.push('FACTORY_NAMESPACE_ID')
  // FACTORY_TASK est obligatoire uniquement si FACTORY_TICKET est absent
  if (!task && !factoryTicketRaw) missing.push('FACTORY_TASK (ou FACTORY_TICKET)')
  if (missing.length > 0) {
    throw new Error(
      `Variables d'environnement manquantes : ${missing.join(', ')}\n` +
        `  FACTORY_NAMESPACE_ID    : ID du namespace AgentOS\n` +
        `  FACTORY_TASK            : la tâche à accomplir (optionnel si FACTORY_TICKET fourni)\n` +
        `  FACTORY_TICKET          : (optionnel) identifiant ou URL du ticket Jira\n` +
        `  JIRA_BASE_URL           : (requis si FACTORY_TICKET) ex. https://foo.atlassian.net\n` +
        `  JIRA_EMAIL              : (requis si FACTORY_TICKET) email du compte Jira\n` +
        `  JIRA_API_TOKEN          : (requis si FACTORY_TICKET) token API Jira\n` +
        `  FACTORY_SCOPE           : (optionnel) périmètre autorisé\n` +
        `  FACTORY_DOMAIN          : (optionnel) back | front, défaut front\n` +
        `  FACTORY_AGENT_ANALYST   : (optionnel) nom du rôle analyste, défaut factory-analyst\n` +
        `  FACTORY_AGENT_EDITOR    : (optionnel) nom du rôle éditeur, défaut factory-editor`
    )
  }

  const domain = domains[domainName]
  if (!domain) {
    throw new Error(
      `Domaine inconnu : "${domainName}". Valeurs acceptées : ${Object.keys(domains).join(', ')}`
    )
  }

  const theRun = createRun('us-loop', { namespaceId })
  let allPass = false

  // -------------------------------------------------------------------------
  // Phase fetch-ticket (code)
  //
  // Récupère le ticket Jira si FACTORY_TICKET est défini.
  // Skipée silencieusement si FACTORY_TICKET est absent.
  // ticketContent voyage en mémoire et n'est jamais écrit dans le registre
  // (invariant : pas de texte généré/externe dans le registre).
  // -------------------------------------------------------------------------
  let ticketContent = null

  if (factoryTicketRaw) {
    const fetchTicketPhase = startPhase(theRun, 'fetch-ticket', 'code')
    log.phaseStart('fetch-ticket', 'code')

    const ticketId = extractTicketId(factoryTicketRaw)
    if (!ticketId) {
      failPhase(fetchTicketPhase, { input: factoryTicketRaw, reason: 'Impossible d\'extraire un identifiant Jira valide (format attendu : PROJ-1234 ou URL /browse/PROJ-1234)' })
      log.phaseEnd('fetch-ticket', 'fail', { input: factoryTicketRaw })
      log.error(`FACTORY_TICKET invalide : "${factoryTicketRaw}". Format attendu : PROJ-1234 ou URL complète.`)
      endRun(theRun, 'fail')
      return { allPass: false, filePath: theRun.filePath }
    }

    const jiraBaseUrl = process.env.JIRA_BASE_URL
    const jiraEmail = process.env.JIRA_EMAIL
    const jiraApiToken = process.env.JIRA_API_TOKEN

    const missingJira = []
    if (!jiraBaseUrl) missingJira.push('JIRA_BASE_URL')
    if (!jiraEmail) missingJira.push('JIRA_EMAIL')
    if (!jiraApiToken) missingJira.push('JIRA_API_TOKEN')

    if (missingJira.length > 0) {
      failPhase(fetchTicketPhase, { ticketId, reason: `Variables Jira manquantes : ${missingJira.join(', ')}` })
      log.phaseEnd('fetch-ticket', 'fail', { ticketId })
      log.error(`Variables Jira manquantes : ${missingJira.join(', ')}`)
      endRun(theRun, 'fail')
      return { allPass: false, filePath: theRun.filePath }
    }

    try {
      const result = await fetchJiraTicket(ticketId, jiraBaseUrl, jiraEmail, jiraApiToken)
      ticketContent = result.ticketContent
      passPhase(fetchTicketPhase, {
        ticketId,
        summary: result.summary,
        fieldCount: result.fieldCount,
        commentCount: result.commentCount,
        commentsIncluded: result.commentsIncluded,
        commentsTruncated: result.commentsTruncated,
      })
      log.phaseEnd('fetch-ticket', 'pass', {
        ticketId,
        fieldCount: result.fieldCount,
        commentCount: result.commentCount,
        commentsIncluded: result.commentsIncluded,
      })
    } catch (err) {
      failPhase(fetchTicketPhase, { ticketId, reason: String(err) })
      log.phaseEnd('fetch-ticket', 'fail', { ticketId })
      log.error(`Échec de récupération du ticket ${ticketId} : ${String(err)}`)
      endRun(theRun, 'fail')
      return { allPass: false, filePath: theRun.filePath }
    }
  }

  // -------------------------------------------------------------------------
  // Phase 0 : préflight (code)
  //
  // Vérifications :
  //   - L'analyste existe, est activé, n'a pas de subAgents.
  //   - L'éditeur existe, est activé, n'a pas de subAgents.
  //   - L'éditeur est colocalisé avec l'orchestrateur (preflightWorkspace).
  //
  // On ne vérifie pas la colocalisation de l'analyste : `preflightWorkspace`
  // rejette explicitement les intégrations readOnly, donc l'appeler sur
  // l'analyste échouerait toujours.
  //
  // Conséquence : rien ne garantit au démarrage que l'analyste lit le même
  // arbre que celui où l'éditeur écrira. Le plan-gate rattrape ce cas : il
  // vérifie l'existence de chaque fichier sous REPO_ROOT via existsSync.
  // Un plan produit depuis un autre dépôt est donc rejeté avant qu'un
  // token d'implémentation soit dépensé.
  // La vérification readOnly de l'analyste se fait dans provision.mjs.
  // -------------------------------------------------------------------------
  {
    const phase = startPhase(theRun, 'preflight', 'code')
    log.phaseStart('preflight', 'code')

    // Vérifier l'analyste
    const analystCheck = await preflightAgent(namespaceId, analystName)
    if (!analystCheck.ok) {
      failPhase(phase, { analystName, reason: analystCheck.reason })
      log.phaseEnd('preflight', 'fail', { analystName })
      log.error(analystCheck.reason)
      endRun(theRun, 'fail')
      return { allPass: false, filePath: theRun.filePath }
    }

    // Vérifier l'éditeur
    const editorCheck = await preflightAgent(namespaceId, editorName)
    if (!editorCheck.ok) {
      failPhase(phase, { editorName, reason: editorCheck.reason })
      log.phaseEnd('preflight', 'fail', { editorName })
      log.error(editorCheck.reason)
      endRun(theRun, 'fail')
      return { allPass: false, filePath: theRun.filePath }
    }

    // Colocalisation de l'éditeur : il doit écrire dans l'arbre que l'oracle compile.
    const workspace = await preflightWorkspace(namespaceId, editorCheck.agent, REPO_ROOT)
    if (!workspace.ok) {
      failPhase(phase, {
        editorName,
        repoRoot: REPO_ROOT,
        rootPath: workspace.rootPath,
        reason: workspace.reason,
      })
      log.phaseEnd('preflight', 'fail', { editorName, rootPath: workspace.rootPath })
      log.error(workspace.reason)
      endRun(theRun, 'fail')
      return { allPass: false, filePath: theRun.filePath }
    }

    passPhase(phase, {
      analystName,
      analystSubAgents: analystCheck.agent.subAgents ?? [],
      editorName,
      editorSubAgents: editorCheck.agent.subAgents ?? [],
      domain: domainName,
      rootPath: workspace.rootPath,
    })
    log.phaseEnd('preflight', 'pass', { analystName, editorName, domain: domainName })
  }

  // Accumulation des erreurs de compilateur pour la boucle externe
  let lastErrorLines = null

  // Accumulation des résultats oracles pour le review packet (dernière révision).
  /** @type {Array<{ name: string, exitCode: number, passed: boolean, tail: string }>} */
  let lastOracleResults = []

  // Claims-gate de la dernière révision réussie (pour le review packet).
  /** @type {object|null} */
  let lastClaimsGate = null

  // Quarantined oracle records accumulated across the run (for review packet).
  /** @type {object[]} */
  const quarantinedOracles = []

  // -------------------------------------------------------------------------
  // Baseline oracle phases (code) — before any agent edits files
  //
  // Run each oracle against the current repo state BEFORE the analyst/editor
  // modifies anything. This gives us a baseline to classify post-edit failures.
  //
  // Rules:
  //   - Same command, cwd, project resolution, cache policy, timeout, and
  //     result parsing as post-edit verification.
  //   - Baseline is OBSERVATION ONLY — never a reason to ask an editor to fix.
  //   - Baseline failure is recorded as a durable fact.
  //   - If baseline itself cannot run (timeout, empty), the corresponding
  //     post-edit classification defaults to ORACLE_INFRASTRUCTURE.
  // -------------------------------------------------------------------------

  /** @type {Map<string, import('../lib/oracle-baseline.mjs').BaselineOracleResult>} */
  const baselineResults = new Map()

  for (const oracle of domain.oracles) {
    const baselinePhaseName = `baseline-${oracle.name}`
    const baselinePhase = startPhase(theRun, baselinePhaseName, 'code')
    log.phaseStart(baselinePhaseName, 'code')
    log.info(`[${baselinePhaseName}] Running baseline oracle: ${oracle.name}`)
    log.info(`[${baselinePhaseName}] Command: ${oracle.command}`)
    log.info(`[${baselinePhaseName}] CWD: ${oracle.cwd}`)

    const baseline = runBaselineOracle({ oracle, repoRoot: REPO_ROOT, timeoutMs: ORACLE_TIMEOUT_MS })
    baselineResults.set(oracle.name, baseline)

    const baselineFacts = {
      oracle: oracle.name,
      command: baseline.command,
      cwd: baseline.cwd,
      exitCode: baseline.exitCode,
      timedOut: baseline.timedOut,
      emptySuccess: baseline.emptySuccess,
      durationMs: baseline.durationMs,
      tasks: baseline.tasks,
      diagnosticCount: baseline.diagnosticIdentities.length,
      executionEvidence: baseline.executionEvidence,
      ranAt: baseline.ranAt,
    }

    if (baseline.exitCode === 0 && !baseline.timedOut && !baseline.emptySuccess) {
      passPhase(baselinePhase, baselineFacts)
      log.phaseEnd(baselinePhaseName, 'pass', { oracle: oracle.name, exitCode: baseline.exitCode })
    } else {
      // Baseline failed: record as fail, but DO NOT stop the run.
      // This is observation; the workflow continues to the analyst.
      failPhase(baselinePhase, {
        ...baselineFacts,
        rawDiagnosticLines: baseline.rawDiagnosticLines,
        diagnosticIdentities: baseline.diagnosticIdentities,
      })
      log.phaseEnd(baselinePhaseName, 'fail', {
        oracle: oracle.name,
        exitCode: baseline.exitCode,
        timedOut: baseline.timedOut,
        emptySuccess: baseline.emptySuccess,
        diagnosticCount: baseline.diagnosticIdentities.length,
      })
      log.error(
        `[${baselinePhaseName}] Baseline oracle failed (exitCode=${baseline.exitCode}, ` +
        `timedOut=${baseline.timedOut}, emptySuccess=${baseline.emptySuccess}). ` +
        `This is OBSERVATION only — the run continues. Classification will use this as baseline evidence.`
      )
    }
  }

  // -------------------------------------------------------------------------
  // Boucle externe : révisions (analyste)
  // -------------------------------------------------------------------------
  for (let revision = 1; revision <= MAX_REVISION_LOOPS; revision++) {

    // -----------------------------------------------------------------------
    // Phase analyse (agent, LECTURE SEULE)
    //
    // La garde wroteNothing de fix-loop NE S'APPLIQUE PAS ici.
    // Un analyste qui n'écrit rien est le comportement normal.
    // L'analyste est en readOnly : il ne PEUT pas écrire.
    // -----------------------------------------------------------------------
    let plan = null

    {
      const analysePhaseName = `analyse-${revision}`
      const analysePhase = startPhase(theRun, analysePhaseName, 'agent')
      log.phaseStart(analysePhaseName, 'agent')

      const brief =
        revision === 1
          ? buildAnalystBrief(task, scope, ticketContent)
          : buildRevisionAnalystBrief(task, scope, lastErrorLines, revision)

      // Case NEUF pour l'analyste — même raison que fix-loop.
      let analystCaseId
      try {
        const newCase = await createCase(namespaceId, `factory/us-loop — analyse-${revision}`)
        analystCaseId = newCase.id
      } catch (err) {
        failPhase(analysePhase, { revision, agentStatus: 'error', error: String(err) })
        log.phaseEnd(analysePhaseName, 'fail', { error: String(err) })
        endRun(theRun, 'fail')
        return { allPass: false, filePath: theRun.filePath }
      }

      const turn = await runAgentTurn(analystCaseId, analystName, brief, {
        startTimeoutMs: START_TIMEOUT_MS,
        workTimeoutMs: WORK_TIMEOUT_MS,
      })

      // Contrôle a posteriori de la substitution d'agent
      const wrongAgent =
        turn.agentsSelected.length > 0 && !turn.agentsSelected.includes(analystName)

      const analyseFacts = {
        revision,
        caseId: analystCaseId,
        agentStatus: turn.status,
        caseStatus: turn.caseStatus,
        agentsSelected: turn.agentsSelected,
        agentTurns: turn.agentTurns,
        toolCallCount: turn.toolCallCount,
        // failedToolCalls est un Record<string,number> — sérialisé explicitement
        // pour éviter [object Object] si jamais log.* appelle toString().
        failedToolCalls: turn.failedToolCalls ?? {},
        failedToolCallCount: Object.keys(turn.failedToolCalls ?? {}).length,
        killedByBudget: turn.killedByBudget,
        anchored: turn.anchored,
        llmModels: turn.llmModels,
        // NB : ni le texte du plan ni aucun texte de l'agent n'entre dans le registre
      }

      if (wrongAgent) {
        failPhase(analysePhase, { ...analyseFacts, expectedAgent: analystName })
        log.phaseEnd(analysePhaseName, 'fail', { agentsSelected: turn.agentsSelected })
        log.error(
          `L'agent "${analystName}" n'a pas traité ce case. ` +
            `Agents sélectionnés : ${turn.agentsSelected.join(', ')}.`
        )
        endRun(theRun, 'fail')
        return { allPass: false, filePath: theRun.filePath }
      }

      if (turn.status !== 'finished') {
        failPhase(analysePhase, analyseFacts)
        log.phaseEnd(analysePhaseName, 'fail', { agentStatus: turn.status })
        log.error(`Tour d'analyste non abouti : ${turn.status}`)
        endRun(theRun, 'fail')
        return { allPass: false, filePath: theRun.filePath }
      }

      // Parsing du plan — budget de reformulation JSON_FIX_ATTEMPTS
      // Le plan voyage EN MÉMOIRE via turn.message, jamais sur disque.
      let parseResult = parsePlan(turn.message)

      if (!parseResult.ok) {
        log.error(`Plan invalide : ${parseResult.error}`)

        // Tentatives de reformulation dans des cases neufs
        let fixAttempt = 0
        while (!parseResult.ok && fixAttempt < JSON_FIX_ATTEMPTS) {
          fixAttempt++
          log.info(`Reformulation du plan (tentative ${fixAttempt}/${JSON_FIX_ATTEMPTS})…`)

          let fixCaseId
          try {
            const fixCase = await createCase(
              namespaceId,
              `factory/us-loop — analyse-${revision}-json-fix-${fixAttempt}`
            )
            fixCaseId = fixCase.id
          } catch (err) {
            failPhase(analysePhase, { ...analyseFacts, jsonFixError: String(err) })
            log.phaseEnd(analysePhaseName, 'fail', { error: String(err) })
            endRun(theRun, 'fail')
            return { allPass: false, filePath: theRun.filePath }
          }

          const fixTurn = await runAgentTurn(
            fixCaseId,
            analystName,
            buildJsonFixBrief(parseResult.error),
            { startTimeoutMs: START_TIMEOUT_MS, workTimeoutMs: WORK_TIMEOUT_MS }
          )

          if (fixTurn.status !== 'finished') {
            log.error(`Reformulation non aboutie : ${fixTurn.status}`)
            break
          }

          parseResult = parsePlan(fixTurn.message)
          if (!parseResult.ok) {
            log.error(`Plan toujours invalide après reformulation ${fixAttempt} : ${parseResult.error}`)
          }
        }

        if (!parseResult.ok) {
          failPhase(analysePhase, {
            ...analyseFacts,
            parseError: parseResult.error,
            jsonFixAttempts: fixAttempt,
          })
          log.phaseEnd(analysePhaseName, 'fail', { parseError: parseResult.error })
          log.error(`Impossible d'obtenir un plan valide après ${fixAttempt} reformulation(s).`)
          endRun(theRun, 'fail')
          return { allPass: false, filePath: theRun.filePath }
        }
      }

      plan = parseResult.plan

      // Seuls les chemins de fichiers (faits vérifiés mécaniquement) entrent
      // dans le registre — pas plan.steps, pas plan.doneWhen, pas de prose.
      passPhase(analysePhase, {
        ...analyseFacts,
        plannedFiles: plan.files,
        fileCount: plan.files.length,
      })
      log.phaseEnd(analysePhaseName, 'pass', {
        agentTurns: turn.agentTurns,
        toolCallCount: turn.toolCallCount,
        fileCount: plan.files.length,
      })
    }

    // -----------------------------------------------------------------------
    // Plan-gate (code)
    //
    // Vérifie que chaque chemin de plan.files existe sur disque.
    // Rejette tout chemin absolu ou contenant `..` (déjà fait par parsePlan,
    // mais on vérifie à nouveau ici car ce gate est la dernière barrière avant
    // qu'un token d'implémentation soit dépensé).
    // -----------------------------------------------------------------------
    {
      const planGateName = `plan-gate-${revision}`
      const planGatePhase = startPhase(theRun, planGateName, 'code')
      log.phaseStart(planGateName, 'code')

      const { plannedFiles, missingFiles, fileCount } = checkPlanFiles(plan.files, REPO_ROOT)

      const planGateFacts = { plannedFiles, missingFiles, fileCount }

      if (missingFiles.length > 0) {
        failPhase(planGatePhase, planGateFacts)
        log.phaseEnd(planGateName, 'fail', { missingFiles })
        log.error(
          `Le plan cite ${missingFiles.length} fichier(s) inexistant(s) : ${missingFiles.join(', ')}. ` +
            'Le run s\'arrête avant de dépenser des tokens d\'implémentation.'
        )
        endRun(theRun, 'fail')
        return { allPass: false, filePath: theRun.filePath }
      }

      passPhase(planGatePhase, planGateFacts)
      log.phaseEnd(planGateName, 'pass', { fileCount })
    }

    // Snapshot de référence pour claims-gate (accumulé sur toute la boucle d'édition)
    const beforeEditing = snapshotDiff(REPO_ROOT)

    // -----------------------------------------------------------------------
    // Boucle interne : édition + vérification
    // -----------------------------------------------------------------------
    let innerPass = false
    let errorLines = null

    for (let attempt = 1; attempt <= MAX_FIX_LOOPS; attempt++) {

      // --- Phase agent : l'éditeur implémente ---
      const editPhaseName = `edit-${revision}-${attempt}`
      const editPhase = startPhase(theRun, editPhaseName, 'agent')
      log.phaseStart(editPhaseName, 'agent')

      // Augmenter le périmètre de fichiers du brief de retry avec les fichiers
      // référencés dans les diagnostics. Les fichiers annoncés par l'analyste
      // sont préservés ; les fichiers de spec/impl cités dans les erreurs Jest
      // ou TS sont ajoutés si ils existent sur disque.
      // Cela permet à l'éditeur de trouver le fichier spec échoué même s'il
      // n'était pas dans le plan initial.
      const planForBrief = attempt === 1
        ? plan
        : (() => {
            const referencedFiles = extractReferencedFiles(errorLines, REPO_ROOT)
            const augmentedFiles = [...new Set([...plan.files, ...referencedFiles])]
            return { ...plan, files: augmentedFiles }
          })()

      const editorBrief =
        attempt === 1
          ? buildEditorBrief(task, scope, plan)
          : buildEditorFixBrief(task, scope, planForBrief, errorLines, attempt)

      const beforeAgent = snapshotDiff(REPO_ROOT)

      // Case NEUF pour l'éditeur — même raison que fix-loop.
      let editorCaseId
      try {
        const newCase = await createCase(
          namespaceId,
          `factory/us-loop — edit-${revision}-${attempt}`
        )
        editorCaseId = newCase.id
      } catch (err) {
        failPhase(editPhase, { revision, attempt, agentStatus: 'error', error: String(err) })
        log.phaseEnd(editPhaseName, 'fail', { error: String(err) })
        endRun(theRun, 'fail')
        return { allPass: false, filePath: theRun.filePath }
      }

      const turn = await runAgentTurn(editorCaseId, editorName, editorBrief, {
        startTimeoutMs: START_TIMEOUT_MS,
        workTimeoutMs: WORK_TIMEOUT_MS,
      })

      const agentChanged = diffSince(beforeAgent, REPO_ROOT)

      // Contrôle a posteriori de la substitution d'agent
      const wrongAgent =
        turn.agentsSelected.length > 0 && !turn.agentsSelected.includes(editorName)

      const editFacts = {
        revision,
        attempt,
        caseId: editorCaseId,
        agentStatus: turn.status,
        caseStatus: turn.caseStatus,
        agentsSelected: turn.agentsSelected,
        agentTurns: turn.agentTurns,
        toolCallCount: turn.toolCallCount,
        // failedToolCalls est un Record<string,number> — sérialisé explicitement
        // pour éviter [object Object] dans le registre et les logs.
        failedToolCalls: turn.failedToolCalls ?? {},
        failedToolCallCount: Object.keys(turn.failedToolCalls ?? {}).length,
        killedByBudget: turn.killedByBudget,
        anchored: turn.anchored,
        llmModels: turn.llmModels,
        filesModified: agentChanged.modified,
        filesUntracked: agentChanged.untracked,
        // NB : le texte produit par l'agent n'est PAS enregistré
      }

      if (wrongAgent) {
        failPhase(editPhase, { ...editFacts, expectedAgent: editorName })
        log.phaseEnd(editPhaseName, 'fail', { agentsSelected: turn.agentsSelected })
        log.error(
          `L'agent "${editorName}" n'a pas traité ce case. ` +
            `Agents sélectionnés : ${turn.agentsSelected.join(', ')}.`
        )
        endRun(theRun, 'fail')
        return { allPass: false, filePath: theRun.filePath }
      }

      if (turn.status !== 'finished') {
        failPhase(editPhase, editFacts)
        log.phaseEnd(editPhaseName, 'fail', { agentStatus: turn.status })
        log.error(`Tour d'éditeur non abouti : ${turn.status}`)
        endRun(theRun, 'fail')
        return { allPass: false, filePath: theRun.filePath }
      }

      // Garde wroteNothing : un éditeur qui ne touche rien n'a pas fait le travail.
      // (Contrairement à l'analyste, l'éditeur DOIT modifier des fichiers.)
      const wroteNothing =
        agentChanged.modified.length === 0 && agentChanged.untracked.length === 0

      if (wroteNothing) {
        // Distinction entre deux cas de no-write :
        //
        // (A) Diagnostics actionnables disponibles (errorLines non vide) :
        //     L'éditeur a probablement mal interprété un brief sans contexte
        //     suffisant (fichier de spec absent du scope, message d'erreur
        //     réduit au résumé Nx). On échoue cette tentative mais on continue
        //     la boucle interne — le brief suivant contiendra les diagnostics
        //     et les fichiers référencés. Budget MAX_FIX_LOOPS toujours respecté.
        //
        // (B) Pas de diagnostics (premier tour ou errorLines null/vide) :
        //     L'éditeur a vu le brief complet et n'a rien fait. Soit la tâche
        //     est hors périmètre (sortie honorable), soit le plan est mal compris.
        //     Terminer immédiatement — continuer sans information nouvelle est
        //     du travail parasite.
        const hasActionableDiagnostics = Array.isArray(errorLines) && errorLines.length > 0
        failPhase(editPhase, { ...editFacts, wroteNothing: true, hasActionableDiagnostics })
        log.phaseEnd(editPhaseName, 'fail', { wroteNothing: true, hasActionableDiagnostics })
        log.error(
          `L'éditeur a terminé sans modifier aucun fichier (tentative ${attempt}/${MAX_FIX_LOOPS}). ` +
            'Soit la tâche est hors périmètre (sortie honorable), soit le plan est mal compris. ' +
            `Consulter le case ${editorCaseId} dans AgentOS pour lire sa réponse.`
        )
        if (!hasActionableDiagnostics) {
          // Cas (B) : arrêt immédiat, pas d'information nouvelle pour le prochain tour.
          endRun(theRun, 'fail')
          return { allPass: false, filePath: theRun.filePath }
        }
        // Cas (A) : continuer la boucle interne avec les diagnostics actionnables.
        // Le brief suivant inclura les fichiers référencés dans errorLines.
        log.error(
          `Des diagnostics actionnables sont disponibles — poursuite de la boucle ` +
            `(tentative ${attempt + 1}/${MAX_FIX_LOOPS} si budget restant).`
        )
        errorLines = errorLines // déjà à jour, pas de changement
        lastErrorLines = errorLines
        if (attempt === MAX_FIX_LOOPS) {
          log.error(`Budget de ${MAX_FIX_LOOPS} tentatives épuisé (no-write avec diagnostics).`)
        }
        continue
      }

      // Observabilité : logguer les appels d'outils échoués si l'agent a quand même
      // modifié des fichiers (récupération réussie). On ne fait pas échouer la phase
      // ici — la vérification déterministe des oracles tranche sur le résultat réel.
      if (turn.failedToolCalls && Object.keys(turn.failedToolCalls).length > 0) {
        const failedSummary = Object.entries(turn.failedToolCalls)
          .map(([tool, count]) => `${tool}×${count}`)
          .join(', ')
        log.error(
          `[edit-${revision}-${attempt}] ${Object.keys(turn.failedToolCalls).length} outil(s) en échec récupéré(s) : ${failedSummary}. ` +
          `L'agent a quand même écrit des fichiers — les oracles valideront le résultat.`
        )
      }

      passPhase(editPhase, editFacts)
      log.phaseEnd(editPhaseName, 'pass', {
        agentTurns: turn.agentTurns,
        toolCallCount: turn.toolCallCount,
        failedToolCallCount: Object.keys(turn.failedToolCalls ?? {}).length,
        failedToolCallSummary: Object.entries(turn.failedToolCalls ?? {})
          .map(([tool, count]) => `${tool}:${count}`)
          .join(',') || null,
        filesModified: agentChanged.modified.length,
        filesUntracked: agentChanged.untracked.length,
      })

      // --- Phase(s) code : les oracles rendent le verdict ---
      //
      // Chaque oracle produit sa propre phase dans le registre. Au premier échec,
      // on s'arrête : les oracles suivants ne tournent pas. La vérification ne
      // passe que si TOUS les oracles passent.
      //
      // La garde de timeout et le correctif A8 (succès vide) s'appliquent à
      // CHAQUE oracle. Un timeout sort des DEUX boucles (interne + externe) via
      // `return`, comme tous les autres chemins d'échec fatal de ce workflow.
      let oraclePass = true
      let oracleErrorLines = null

      for (const oracle of domain.oracles) {
        const verifyPhaseName = `verify-${oracle.name}-${revision}-${attempt}`
        const verifyPhase = startPhase(theRun, verifyPhaseName, 'code')
        log.phaseStart(verifyPhaseName, 'code')

        // Construction de la commande effective.
        //
        // Si l'oracle porte `filesArg: true`, on injecte `--files=<liste>` à
        // partir des fichiers modifiés par l'éditeur lors de ce tour
        // (`agentChanged.modified`). Cette liste est stable pendant toute la
        // boucle des oracles : elle a été calculée par `diffSince(beforeAgent,
        // REPO_ROOT)` avant d'entrer dans la boucle, et aucun oracle ne modifie
        // l'arbre (vérifié par le snapshot `beforeOracle`/`oracleChanged`).
        //
        // Un oracle sans `filesArg` (comme `types`) reçoit sa commande telle
        // quelle — périmètre fixe, indépendant du diff.
        //
        // C'est la commande EFFECTIVE (avec `--files`) qui est enregistrée dans
        // le registre, pas la commande template. Le registre doit dire ce qui a
        // réellement tourné.
        const effectiveCommand = buildOracleCommand(oracle, agentChanged.modified, REPO_ROOT)

        // Résoudre les projets pour les logger au démarrage (observabilité : diagnosable depuis l'UI AgentOS).
        const resolvedProjectsForLog = oracle.filesArg
          ? resolveOwnerProjects(agentChanged.modified, REPO_ROOT)
          : []
        log.info(`[${verifyPhaseName}] Oracle : ${oracle.name}`)
        log.info(`[${verifyPhaseName}] Commande : ${effectiveCommand}`)
        log.info(`[${verifyPhaseName}] Répertoire : ${oracle.cwd}`)
        if (resolvedProjectsForLog.length > 0) {
          log.info(`[${verifyPhaseName}] Projets résolus : ${resolvedProjectsForLog.join(', ')}`)
        }
        log.info(`[${verifyPhaseName}] La commande peut prendre plusieurs minutes sans sortie intermédiaire.`)

        const beforeOracle = snapshotDiff(REPO_ROOT)

        const result = runCommand(effectiveCommand, {
          cwd: oracle.cwd,
          timeoutMs: ORACLE_TIMEOUT_MS,
        })

        const oracleChanged = diffSince(beforeOracle, REPO_ROOT)

        const passed = result.exitCode === 0
        const tasks = countTaskOutcomes(result.stdout + '\n' + result.stderr)

        const verifyFacts = {
          revision,
          attempt,
          oracle: oracle.name,
          command: effectiveCommand,
          domain: domainName,
          exitCode: result.exitCode,
          timedOut: result.timedOut,
          commandDurationMs: result.durationMs,
          tasks,
          filesModified: oracleChanged.modified,
          filesUntracked: oracleChanged.untracked,
        }

        // TIMEOUT DE L'ORACLE — arrêt immédiat, sortie des DEUX boucles (décision, C2)
        //
        // Même raisonnement que fix-loop : un timeout ne dit rien sur le travail de
        // l'éditeur. Ce n'est pas un verdict sur le code, c'est un échec de l'instrument.
        // Relancer l'éditeur sur une sortie tronquée d'une commande interrompue lui
        // demande de corriger un problème qu'on n'a pas mesuré — source de travail
        // parasite avec verdict cohérent.
        //
        // La phase est dans la boucle interne (attempt), elle-même dans la boucle
        // externe (revision), elle-même dans la boucle des oracles. Le `return` direct
        // sort des TROIS boucles, comme le font déjà les autres chemins d'échec fatal.
        if (result.timedOut) {
          failPhase(verifyPhase, verifyFacts)
          log.phaseEnd(verifyPhaseName, 'fail', {
            oracle: oracle.name,
            exitCode: result.exitCode,
            timedOut: true,
            commandDurationMs: result.durationMs,
          })
          log.error(
            `Timeout de l'oracle [${oracle.name}] : la vérification n'a pas abouti en ${ORACLE_TIMEOUT_MS / 1000}s ` +
              `(durée réelle : ${Math.round(result.durationMs / 1000)}s). ` +
              `Ce n'est PAS un verdict sur le travail de l'éditeur — le code n'a été ni validé ` +
              `ni invalidé. C'est le budget ou le périmètre de la commande qu'il faut réévaluer ` +
              `(ORACLE_TIMEOUT_MS ou domain.oracles).`
          )
          endRun(theRun, 'fail')
          return { allPass: false, filePath: theRun.filePath }
        }

        // SUCCÈS VIDE (executed === 0) — route vers ORACLE_INFRASTRUCTURE human gate.
        //
        // Quand `exitCode === 0` mais `tasks.executed === 0`, l'oracle n'a rien
        // exécuté : tout a été servi par le cache. Le verdict est vrai et VIDE.
        // C'est un échec de l'INSTRUMENT. Route vers le gate humain oracle.
        if (passed && tasks.executed === 0) {
          failPhase(verifyPhase, { ...verifyFacts, emptySuccess: true, classification: 'ORACLE_INFRASTRUCTURE' })
          log.phaseEnd(verifyPhaseName, 'fail', {
            oracle: oracle.name,
            exitCode: result.exitCode,
            emptySuccess: true,
            classification: 'ORACLE_INFRASTRUCTURE',
            commandDurationMs: result.durationMs,
          })
          log.error(
            `Oracle [${oracle.name}] : succès vide — aucune tâche exécutée ` +
              `(up-to-date=${tasks.upToDate}, from-cache=${tasks.fromCache}). ` +
              `Classification : ORACLE_INFRASTRUCTURE. Ouverture du gate humain oracle.`
          )

          const oracleGateInfo = {
            oracleName: oracle.name,
            classification: 'ORACLE_INFRASTRUCTURE',
            reason: `Oracle empty success: tasks.executed === 0 (up-to-date=${tasks.upToDate}, from-cache=${tasks.fromCache}). No execution evidence.`,
            command: effectiveCommand,
            cwd: oracle.cwd,
            projects: resolvedProjectsForLog,
            baselineDiagnostics: baselineResults.get(oracle.name)?.diagnosticIdentities ?? [],
            postEditDiagnostics: [],
            newDiagnostics: [],
            preExistingDiagnostics: [],
            newDiagnosticLines: [],
            baselineEvidence: baselineResults.get(oracle.name)?.executionEvidence ?? 'no baseline',
          }
          emitOracleGateOpen(theRun.runId, oracleGateInfo)
          const { decision: oracleDecision, message: oracleMessage } = await waitForHumanDecision(theRun.runId, log, 'oracle')

          if (oracleDecision === 'continue') {
            // Quarantine: record the failed oracle, proceed through the run.
            const quarantine = buildQuarantineRecord({
              oracleName: oracle.name,
              classification: 'ORACLE_INFRASTRUCTURE',
              reason: oracleGateInfo.reason,
              baseline: baselineResults.get(oracle.name) ?? null,
              postEdit: { exitCode: result.exitCode, timedOut: false, emptySuccess: true, durationMs: result.durationMs },
              classificationResult: { baselineIdentities: [], postEditIdentities: [], newDiagnostics: [], preExistingDiagnostics: [], newDiagnosticLines: [], baselinePassed: false, postEditPassed: false },
              humanDecision: 'continue',
              humanMessage: oracleMessage,
            })
            quarantinedOracles.push(quarantine)
            log.error(`Oracle [${oracle.name}] quarantined (human: continue). Proceeding.`)
            // Continue to next oracle — do not treat as oraclePass=false.
            continue
          } else {
            // 'fail': end the run.
            endRun(theRun, 'fail')
            return { allPass: false, filePath: theRun.filePath }
          }
        }

        if (tasks.countMismatch) {
          log.error(
            'ATTENTION : les deux mesures de décompte Nx divergent ' +
              `(ligne à ligne : fromCache=${tasks.fromCache}, ` +
              `synthèse : fromCache=${tasks.summaryFromCache} sur ${tasks.summaryTotal}). ` +
              'Le format de sortie de Nx a probablement changé — ' +
              'le chiffre executed du registre n\'est pas fiable pour ce run.'
          )
        }

        if (passed) {
          passPhase(verifyPhase, verifyFacts)
          log.phaseEnd(verifyPhaseName, 'pass', {
            oracle: oracle.name,
            exitCode: result.exitCode,
            commandDurationMs: result.durationMs,
            tasksExecuted: tasks.executed,
          })
          // Accumuler pour le review packet.
          lastOracleResults.push({
            name: oracle.name,
            exitCode: result.exitCode,
            passed: true,
            tail: tailLines(result.stdout || result.stderr, TAIL_LINES).join('\n'),
          })
          // Oracle passé, on continue avec le suivant.
          continue
        }

        // Échec : classifier et router selon la classification.
        const stderrTail = tailLines(result.stderr, TAIL_LINES)
        const stdoutTail = tailLines(result.stdout, TAIL_LINES)

        // Classify the oracle failure relative to the baseline.
        const classResult = classifyOracleResult({
          oracle,
          baseline: baselineResults.get(oracle.name) ?? null,
          postEdit: {
            exitCode: result.exitCode,
            timedOut: result.timedOut,
            emptySuccess: false,
            stdout: result.stdout,
            stderr: result.stderr,
            tasks,
          },
          changedFiles: agentChanged.modified,
          plannedFiles: plan.files,
        })

        const classification = classResult.classification

        // Accumuler pour le review packet (oracle échoué).
        lastOracleResults.push({
          name: oracle.name,
          exitCode: result.exitCode,
          passed: false,
          classification,
          tail: tailLines(result.stderr || result.stdout, ERROR_LINES_FOR_AGENT).join('\n'),
        })

        failPhase(verifyPhase, {
          ...verifyFacts,
          stderrTail,
          stdoutTail,
          classification,
          classificationReason: classResult.reason,
          newDiagnostics: classResult.newDiagnostics,
          preExistingDiagnostics: classResult.preExistingDiagnostics,
        })
        log.phaseEnd(verifyPhaseName, 'fail', {
          oracle: oracle.name,
          exitCode: result.exitCode,
          timedOut: result.timedOut,
          classification,
          commandDurationMs: result.durationMs,
        })

        log.error(
          `Oracle [${oracle.name}] verdict négatif (exitCode=${result.exitCode}) ` +
            `— classification: ${classification} — ` +
            `tentative ${attempt}/${MAX_FIX_LOOPS} (révision ${revision})`
        )
        log.error(`Classification reason: ${classResult.reason}`)

        // Route by classification.
        if (classification === 'PRODUCT_REGRESSION') {
          // Send only NEW diagnostics to the editor retry.
          oracleErrorLines = classResult.newDiagnosticLines.length > 0
            ? classResult.newDiagnosticLines
            : (oracle.name === 'types'
                ? extractTypeDiagnostics(result.stdout, result.stderr, ERROR_LINES_FOR_AGENT)
                : oracle.name === 'tests'
                  ? extractTestDiagnostics(result.stdout, result.stderr, ERROR_LINES_FOR_AGENT)
                  : tailLines(
                      result.stderr.trim().length > 0 ? result.stderr : result.stdout,
                      ERROR_LINES_FOR_AGENT
                    ))

          for (const line of oracleErrorLines.slice(-TAIL_LINES)) {
            log.error(line)
          }

          oraclePass = false
          break // Editor retry with new diagnostics only.
        }

        // BASELINE_FAILURE, ORACLE_INFRASTRUCTURE, INDETERMINATE_OUT_OF_SCOPE:
        // Do not spend editor retries. Open a run-scoped human oracle gate.
        {
          const oracleGateInfo = {
            oracleName: oracle.name,
            classification,
            reason: classResult.reason,
            command: effectiveCommand,
            cwd: oracle.cwd,
            projects: resolvedProjectsForLog,
            baselineDiagnostics: classResult.baselineIdentities,
            postEditDiagnostics: classResult.postEditIdentities,
            newDiagnostics: classResult.newDiagnostics,
            preExistingDiagnostics: classResult.preExistingDiagnostics,
            newDiagnosticLines: classResult.newDiagnosticLines,
            baselineEvidence: baselineResults.get(oracle.name)?.executionEvidence ?? 'no baseline',
          }

          log.error(`Opening human oracle gate for classification: ${classification}`)
          emitOracleGateOpen(theRun.runId, oracleGateInfo)
          const { decision: oracleDecision, message: oracleMessage } = await waitForHumanDecision(theRun.runId, log, 'oracle')

          if (oracleDecision === 'continue') {
            // Quarantine: record the failed oracle as durable evidence, proceed.
            const quarantine = buildQuarantineRecord({
              oracleName: oracle.name,
              classification,
              reason: classResult.reason,
              baseline: baselineResults.get(oracle.name) ?? null,
              postEdit: { exitCode: result.exitCode, timedOut: result.timedOut, emptySuccess: false, durationMs: result.durationMs },
              classificationResult: classResult,
              humanDecision: 'continue',
              humanMessage: oracleMessage,
            })
            quarantinedOracles.push(quarantine)
            log.error(`Oracle [${oracle.name}] quarantined (classification: ${classification}, human: continue). Proceeding.`)
            // Continue to next oracle — quarantined, not passing.
            continue
          } else {
            // 'fail': end the run immediately.
            endRun(theRun, 'fail')
            return { allPass: false, filePath: theRun.filePath }
          }
        }
      } // fin boucle oracles

      if (oraclePass) {
        // Propager les lignes d'erreur du dernier oracle échoué pour les révisions suivantes.
        // (Dans ce chemin, oracleErrorLines est null — la variable reste intacte.)
        innerPass = true
        log.info(`Boucle interne terminée après ${attempt} tentative(s) (révision ${revision}).`)
        break
      }

      // Propager les lignes d'erreur pour le brief de la prochaine tentative.
      errorLines = oracleErrorLines
      lastErrorLines = oracleErrorLines

      if (attempt === MAX_FIX_LOOPS) {
        log.error(`Budget de ${MAX_FIX_LOOPS} tentatives épuisé pour la révision ${revision}.`)
      }
    } // fin boucle interne

    if (innerPass) {
      // -----------------------------------------------------------------------
      // Claims-gate (code) — observabilité des écarts plan vs réalité
      //
      // Compare les fichiers réellement modifiés (depuis le début de la boucle
      // d'édition) aux fichiers annoncés par le plan.
      //
      // CONTRAT :
      //   - untouchedPlannedFiles non vide → écart de plan observable, PAS un
      //     échec automatique. Les oracles déterministes ont déjà validé le code.
      //     Un fichier planifié non touché peut signifier qu'il était déjà correct.
      //     Le fait est enregistré et transmis aux reviewers adversariaux.
      //   - unplannedFiles non vide → observabilité uniquement, pas un échec.
      //     Un éditeur peut légitimement toucher un fichier voisin non annoncé.
      // -----------------------------------------------------------------------
      const claimsGateName = `claims-gate-${revision}`
      const claimsGatePhase = startPhase(theRun, claimsGateName, 'code')
      log.phaseStart(claimsGateName, 'code')

      const actualChanged = diffSince(beforeEditing, REPO_ROOT)
      const claims = compareClaims(plan.files, actualChanged.modified, actualChanged.untracked)

      // Enregistrer tous les faits — écarts inclus — pour les reviewers.
      passPhase(claimsGatePhase, {
        revision,
        plannedFiles: claims.plannedFiles,
        actualFiles: claims.actualFiles,
        unplannedFiles: claims.unplannedFiles,
        untouchedPlannedFiles: claims.untouchedPlannedFiles,
        claimsMatch: claims.claimsMatch,
      })
      log.phaseEnd(claimsGateName, 'pass', {
        claimsMatch: claims.claimsMatch,
        unplannedCount: claims.unplannedFiles.length,
        untouchedCount: claims.untouchedPlannedFiles.length,
      })

      if (claims.untouchedPlannedFiles.length > 0) {
        log.error(
          `Claims-gate (observabilité) : ${claims.untouchedPlannedFiles.length} fichier(s) annoncé(s) non touchés : ` +
            claims.untouchedPlannedFiles.join(', ') +
            '. Les oracles ont passé — écart transmis aux reviewers.'
        )
      }
      if (claims.unplannedFiles.length > 0) {
        log.error(
          `Claims-gate (observabilité) : ${claims.unplannedFiles.length} fichier(s) modifié(s) non annoncé(s) : ` +
            claims.unplannedFiles.join(', ')
        )
      }

      // Sauvegarder le claims-gate pour le review packet.
      lastClaimsGate = {
        claimsMatch: claims.claimsMatch,
        plannedFiles: claims.plannedFiles,
        actualFiles: claims.actualFiles,
        unplannedFiles: claims.unplannedFiles,
        untouchedPlannedFiles: claims.untouchedPlannedFiles,
      }

      allPass = true
      log.info(`Workflow terminé après la révision ${revision}.`)
      break
    }

    // La boucle interne a épuisé son budget sans succès.
    if (revision === MAX_REVISION_LOOPS) {
      log.error(`Budget de ${MAX_REVISION_LOOPS} révisions épuisé.`)
    } else {
      log.info(`Révision ${revision} échouée, retour à l'analyste (révision ${revision + 1}).`)
    }
  } // fin boucle externe

  // -------------------------------------------------------------------------
  // Phase review adversariale (agent, parallèle)
  //
  // Lancée uniquement si la boucle implémentation a passé (allPass === true).
  // Le review packet est immuable : même contenu pour les 4 reviewers.
  //
  // Si AgentOS est indisponible ou si tous les reviewers sont skipés :
  // warning loggé, le run reste PASS (la factory ne bloque pas sur un
  // échec d'instrument).
  //
  // Si au moins un reviewer rend FAIL : le run est marqué FAIL.
  // -------------------------------------------------------------------------
  if (allPass && lastClaimsGate !== null) {
    const reviewPhaseName = 'adversarial-review'
    const reviewPhase = startPhase(theRun, reviewPhaseName, 'agent')
    log.phaseStart(reviewPhaseName, 'agent')

    // Construire le diff git SCOPÉ aux fichiers touchés par l'éditeur.
    //
    // POURQUOI PAS `git diff HEAD` :
    // Le working tree peut contenir des modifications sans rapport avec la tâche
    // (agents YAML, scripts, config locale). Les reviewers adversariaux voient
    // alors du bruit et produisent des findings hors scope qui polluent le verdict.
    //
    // On utilise `lastClaimsGate.actualFiles` — la liste exacte des fichiers
    // modifiés par l'éditeur, calculée par diffSince(beforeEditing, REPO_ROOT).
    // C'est le seul périmètre que la Factory a mesuré et peut garantir.
    //
    // Si la liste est vide (ne devrait pas arriver ici car innerPass=true implique
    // que l'éditeur a écrit), on retombe sur `git diff HEAD` en dernier recours.
    let gitDiff = ''
    const filesToReview = lastClaimsGate.actualFiles ?? []
    try {
      if (filesToReview.length > 0) {
        // `git diff HEAD -- file1 file2 ...` : diff scopé aux fichiers de l'éditeur.
        const fileArgs = filesToReview.map((f) => `"${f}"`).join(' ')
        gitDiff = execSync(
          `git diff HEAD -- ${fileArgs}`,
          { cwd: REPO_ROOT, encoding: 'utf8', timeout: 30_000 }
        )
        if (!gitDiff.trim()) {
          // Les fichiers existent mais ne sont pas encore stagés/trackés :
          // essayer git diff avec les fichiers non trackés.
          gitDiff = execSync(
            `git diff -- ${fileArgs}`,
            { cwd: REPO_ROOT, encoding: 'utf8', timeout: 30_000 }
          )
        }
      } else {
        log.error('diff-scope : actualFiles vide, repli sur git diff HEAD (bruit possible)')
        gitDiff = execSync('git diff HEAD', { cwd: REPO_ROOT, encoding: 'utf8', timeout: 30_000 })
      }
    } catch (err) {
      log.error(`Impossible de produire le git diff : ${String(err)}`)
      gitDiff = '(git diff unavailable)'
    }

    const reviewPacket = {
      task: task ?? ticketContent ?? '(no task)',
      diff: gitDiff || '(empty diff)',
      oracleResults: lastOracleResults,
      claimsGate: lastClaimsGate,
      // Quarantined oracles are included so reviewers see them.
      quarantinedOracles: quarantinedOracles.length > 0 ? quarantinedOracles : undefined,
    }

    try {
      const reviewResult = await runAdversarialReview({
        namespaceId,
        reviewPacket,
      })

      // Les rawOutputs sont inclus dans les facts pour affichage dans le dashboard.
      // Chaque reviewer peut avoir produit plusieurs Ko de markdown — on les tronque
      // à 4000 chars pour éviter de saturer le registre JSONL.
      const MAX_RAW_OUTPUT = 4000
      const reviewFacts = {
        globalVerdict: reviewResult.globalVerdict,
        reviewerCount: reviewResult.reviewerCount,
        passCount: reviewResult.passCount,
        failCount: reviewResult.failCount,
        skipCount: reviewResult.skipCount,
        outcomes: reviewResult.outcomes.map((o) => ({
          reviewerName: o.reviewerName,
          caseId: o.caseId,
          status: o.status,
          verdict: o.verdict,
          hasCritical: o.hasCritical,
          errorCode: o.errorCode,
          // Inclure le rawOutput tronqué pour affichage dans le dashboard.
          summary: o.rawOutput
            ? o.rawOutput.slice(0, MAX_RAW_OUTPUT) + (o.rawOutput.length > MAX_RAW_OUTPUT ? '\n...(tronqué)' : '')
            : null,
        })),
      }

      if (reviewResult.globalVerdict === 'FAIL') {
        // Afficher les findings dans le terminal pour le gate humain.
        log.error('\n═══ REVUE ADVERSARIALE FAIL ═══')
        for (const o of reviewResult.outcomes) {
          if (o.verdict === 'FAIL' && o.rawOutput) {
            log.error(`\n--- ${o.reviewerName} (${o.caseId ?? 'no case'}) ---`)
            // Afficher les 80 premières lignes du rawOutput.
            const lines = o.rawOutput.split('\n').slice(0, 80)
            for (const line of lines) log.error(line)
          }
        }
        log.error('\n════════════════════════════════')

        // Run-scoped human gate (no timeout, no global files).
        //
        // 1. emitGateOpen() writes a JSON line on stdout — the dashboard server
        //    intercepts it and stores the gate in its registry keyed by runId.
        // 2. waitForHumanDecision() polls factory/runs/<runId>.gate-reply.
        //    The dashboard server writes that file when the human POSTs a decision.
        //    On SIGTERM, shutdown.mjs calls rejectAllPendingGates() which resolves
        //    the Promise to { decision: 'fail' } before process.exit().
        emitGateOpen(theRun.runId, reviewResult)
        const { decision: humanDecision, message: humanMessage } = await waitForHumanDecision(theRun.runId, log)

        if (humanDecision === 'ignore') {
          // L'humain ignore le FAIL : run PASS.
          log.error('Décision humaine : IGNORE — le run est marqué PASS malgré le FAIL de review.')
          passPhase(reviewPhase, { ...reviewFacts, humanDecision: 'ignore' })
          log.phaseEnd(reviewPhaseName, 'pass', { globalVerdict: 'FAIL', humanDecision: 'ignore' })

        } else if (humanDecision === 'retry') {
          // L'humain renvoie à edit avec les findings comme brief de correction.
          // On marque la phase review comme FAIL (elle a fait son travail : trouver des problèmes),
          // puis on réintègre le retry dans le workflow normal : edit → oracles → review.
          // Le retry est borné : MAX_FIX_LOOPS tentatives maximum, puis le run échoue.
          log.error('Décision humaine : RETRY — renvoi à edit avec les findings de review.')
          failPhase(reviewPhase, { ...reviewFacts, humanDecision: 'retry' })
          log.phaseEnd(reviewPhaseName, 'fail', { globalVerdict: 'FAIL', humanDecision: 'retry' })

          // Construire le brief de correction à partir des findings.
          const reviewFindings = reviewResult.outcomes
            .filter((o) => o.verdict === 'FAIL' && o.rawOutput)
            .map((o) => `### ${o.reviewerName}\n${o.rawOutput}`)
            .join('\n\n---\n\n')

          // Boucle de retry bornée : MAX_FIX_LOOPS tentatives.
          // Chaque tentative : edit → oracles → si tout passe → review adversariale.
          // Un fichier de plan implicite : on réutilise les fichiers de lastClaimsGate.
          let retryPass = false
          let retryErrorLines = null
          // Reset oracle results for the retry sequence.
          lastOracleResults = []

          for (let retryAttempt = 1; retryAttempt <= MAX_FIX_LOOPS; retryAttempt++) {
            const retryPhaseName = `edit-review-retry-${retryAttempt}`
            const retryPhase = startPhase(theRun, retryPhaseName, 'agent')
            log.phaseStart(retryPhaseName, 'agent')

            // Augmenter le périmètre avec les fichiers référencés dans les diagnostics
            // (même logique que la boucle principale).
            const retryBasePlan = {
              files: lastClaimsGate?.actualFiles ?? [],
              doneWhen: 'Address all review findings',
              steps: [],
            }
            const retryPlanForBrief = retryAttempt === 1
              ? retryBasePlan
              : (() => {
                  const referencedFiles = extractReferencedFiles(retryErrorLines, REPO_ROOT)
                  const augmentedFiles = [...new Set([...retryBasePlan.files, ...referencedFiles])]
                  return { ...retryBasePlan, files: augmentedFiles }
                })()

            const retryBrief = retryAttempt === 1
              ? buildReviewRetryBrief(task, scope, lastClaimsGate, reviewFindings, humanMessage)
              : buildEditorFixBrief(task, scope, retryPlanForBrief, retryErrorLines, retryAttempt)

            let retryCaseId
            try {
              const newCase = await createCase(namespaceId, `factory/us-loop — edit-review-retry-${retryAttempt}`)
              retryCaseId = newCase.id
            } catch (err) {
              failPhase(retryPhase, { agentStatus: 'error', error: String(err) })
              log.phaseEnd(retryPhaseName, 'fail', { error: String(err) })
              endRun(theRun, 'fail')
              return { allPass: false, filePath: theRun.filePath }
            }

            const beforeRetry = snapshotDiff(REPO_ROOT)
            const retryTurn = await runAgentTurn(retryCaseId, editorName, retryBrief, {
              startTimeoutMs: START_TIMEOUT_MS,
              workTimeoutMs: WORK_TIMEOUT_MS,
            })
            const retryChanged = diffSince(beforeRetry, REPO_ROOT)
            const wrongRetryAgent =
              retryTurn.agentsSelected.length > 0 && !retryTurn.agentsSelected.includes(editorName)

            const retryFacts = {
              retryAttempt,
              caseId: retryCaseId,
              agentStatus: retryTurn.status,
              caseStatus: retryTurn.caseStatus,
              agentsSelected: retryTurn.agentsSelected,
              agentTurns: retryTurn.agentTurns,
              toolCallCount: retryTurn.toolCallCount,
              failedToolCalls: retryTurn.failedToolCalls,
              killedByBudget: retryTurn.killedByBudget,
              filesModified: retryChanged.modified,
              filesUntracked: retryChanged.untracked,
              humanMessage: retryAttempt === 1 ? (humanMessage || null) : null,
            }

            if (wrongRetryAgent) {
              failPhase(retryPhase, { ...retryFacts, expectedAgent: editorName })
              log.phaseEnd(retryPhaseName, 'fail', { agentsSelected: retryTurn.agentsSelected })
              endRun(theRun, 'fail')
              return { allPass: false, filePath: theRun.filePath }
            }

            if (retryTurn.status !== 'finished') {
              failPhase(retryPhase, retryFacts)
              log.phaseEnd(retryPhaseName, 'fail', { agentStatus: retryTurn.status })
              allPass = false
              break
            }

            const retryWroteNothing =
              retryChanged.modified.length === 0 && retryChanged.untracked.length === 0

            if (retryWroteNothing) {
              failPhase(retryPhase, { ...retryFacts, wroteNothing: true })
              log.phaseEnd(retryPhaseName, 'fail', { wroteNothing: true })
              log.error(`L'éditeur n'a rien modifié après retry ${retryAttempt}. Case : ${retryCaseId}`)
              allPass = false
              break
            }

            passPhase(retryPhase, retryFacts)
            log.phaseEnd(retryPhaseName, 'pass', {
              filesModified: retryChanged.modified.length,
              agentTurns: retryTurn.agentTurns,
            })

            // Oracles : vérification déterministe identique à la boucle principale.
            let retryOraclePass = true
            let retryOracleErrorLines = null

            for (const oracle of domain.oracles) {
              const retryVerifyName = `verify-${oracle.name}-review-retry-${retryAttempt}`
              const retryVerifyPhase = startPhase(theRun, retryVerifyName, 'code')
              log.phaseStart(retryVerifyName, 'code')

              const effectiveCommand = buildOracleCommand(oracle, retryChanged.modified, REPO_ROOT)
              log.info(`Oracle (retry) : ${oracle.name}`)
              log.info('Commande : ' + effectiveCommand)

              const beforeOracle = snapshotDiff(REPO_ROOT)
              const result = runCommand(effectiveCommand, {
                cwd: oracle.cwd,
                timeoutMs: ORACLE_TIMEOUT_MS,
              })
              const oracleChanged = diffSince(beforeOracle, REPO_ROOT)
              const passed = result.exitCode === 0
              const tasks = countTaskOutcomes(result.stdout + '\n' + result.stderr)

              const retryVerifyFacts = {
                retryAttempt,
                oracle: oracle.name,
                command: effectiveCommand,
                domain: domainName,
                exitCode: result.exitCode,
                timedOut: result.timedOut,
                commandDurationMs: result.durationMs,
                tasks,
                filesModified: oracleChanged.modified,
                filesUntracked: oracleChanged.untracked,
              }

              if (result.timedOut) {
                failPhase(retryVerifyPhase, retryVerifyFacts)
                log.phaseEnd(retryVerifyName, 'fail', { oracle: oracle.name, timedOut: true })
                log.error(`Timeout oracle [${oracle.name}] pendant retry.`)
                endRun(theRun, 'fail')
                return { allPass: false, filePath: theRun.filePath }
              }

              if (passed && tasks.executed === 0) {
                failPhase(retryVerifyPhase, { ...retryVerifyFacts, emptySuccess: true })
                log.phaseEnd(retryVerifyName, 'fail', { oracle: oracle.name, emptySuccess: true })
                log.error(`Oracle [${oracle.name}] retry : succès vide.`)
                endRun(theRun, 'fail')
                return { allPass: false, filePath: theRun.filePath }
              }

              if (passed) {
                passPhase(retryVerifyPhase, retryVerifyFacts)
                log.phaseEnd(retryVerifyName, 'pass', {
                  oracle: oracle.name,
                  exitCode: result.exitCode,
                  commandDurationMs: result.durationMs,
                  tasksExecuted: tasks.executed,
                })
                lastOracleResults.push({
                  name: oracle.name,
                  exitCode: result.exitCode,
                  passed: true,
                  tail: tailLines(result.stdout || result.stderr, TAIL_LINES).join('\n'),
                })
                continue
              }

              // Oracle échoué.
              const stderrTail = tailLines(result.stderr, TAIL_LINES)
              const stdoutTail = tailLines(result.stdout, TAIL_LINES)
              lastOracleResults.push({
                name: oracle.name,
                exitCode: result.exitCode,
                passed: false,
                tail: tailLines(result.stderr || result.stdout, ERROR_LINES_FOR_AGENT).join('\n'),
              })
              failPhase(retryVerifyPhase, { ...retryVerifyFacts, stderrTail, stdoutTail })
              log.phaseEnd(retryVerifyName, 'fail', {
                oracle: oracle.name,
                exitCode: result.exitCode,
                commandDurationMs: result.durationMs,
              })
              // Même logique d'extraction que la boucle principale :
              // diagnostics Jest actionnables pour 'tests', TS pour 'types', tailLines sinon.
              retryOracleErrorLines = oracle.name === 'types'
                ? extractTypeDiagnostics(result.stdout, result.stderr, ERROR_LINES_FOR_AGENT)
                : oracle.name === 'tests'
                  ? extractTestDiagnostics(result.stdout, result.stderr, ERROR_LINES_FOR_AGENT)
                  : tailLines(
                      result.stderr.trim().length > 0 ? result.stderr : result.stdout,
                      ERROR_LINES_FOR_AGENT
                    )
              retryOraclePass = false
              break
            }

            if (!retryOraclePass) {
              retryErrorLines = retryOracleErrorLines
              if (retryAttempt === MAX_FIX_LOOPS) {
                log.error(`Budget de ${MAX_FIX_LOOPS} tentatives de retry épuisé après review.`)
                allPass = false
              }
              continue // next retryAttempt
            }

            // Tous les oracles passent : lancer une nouvelle review adversariale.
            retryPass = true
            log.info(`Retry ${retryAttempt} a passé les oracles — review adversariale.`)

            // Mettre à jour lastClaimsGate avec les fichiers touchés dans ce retry.
            const retryActualChanged = diffSince(beforeRetry, REPO_ROOT)
            lastClaimsGate = {
              ...lastClaimsGate,
              actualFiles: [
                ...new Set([
                  ...(lastClaimsGate?.actualFiles ?? []),
                  ...retryActualChanged.modified,
                  ...retryActualChanged.untracked,
                ])
              ],
            }
            break
          } // fin boucle retry

          if (!retryPass) {
            // Le retry a épuisé son budget sans passer les oracles : run FAIL.
            allPass = false
          } else {
            // Les oracles passent : relancer une review adversariale complète.
            // La variable allPass reste false jusqu'à ce que la review passe.
            // On sort de la branche retry — la review sera relancée dans un second
            // passage de la phase review ci-dessous.
            //
            // Pour éviter de dupliquer le code de review, on relance directement ici.
            const retryReviewPhaseName = 'adversarial-review-retry'
            const retryReviewPhase = startPhase(theRun, retryReviewPhaseName, 'agent')
            log.phaseStart(retryReviewPhaseName, 'agent')

            let retryGitDiff = ''
            const filesToReviewRetry = lastClaimsGate?.actualFiles ?? []
            try {
              if (filesToReviewRetry.length > 0) {
                const fileArgs = filesToReviewRetry.map((f) => `"${f}"`).join(' ')
                retryGitDiff = execSync(
                  `git diff HEAD -- ${fileArgs}`,
                  { cwd: REPO_ROOT, encoding: 'utf8', timeout: 30_000 }
                )
                if (!retryGitDiff.trim()) {
                  retryGitDiff = execSync(
                    `git diff -- ${fileArgs}`,
                    { cwd: REPO_ROOT, encoding: 'utf8', timeout: 30_000 }
                  )
                }
              } else {
                retryGitDiff = execSync('git diff HEAD', { cwd: REPO_ROOT, encoding: 'utf8', timeout: 30_000 })
              }
            } catch (err) {
              log.error(`Impossible de produire le git diff (retry) : ${String(err)}`)
              retryGitDiff = '(git diff unavailable)'
            }

            const retryReviewPacket = {
              task: task ?? ticketContent ?? '(no task)',
              diff: retryGitDiff || '(empty diff)',
              oracleResults: lastOracleResults,
              claimsGate: lastClaimsGate,
            }

            try {
              const retryReviewResult = await runAdversarialReview({
                namespaceId,
                reviewPacket: retryReviewPacket,
              })

              const MAX_RAW_OUTPUT = 4000
              const retryReviewFacts = {
                globalVerdict: retryReviewResult.globalVerdict,
                reviewerCount: retryReviewResult.reviewerCount,
                passCount: retryReviewResult.passCount,
                failCount: retryReviewResult.failCount,
                skipCount: retryReviewResult.skipCount,
                outcomes: retryReviewResult.outcomes.map((o) => ({
                  reviewerName: o.reviewerName,
                  caseId: o.caseId,
                  status: o.status,
                  verdict: o.verdict,
                  hasCritical: o.hasCritical,
                  errorCode: o.errorCode,
                  summary: o.rawOutput
                    ? o.rawOutput.slice(0, MAX_RAW_OUTPUT) + (o.rawOutput.length > MAX_RAW_OUTPUT ? '\n...(tronqué)' : '')
                    : null,
                })),
              }

              if (retryReviewResult.globalVerdict === 'FAIL') {
                // Retry review also failed: run FAIL, no further retry budget.
                allPass = false
                failPhase(retryReviewPhase, retryReviewFacts)
                log.phaseEnd(retryReviewPhaseName, 'fail', {
                  globalVerdict: retryReviewResult.globalVerdict,
                  failCount: retryReviewResult.failCount,
                })
                log.error('Revue adversariale après retry : FAIL — run marqué FAIL.')
              } else if (retryReviewResult.globalVerdict === 'SKIP') {
                passPhase(retryReviewPhase, retryReviewFacts)
                log.phaseEnd(retryReviewPhaseName, 'pass', { globalVerdict: 'SKIP' })
                log.error('Revue adversariale après retry : SKIP — run reste PASS.')
                allPass = true
              } else {
                passPhase(retryReviewPhase, retryReviewFacts)
                log.phaseEnd(retryReviewPhaseName, 'pass', {
                  globalVerdict: retryReviewResult.globalVerdict,
                  passCount: retryReviewResult.passCount,
                })
                log.info(`Revue adversariale après retry PASS (${retryReviewResult.passCount}/4).`)
                allPass = true
              }
            } catch (err) {
              passPhase(retryReviewPhase, { error: String(err), skipped: true })
              log.phaseEnd(retryReviewPhaseName, 'pass', { skipped: true })
              log.error(`Moteur de revue adversariale (retry) en erreur : ${String(err)}. Run reste PASS.`)
              allPass = true
            }
          }

        } else {
          // 'fail' ou timeout : le run échoue.
          allPass = false
          failPhase(reviewPhase, { ...reviewFacts, humanDecision })
          log.phaseEnd(reviewPhaseName, 'fail', {
            globalVerdict: reviewResult.globalVerdict,
            failCount: reviewResult.failCount,
            humanDecision,
          })
          log.error(`Revue adversariale FAIL (décision : ${humanDecision}).`)
        }
      } else if (reviewResult.globalVerdict === 'SKIP') {
        // Tous les reviewers ont été skipés : AgentOS probablement indisponible.
        // On ne bloque pas le run, mais on loggue un avertissement.
        passPhase(reviewPhase, reviewFacts)
        log.phaseEnd(reviewPhaseName, 'pass', { globalVerdict: 'SKIP' })
        log.error(
          `Revue adversariale SKIP : tous les reviewers ont été sautés ` +
          `(AgentOS indisponible ou timeout). Le run reste PASS.`
        )
      } else {
        passPhase(reviewPhase, reviewFacts)
        log.phaseEnd(reviewPhaseName, 'pass', {
          globalVerdict: reviewResult.globalVerdict,
          passCount: reviewResult.passCount,
          skipCount: reviewResult.skipCount,
        })
        log.info(`Revue adversariale PASS (${reviewResult.passCount}/4 reviewers).`)
      }
    } catch (err) {
      // Échec inattendu du moteur de review : warning, ne pas bloquer.
      passPhase(reviewPhase, { error: String(err), skipped: true })
      log.phaseEnd(reviewPhaseName, 'pass', { skipped: true })
      log.error(`Moteur de revue adversariale en erreur : ${String(err)}. Le run reste PASS.`)
    }
  }

  endRun(theRun, allPass ? 'pass' : 'fail')
  return { allPass, filePath: theRun.filePath }
}
