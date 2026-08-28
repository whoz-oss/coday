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
import { buildOracleCommand } from '../lib/oracle-command.mjs'
import { extractTicketId, extractAdfText, fetchJiraTicket } from '../lib/jira.mjs'
import { join, dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

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

    `## Compiler output\n\`\`\`\n${errorLines.join('\n')}\n\`\`\``,
  ]

  if (scope) {
    sections.push(`## Scope\n${scope}`)
  }

  sections.push(
    '## Implementation plan\n' +
    `Files to modify:\n` +
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
    'If the error indicates the real problem lies outside the scope above, say so ' +
    'explicitly and stop. Reporting that is a successful outcome, not a failure.'
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
      passPhase(fetchTicketPhase, { ticketId, summary: result.summary, fieldCount: result.fieldCount })
      log.phaseEnd('fetch-ticket', 'pass', { ticketId, fieldCount: result.fieldCount })
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
        failedToolCalls: turn.failedToolCalls,
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

      const editorBrief =
        attempt === 1
          ? buildEditorBrief(task, scope, plan)
          : buildEditorFixBrief(task, scope, plan, errorLines, attempt)

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
        failedToolCalls: turn.failedToolCalls,
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
        failPhase(editPhase, { ...editFacts, wroteNothing: true })
        log.phaseEnd(editPhaseName, 'fail', { wroteNothing: true })
        log.error(
          "L'éditeur a terminé sans modifier aucun fichier. " +
            'Soit la tâche est hors périmètre (sortie honorable), soit le plan est mal compris. ' +
            `Consulter le case ${editorCaseId} dans AgentOS pour lire sa réponse.`
        )
        endRun(theRun, 'fail')
        return { allPass: false, filePath: theRun.filePath }
      }

      passPhase(editPhase, editFacts)
      log.phaseEnd(editPhaseName, 'pass', {
        agentTurns: turn.agentTurns,
        toolCallCount: turn.toolCallCount,
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

        log.info(`Oracle : ${oracle.name}`)
        log.info('Commande : ' + effectiveCommand)
        log.info('Répertoire : ' + oracle.cwd)
        log.info('La commande peut prendre plusieurs minutes sans sortie intermédiaire.')

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

        // SUCCÈS VIDE (executed === 0) — arrêt immédiat, sortie des DEUX boucles
        // (correctif A8, incident du 2026-08-20)
        //
        // Quand `exitCode === 0` mais `tasks.executed === 0`, l'oracle n'a rien
        // exécuté : tout a été servi par le cache. Le verdict est vrai et VIDE.
        //
        // C'est un échec de l'INSTRUMENT, pas un verdict sur le travail de l'agent.
        // La phase est enregistrée en `failPhase` avec le fait `emptySuccess: true`.
        //
        // POURQUOI NE PAS RELANCER L'ÉDITEUR :
        // Relancer l'éditeur sur cette non-information lui demanderait de corriger un
        // problème qu'on n'a pas mesuré. Un modèle à qui l'on demande de corriger sans
        // lui donner d'erreur produit quelque chose de plausible — travail parasite
        // avec verdict cohérent, la famille de panne la plus coûteuse de ce système.
        if (passed && tasks.executed === 0) {
          failPhase(verifyPhase, { ...verifyFacts, emptySuccess: true })
          log.phaseEnd(verifyPhaseName, 'fail', {
            oracle: oracle.name,
            exitCode: result.exitCode,
            emptySuccess: true,
            commandDurationMs: result.durationMs,
          })
          log.error(
            `Oracle [${oracle.name}] : succès vide — aucune tâche exécutée ` +
              `(up-to-date=${tasks.upToDate}, from-cache=${tasks.fromCache}). ` +
              `Ce n'est PAS un verdict sur le travail de l'éditeur — le code n'a été ` +
              `ni validé ni invalidé. Causes plausibles : soit les fichiers modifiés ` +
              `ne sont pas dans le graphe d'entrée de cet oracle, soit le cache doit ` +
              `être invalidé (pnpm nx reset dans le dépôt cible).`
          )
          endRun(theRun, 'fail')
          return { allPass: false, filePath: theRun.filePath }
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
          // Oracle passé, on continue avec le suivant.
          continue
        }

        // Échec : préparer les extraits pour le brief suivant.
        const stderrTail = tailLines(result.stderr, TAIL_LINES)
        const stdoutTail = tailLines(result.stdout, TAIL_LINES)

        failPhase(verifyPhase, { ...verifyFacts, stderrTail, stdoutTail })
        log.phaseEnd(verifyPhaseName, 'fail', {
          oracle: oracle.name,
          exitCode: result.exitCode,
          timedOut: result.timedOut,
          commandDurationMs: result.durationMs,
        })

        const source = result.stderr.trim().length > 0 ? result.stderr : result.stdout
        oracleErrorLines = tailLines(source, ERROR_LINES_FOR_AGENT)

        log.error(
          `Oracle [${oracle.name}] verdict négatif (exitCode=${result.exitCode}) ` +
            `— tentative ${attempt}/${MAX_FIX_LOOPS} (révision ${revision})`
        )
        for (const line of oracleErrorLines.slice(-TAIL_LINES)) {
          log.error(line)
        }

        oraclePass = false
        break // Au premier oracle échoué, on s'arrête : les suivants ne tournent pas.
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
      // Claims-gate (code)
      //
      // Compare les fichiers réellement modifiés (depuis le début de la boucle
      // d'édition) aux fichiers annoncés par le plan.
      //
      // UN ÉCART NE FAIT PAS ÉCHOUER LE RUN — c'est un fait à porter.
      // La phase passe, mais log.error avertit si claimsMatch === false.
      // -----------------------------------------------------------------------
      const claimsGateName = `claims-gate-${revision}`
      const claimsGatePhase = startPhase(theRun, claimsGateName, 'code')
      log.phaseStart(claimsGateName, 'code')

      const actualChanged = diffSince(beforeEditing, REPO_ROOT)
      const claims = compareClaims(plan.files, actualChanged.modified, actualChanged.untracked)

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

      if (!claims.claimsMatch) {
        if (claims.unplannedFiles.length > 0) {
          log.error(
            `Écart claims-gate : ${claims.unplannedFiles.length} fichier(s) modifié(s) non annoncé(s) : ` +
              claims.unplannedFiles.join(', ')
          )
        }
        if (claims.untouchedPlannedFiles.length > 0) {
          log.error(
            `Écart claims-gate : ${claims.untouchedPlannedFiles.length} fichier(s) annoncé(s) non touché(s) : ` +
              claims.untouchedPlannedFiles.join(', ')
          )
        }
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

  endRun(theRun, allPass ? 'pass' : 'fail')
  return { allPass, filePath: theRun.filePath }
}
