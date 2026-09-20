/**
 * Dashboard server — Software Factory.
 *
 * Usage : node factory/dashboard/server.mjs
 * Port  : 3141 (configurable via PORT env)
 *
 * Endpoints :
 *   GET  /                          → index.html
 *   GET  /api/config                → { agentosUrl } pour les liens profonds
 *   GET  /api/runs                  → liste des runs
 *   GET  /api/runs/:id              → détail d'un run
 *   POST /api/runs                  → lancer un run (répond avec { runId })
 *   GET  /api/runs/:id/stream       → SSE stdout/stderr en temps réel
 *   GET  /api/agents?namespaceId=   → proxy AgentOS (liste agents)
 *   GET  /api/cases/:caseId/events  → proxy AgentOS (événements d'un case)
 *   GET  /api/jira/:ticketId        → contenu d'un ticket Jira (récupéré maintenant)
 *
 * POURQUOI UN PROXY D'ÉVÉNEMENTS (décision O-B, 2026-08-22)
 * ---------------------------------------------------------
 * Le registre ne contient aucun texte produit par un LLM — c'est l'invariant 2.
 * Le récit (brief envoyé, réponse de l'agent, appels d'outils) vit dans AgentOS,
 * pas ici. Plutôt que de dupliquer ce texte dans les JSONL, le dashboard va le
 * chercher à la source au moment de l'affichage, via le `caseId` que le registre
 * enregistre comme un fait.
 *
 * La séparation est ainsi préservée : le registre reste un instrument de faits,
 * AgentOS reste la source du récit, et l'écran les met côte à côte sans les
 * mélanger.
 */

import { createServer } from 'node:http'
import { readFileSync, writeFileSync, readdirSync, existsSync } from 'node:fs'
import { homedir } from 'node:os'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawn } from 'node:child_process'
import { fetchJiraTicket } from '../lib/jira.mjs'
import { discoverJiraCredentials } from '../lib/coday-config.mjs'
import { registerGate, unregisterGate, getGate, writeGateReply } from '../lib/review-gate.mjs'
import { listForgeRunProjections, parseForgeLedger, projectForgeRun, createEpicRun } from '../lib/forge-ledger.mjs'
import { recordHumanDecision } from '../lib/forge-human-decision.mjs'
import { defaultRunStoreRoot, resolveForgeRoots, REPO_RUN_STORE_POLICY } from '../lib/forge-roots.mjs'
import { evaluateG2 } from '../lib/forge-g2.mjs'
import { executeStoryAnalysis } from '../lib/forge-story-analysis.mjs'
import { executeStoryEdit } from '../lib/forge-story-edit.mjs'
import { executeStoryOracles, isAllowedStoryOracleRequestBody } from '../lib/forge-story-oracles.mjs'
import { WorkflowProjectionStore } from '../lib/workflow-projection-store.mjs'
import { handleWorkflowProjectionRequest } from './workflow-projection-routes.mjs'
import { WorkflowProjectionSseHub } from './workflow-projection-sse.mjs'
import { handleForgeWorkflowProjectionRequest } from './forge-workflow-projection-routes.mjs'
import { WorkflowDefinitionRegistry } from '../lib/workflow-definition-registry.mjs'
import { handleWorkflowDefinitionRequest } from './workflow-definition-routes.mjs'
import { WorkflowEvidenceStore } from '../lib/workflow-evidence-store.mjs'
import { handleWorkflowEvidenceRequest } from './workflow-evidence-routes.mjs'
import { handleWorkflowTransitionRequest } from './workflow-transition-routes.mjs'
import { handleWorkflowOracleRequest } from './workflow-oracle-routes.mjs'
import { OracleDefinitionRegistry } from '../lib/oracle-definition.mjs'
import { handleWorkflowCodeTransitionRequest } from './workflow-code-transition-routes.mjs'
import { WorkflowHumanInteractionStore } from '../lib/workflow-human-interaction-store.mjs'
import { handleWorkflowHumanInteractionRequest } from './workflow-human-interaction-routes.mjs'

import { WorkUnitEnvironmentStore } from '../lib/work-unit-environment-store.mjs'
import { GitWorktreeProvisioner } from '../lib/git-worktree.mjs'
import { WorkUnitEnvironmentController, handleWorkUnitEnvironmentRequest } from '../lib/work-unit-environment-controller.mjs'
import { defaultDeliveryDefinition } from '../lib/delivery-definition.mjs'
import { DeliveryStore } from '../lib/delivery-store.mjs'
import { DeliveryEvidenceStore } from '../lib/delivery-evidence-store.mjs'
import { DeliveryGitControlPlane } from '../lib/delivery-git-control-plane.mjs'
import { DeliveryPullRequestAdapter } from '../lib/delivery-pr-adapter.mjs'
import { DeliveryController, handleDeliveryRequest } from '../lib/delivery-controller.mjs'
import { FactoryOperationalMetricsService } from '../lib/factory-operational-metrics-service.mjs'
import { handleWorkflowOperationalMetricsRequest } from './workflow-operational-metrics-routes.mjs'
const __dirname = dirname(fileURLToPath(import.meta.url))
const RUNS_DIR = join(__dirname, '..', 'runs')
const RUN_ENTRY = join(__dirname, '..', 'run.mjs')
const PORT = parseInt(process.env.PORT ?? '3141', 10)
const LOOPBACK_HOSTS = new Set(['127.0.0.1', '::1', 'localhost'])
export function resolveFactoryBindPolicy(env = process.env) {
  const host = env.FACTORY_BIND_HOST ?? '127.0.0.1'
  const unsafeRemote = env.FACTORY_UNSAFE_ALLOW_REMOTE_BIND === 'true'
  if (!LOOPBACK_HOSTS.has(host) && !unsafeRemote) {
    throw new Error('FACTORY_BIND_HOST must be loopback unless FACTORY_UNSAFE_ALLOW_REMOTE_BIND=true is explicitly set.')
  }
  return { host, trustMode: LOOPBACK_HOSTS.has(host) ? 'loopback-only' : 'unsafe-remote-unauthenticated' }
}
const FACTORY_BIND_POLICY = resolveFactoryBindPolicy()
const AGENTOS_URL = process.env.AGENTOS_URL ?? 'http://localhost:8124'
// Generic workflow data is independent from cwd, repo roots, namespace config,
// and target checkouts. FACTORY_DATA_ROOT may override this explicit user-data default.
const FACTORY_DATA_ROOT = process.env.FACTORY_DATA_ROOT ?? join(homedir(), '.coday', 'factory')
const workflowProjectionStore = new WorkflowProjectionStore(FACTORY_DATA_ROOT)
const workflowProjectionSseHub = new WorkflowProjectionSseHub()
const workflowDefinitionRegistry = new WorkflowDefinitionRegistry(join(__dirname, '..', 'workflows'))
const workflowEvidenceStore = new WorkflowEvidenceStore(FACTORY_DATA_ROOT)
const workflowHumanInteractionStore = new WorkflowHumanInteractionStore(FACTORY_DATA_ROOT)
const FACTORY_REPO_ROOT = process.env.FACTORY_REPO_ROOT
const FACTORY_WORKTREES_ROOT = process.env.FACTORY_WORKTREES_ROOT
const workUnitEnvironmentStore = new WorkUnitEnvironmentStore(FACTORY_DATA_ROOT)
const workUnitEnvironmentController = FACTORY_REPO_ROOT && FACTORY_WORKTREES_ROOT ? new WorkUnitEnvironmentController({
  store: workUnitEnvironmentStore,
  git: new GitWorktreeProvisioner({ worktreesRoot: FACTORY_WORKTREES_ROOT }),
  policy: { resolve: async (_namespaceId, request) => ({ repoRoot: FACTORY_REPO_ROOT, worktreePath: join(FACTORY_WORKTREES_ROOT, `${request.workflowId}-${request.workUnitId}`) }) },
  workflowStore: workflowProjectionStore,
}) : null
const deliveryStore = new DeliveryStore(FACTORY_DATA_ROOT)
const deliveryEvidenceStore = new DeliveryEvidenceStore(FACTORY_DATA_ROOT)
const workflowOperationalMetricsService = new FactoryOperationalMetricsService({
  workflowStore: workflowProjectionStore,
  humanInteractionStore: workflowHumanInteractionStore,
  deliveryStore,
  deliveryEvidenceStore,
})
const deliveryRemote = process.env.FACTORY_DELIVERY_GIT_REMOTE ?? null
const deliveryAllowedPaths = (process.env.FACTORY_DELIVERY_ALLOWED_PATHS ?? '').split(',').map((value) => value.trim()).filter(Boolean)
const deliveryProtectedPaths = (process.env.FACTORY_DELIVERY_PROTECTED_PATHS ?? '.git,.coday').split(',').map((value) => value.trim()).filter(Boolean)
const deliveryGit = new DeliveryGitControlPlane({
  serviceIdentity: { name: process.env.FACTORY_GIT_COMMITTER_NAME ?? 'Coday Factory', email: process.env.FACTORY_GIT_COMMITTER_EMAIL ?? 'factory@localhost' },
  configuredRemote: deliveryRemote,
  allowedPaths: deliveryAllowedPaths,
  protectedPaths: deliveryProtectedPaths,
})
// GitHub provider wiring is intentionally absent until a trusted server-side adapter is configured.
// The adapter reports PULL_REQUEST_NOT_CONFIGURED; it never fabricates a PR success.
const deliveryPullRequests = new DeliveryPullRequestAdapter()
const deliveryController = workUnitEnvironmentController ? new DeliveryController({
  store: deliveryStore,
  evidenceStore: deliveryEvidenceStore,
  environmentController: workUnitEnvironmentController,
  workflowStore: workflowProjectionStore,
  git: deliveryGit,
  pullRequests: deliveryPullRequests,
  definition: defaultDeliveryDefinition(),
  trustedConfiguration: process.env.FACTORY_GITHUB_OWNER && process.env.FACTORY_GITHUB_REPO && process.env.FACTORY_DELIVERY_BASE_BRANCH ? { pullRequest: { owner: process.env.FACTORY_GITHUB_OWNER, repo: process.env.FACTORY_GITHUB_REPO, baseBranch: process.env.FACTORY_DELIVERY_BASE_BRANCH } } : {},
}) : null
// Oracle definitions are supplied by the trusted composition root. The repository
// currently publishes no production oracle; source tests inject their fixture registry.
const FACTORY_ORACLE_DEFINITIONS_ROOT = process.env.FACTORY_ORACLE_DEFINITIONS_ROOT
const oracleDefinitionRegistry = FACTORY_ORACLE_DEFINITIONS_ROOT ? new OracleDefinitionRegistry(FACTORY_ORACLE_DEFINITIONS_ROOT) : null
const FACTORY_ORACLE_REPO_ROOT = process.env.FACTORY_ORACLE_REPO_ROOT
// Trusted control-plane configuration: never inferred from cwd or namespace configPath.
const FACTORY_ORACLE_NAMESPACE_ID = process.env.FACTORY_ORACLE_NAMESPACE_ID
// Explicit store location for Forge Epic/Story projections. It is intentionally
// independent from both this dashboard's source tree and the target repoRoot.
// FACTORY_USER: used only to identify the AgentOS user for proxy headers.
// No hardcoded personal username — resolved from Coday config or left undefined.
const FACTORY_USER = process.env.FACTORY_USER

// ---------------------------------------------------------------------------
// Jira credentials — env vars take priority; Coday user.yaml is the fallback.
//
// discoverJiraCredentials() reads ~/.coday/users/<sanitized-dir>/user.yaml.
// When FACTORY_USER is set, it targets that specific user directory.
// When absent, it auto-discovers from all user directories (requires exactly one
// to have Jira credentials; zero or multiple keeps Jira unavailable).
// ---------------------------------------------------------------------------
const _codayDiscovery = discoverJiraCredentials(FACTORY_USER)
const _codayJira = _codayDiscovery.credentials

// ---------------------------------------------------------------------------
// Credentials Jira — lus depuis l'environnement du dashboard, pas du formulaire.
//
// POURQUOI ICI ET PAS DANS LE FORMULAIRE (décision O-C, 2026-08-26)
// ------------------------------------------------------------------
// Le formulaire garde FACTORY_TICKET (l'identifiant du ticket, qui n'est pas
// un secret : c'est un numéro visible dans l'URL). Les credentials (email +
// token API) sont des secrets : un token d'API n'a rien à faire dans un
// formulaire web, même local. Il transiterait en clair dans la requête HTTP,
// serait visible dans l'onglet Réseau des DevTools, et serait logé si le
// serveur log ses requêtes.
//
// Le serveur du dashboard est lancé manuellement par un humain qui maîtrise
// son environnement : c'est l'endroit où passer des secrets. Si les variables
// sont absentes, l'endpoint répond 501 avec un message actionnable.
//
// SOURCE UNIQUE (unification 2026-08-28)
// ----------------------------------------
// Le formulaire ne porte plus du tout ces trois champs. Ces constantes sont
// désormais la seule source pour le run (propagation explicite dans launchRun)
// ET pour l'affichage (endpoint /api/jira/:ticketId). Avant cette unification,
// deux sources indépendantes pour le même secret pouvaient diverger sans que
// rien ne le signale : le formulaire renvoyait ses valeurs au process enfant,
// l'environnement du serveur alimentait l'endpoint d'affichage, et aucun
// mécanisme ne garantissait qu'elles étaient identiques.
// ---------------------------------------------------------------------------
// Priorité : variable d'environnement > user.yaml Coday.
// Le fallback sur user.yaml évite de devoir passer les credentials à chaque
// démarrage quand ils sont déjà configurés dans Coday.
// Note: _codayJira.username is the Jira API email, distinct from codayUsername.
const JIRA_BASE_URL = process.env.JIRA_BASE_URL ?? _codayJira?.apiUrl ?? null
const JIRA_EMAIL = process.env.JIRA_EMAIL ?? _codayJira?.jiraUsername ?? null
const JIRA_API_TOKEN = process.env.JIRA_API_TOKEN ?? _codayJira?.apiKey ?? null

// Resolved Coday username for AgentOS proxy headers (may be undefined)
const RESOLVED_FACTORY_USER = FACTORY_USER ?? _codayJira?.codayUsername ?? undefined

// ---------------------------------------------------------------------------
// Registre en mémoire des process en cours
// { runId → { child: ChildProcess|null, listeners: Set<ServerResponse>, lines: string[], stopping: boolean } }
// ---------------------------------------------------------------------------
const activeRuns = new Map()

// ---------------------------------------------------------------------------
// Lecture des JSONL
// ---------------------------------------------------------------------------

function parseJsonl(filePath) {
  try {
    return readFileSync(filePath, 'utf8')
      .split('\n')
      .filter(Boolean)
      .map((line) => { try { return JSON.parse(line) } catch { return null } })
      .filter(Boolean)
  } catch {
    return []
  }
}

function summarizeRun(runId) {
  const filePath = join(RUNS_DIR, `${runId}.jsonl`)
  const lines = parseJsonl(filePath)

  const start = lines.find((l) => l.kind === 'run_start')
  const end = lines.find((l) => l.kind === 'run_end')
  const phaseEnds = lines.filter((l) => l.kind === 'phase_end')

  let status = 'running'
  if (end) status = end.status
  else if (!activeRuns.has(runId)) status = 'crashed'

  // namespaceId is a durable fact written at run creation time (run_start record).
  // Historical runs that pre-date this field will have undefined here, which is
  // correct: they genuinely have no namespace association.
  const namespaceId = start?.namespaceId ?? undefined

  const summary = {
    runId,
    workflow: start?.workflow ?? '?',
    startedAt: start?.startedAt ?? null,
    endedAt: end?.endedAt ?? null,
    durationMs: end?.durationMs ?? null,
    status,
    phaseCount: phaseEnds.length,
    context: extractContext(lines),
  }
  if (namespaceId !== undefined) summary.namespaceId = namespaceId
  return summary
}

/**
 * Extrait le contexte d'un run depuis les faits de ses phases.
 *
 * Rien n'est calculé ni déduit : chaque valeur est lue telle quelle dans un
 * `facts` déjà enregistré. Le but est de remonter en tête d'écran ce qu'il
 * fallait jusqu'ici déplier une phase pour lire — sur quel dépôt le run a
 * tourné, avec quelle commande de vérité, sur quel ticket.
 *
 * NOTE : `namespaceId` n'est enregistré nulle part par les workflows. Il est
 * récupéré à l'affichage depuis les événements du case (chaque `CaseEvent` le
 * porte), et non depuis le registre.
 *
 * @param {object[]} lines  Lignes JSONL déjà parsées.
 * @returns {object}
 */
function extractContext(lines) {
  const ctx = {}
  for (const l of lines) {
    if (l.kind !== 'phase_end') continue
    const f = l.facts ?? {}
    if (f.domain && !ctx.domain) ctx.domain = f.domain
    if (f.rootPath && !ctx.rootPath) ctx.rootPath = f.rootPath
    if (f.command && !ctx.command) ctx.command = f.command
    if (f.ticketId && !ctx.ticketId) ctx.ticketId = f.ticketId
    if (f.summary && !ctx.ticketSummary) ctx.ticketSummary = f.summary
    // Rôles : un seul pour fix-loop, deux pour us-loop.
    if (f.agentName && !ctx.roles) ctx.roles = [f.agentName]
    if (f.analystName || f.editorName) {
      ctx.roles = [f.analystName, f.editorName].filter(Boolean)
    }
  }
  return ctx
}

/**
 * Rebuild phases exclusively from registry records. A phase start timestamp is
 * written on the `phase` record; `phase_end` contributes only the outcome and
 * duration. Keeping that boundary explicit avoids accidentally using an end
 * record's timestamp as a visual start.
 */
function reconstructPhases(lines) {
  const phaseOrder = []
  const phaseStarts = new Map()
  const phaseEnds = new Map()
  for (const line of lines) {
    if (line.kind === 'phase') {
      if (!phaseStarts.has(line.name)) phaseOrder.push(line.name)
      phaseStarts.set(line.name, line)
    } else if (line.kind === 'phase_end') {
      phaseEnds.set(line.name, line)
    }
  }
  return phaseOrder.map((name) => {
    const start = phaseStarts.get(name)
    const end = phaseEnds.get(name)
    return {
      name,
      phaseKind: start?.phaseKind ?? '?',
      status: end?.status ?? 'running',
      startedAt: start?.startedAt ?? null,
      durationMs: end?.durationMs ?? null,
      facts: end?.facts ?? {},
    }
  })
}

function detailRun(runId) {
  const filePath = join(RUNS_DIR, `${runId}.jsonl`)
  if (!existsSync(filePath)) return null

  const lines = parseJsonl(filePath)
  const summary = summarizeRun(runId)
  const phases = reconstructPhases(lines)

  // Lignes de log en mémoire (uniquement pour les runs actifs)
  const logLines = activeRuns.get(runId)?.lines ?? []

  return { ...summary, phases, logLines }
}

function listRuns() {
  let files
  try { files = readdirSync(RUNS_DIR).filter((f) => f.endsWith('.jsonl')) }
  catch { return [] }
  return files
    .sort((a, b) => b.localeCompare(a))
    .map((f) => summarizeRun(f.replace('.jsonl', '')))
}

function readWorkstreams(repoRoot) {
  const tomlPath = join(repoRoot, 'forge/bmad/workstreams.toml')
  if (!existsSync(tomlPath)) return []

  const content = readFileSync(tomlPath, 'utf8')
  const workstreams = []

  // Parser les sections [workstreams.<slug>]
  const sectionRegex = /^\[workstreams\.([a-z0-9]+(?:-[a-z0-9]+)*)\]$/gm
  let match
  while ((match = sectionRegex.exec(content)) !== null) {
    const slug = match[1]
    const sectionStart = match.index + match[0].length
    // Trouver la fin de la section (prochaine section ou EOF)
    const nextSection = /^\[/m.exec(content.slice(sectionStart))
    const sectionContent = nextSection
      ? content.slice(sectionStart, sectionStart + nextSection.index)
      : content.slice(sectionStart)

    const nameMatch = /^name\s*=\s*"([^"]+)"/m.exec(sectionContent)
    const statusMatch = /^status\s*=\s*"([^"]+)"/m.exec(sectionContent)

    if (nameMatch && statusMatch) {
      workstreams.push({
        slug,
        name: nameMatch[1],
        status: statusMatch[1],
      })
    }
  }

  return workstreams
}

// ---------------------------------------------------------------------------
// Lancement d'un run
// ---------------------------------------------------------------------------

/**
 * Démarre node factory/run.mjs <workflow>.
 *
 * Stratégie pour obtenir le runId :
 * - Le workflow appelle createRun() qui crée immédiatement un fichier JSONL
 *   dans factory/runs/ avec un nom de la forme <runId>.jsonl.
 * - On surveille le répertoire factory/runs/ pour détecter ce nouveau fichier
 *   via watchFile sur les fichiers existants + polling.
 * - Plus simple : on prend un snapshot des fichiers existants AVANT de lancer,
 *   puis on poll jusqu'à ce qu'un nouveau fichier apparaisse.
 *
 * @param {object} params
 * @returns {{ pid: number, error?: string }}
 */
function launchRun(params) {
  const {
    workflow = 'fix-loop',
    FACTORY_NAMESPACE_ID,
    FACTORY_AGENT,
    FACTORY_AGENT_ANALYST,
    FACTORY_AGENT_EDITOR,
    FACTORY_TASK,
    FACTORY_SCOPE,
    FACTORY_DOMAIN,
    AGENTOS_URL: agentosUrl,
    FACTORY_USER: factoryUser,
  } = params

  // Les workflows et diagnostics n'ont pas les mêmes rôles.
  //
  // fix-loop / agentos-smoke : un seul rôle (FACTORY_AGENT).
  // us-loop  : deux rôles distincts (FACTORY_AGENT_ANALYST + FACTORY_AGENT_EDITOR),
  //            et FACTORY_AGENT n'est PAS lu par le workflow.
  // backend-oracle-check : aucun agent, pas de FACTORY_NAMESPACE_ID requis.
  //
  // Les alias hérités (smoke, verify-back) sont acceptés ici aussi.
  const ITEMS_WITH_SINGLE_ROLE = new Set(['fix-loop', 'smoke', 'agentos-smoke'])
  const ITEMS_WITHOUT_AGENT = new Set(['backend-oracle-check', 'verify-back'])

  if (!FACTORY_NAMESPACE_ID && !ITEMS_WITHOUT_AGENT.has(workflow)) return { error: 'FACTORY_NAMESPACE_ID manquant' }
  if (!FACTORY_TASK && !ITEMS_WITHOUT_AGENT.has(workflow)) return { error: 'FACTORY_TASK manquant' }
  if (ITEMS_WITH_SINGLE_ROLE.has(workflow) && !FACTORY_AGENT) {
    return { error: `FACTORY_AGENT manquant (requis par "${workflow}")` }
  }

  // Fail-fast : refuser avant de créer quoi que ce soit si un ticket est demandé
  // mais que les credentials Jira manquent dans l'environnement du serveur.
  //
  // Sans cette garde, le run part, crée un fichier de registre, démarre un
  // process, et échoue dans la phase `fetch-ticket` — proprement, mais tard.
  // Refuser ici est préférable : aucun artefact n'est créé, et le message dit
  // exactement quoi faire.
  if (params.FACTORY_TICKET) {
    const missingJira = [
      !JIRA_BASE_URL ? 'JIRA_BASE_URL' : null,
      !JIRA_EMAIL    ? 'JIRA_EMAIL'    : null,
      !JIRA_API_TOKEN ? 'JIRA_API_TOKEN' : null,
    ].filter(Boolean)
    if (missingJira.length > 0) {
      return {
        error:
          `Le serveur du dashboard n'a pas de credentials Jira configurés ` +
          `(manquant : ${missingJira.join(', ')}). ` +
          `Relancez-le avec ces variables dans son environnement : ` +
          `JIRA_BASE_URL=https://votre-instance.atlassian.net ` +
          `JIRA_EMAIL=votre@email.com ` +
          `JIRA_API_TOKEN=votre-token ` +
          `node factory/dashboard/server.mjs`,
      }
    }
  }

  // Snapshot des fichiers existants avant le lancement
  let existingFiles
  try { existingFiles = new Set(readdirSync(RUNS_DIR)) }
  catch { existingFiles = new Set() }

  const env = {
    ...process.env,
    FACTORY_NAMESPACE_ID,
    FACTORY_TASK,
    AGENTOS_URL: agentosUrl ?? AGENTOS_URL,
    FACTORY_USER: factoryUser ?? RESOLVED_FACTORY_USER,
  }
  if (FACTORY_AGENT) env.FACTORY_AGENT = FACTORY_AGENT
  if (FACTORY_AGENT_ANALYST) env.FACTORY_AGENT_ANALYST = FACTORY_AGENT_ANALYST
  if (FACTORY_AGENT_EDITOR) env.FACTORY_AGENT_EDITOR = FACTORY_AGENT_EDITOR
  if (FACTORY_SCOPE) env.FACTORY_SCOPE = FACTORY_SCOPE
  if (FACTORY_DOMAIN) env.FACTORY_DOMAIN = FACTORY_DOMAIN
  if (params.FACTORY_ROOT) env.FACTORY_ROOT = params.FACTORY_ROOT
  if (params.FACTORY_COMMAND_FRONT) env.FACTORY_COMMAND_FRONT = params.FACTORY_COMMAND_FRONT
  if (params.FACTORY_COMMAND_BACK) env.FACTORY_COMMAND_BACK = params.FACTORY_COMMAND_BACK
  if (params.FACTORY_CWD_FRONT) env.FACTORY_CWD_FRONT = params.FACTORY_CWD_FRONT
  if (params.FACTORY_CWD_BACK) env.FACTORY_CWD_BACK = params.FACTORY_CWD_BACK
  if (params.FACTORY_TICKET) env.FACTORY_TICKET = params.FACTORY_TICKET

  // Propagation explicite des credentials Jira depuis les constantes du module.
  //
  // Ces variables sont déjà héritées de l'environnement du serveur via le
  // spread `{ ...process.env, … }` ci-dessus. On les répète ici pour la
  // lisibilité : quelqu'un qui lit launchRun() doit voir d'où viennent les
  // credentials du process enfant sans avoir à raisonner sur le spread.
  //
  // Les valeurs éventuellement envoyées par le client dans `params` sont
  // ignorées silencieusement : elles ne sont jamais lues ni propagées.
  if (JIRA_BASE_URL) env.JIRA_BASE_URL = JIRA_BASE_URL
  if (JIRA_EMAIL) env.JIRA_EMAIL = JIRA_EMAIL
  if (JIRA_API_TOKEN) env.JIRA_API_TOKEN = JIRA_API_TOKEN

  // Construire les arguments CLI selon la forme canonique.
  // Les alias hérités (smoke, verify-back) sont transmis tels quels ;
  // run.mjs les résout et affiche l'avertissement de déprécation.
  const WORKFLOWS = new Set(['fix-loop', 'us-loop'])
  const DIAGNOSTICS = new Set(['agentos-smoke', 'backend-oracle-check'])
  let runArgs
  if (WORKFLOWS.has(workflow)) {
    runArgs = ['workflow', workflow]
  } else if (DIAGNOSTICS.has(workflow)) {
    runArgs = ['diagnostic', workflow]
  } else {
    // Alias hérité ou inconnu : passer directement, run.mjs gère l'erreur
    runArgs = [workflow]
  }

  const child = spawn(process.execPath, [RUN_ENTRY, ...runArgs], {
    env,
    stdio: ['ignore', 'pipe', 'pipe'],
  })

  // Entrée provisoire indexée par PID le temps de trouver le runId
  const pidKey = `pid:${child.pid}`
  activeRuns.set(pidKey, { child, listeners: new Set(), lines: [], stopping: false })

  let runId = null
  const entry = () => activeRuns.get(runId ?? pidKey)

  function broadcast(line) {
    const e = entry()
    if (!e) return
    e.lines.push(line)
    for (const res of e.listeners) {
      try { res.write(`data: ${JSON.stringify({ line })}\n\n`) }
      catch { e.listeners.delete(res) }
    }
  }

  // Poll pour découvrir le nouveau fichier JSONL (créé dans les premières ms)
  const pollInterval = setInterval(() => {
    let files
    try { files = readdirSync(RUNS_DIR).filter((f) => f.endsWith('.jsonl')) }
    catch { return }
    const newFile = files.find((f) => !existingFiles.has(f))
    if (newFile && !runId) {
      runId = newFile.replace('.jsonl', '')
      // Migrer l'entrée du PID vers le runId
      const old = activeRuns.get(pidKey)
      if (old) {
        activeRuns.delete(pidKey)
        activeRuns.set(runId, old)
      }
    }
  }, 200)

  // Lire stdout/stderr ligne par ligne et broadcaster
  let stdoutBuf = ''
  child.stdout.on('data', (chunk) => {
    stdoutBuf += chunk.toString()
    const parts = stdoutBuf.split('\n')
    stdoutBuf = parts.pop()
    for (const line of parts) {
      // Intercept run-scoped review gate IPC signal before broadcasting.
      // The workflow emits a JSON line with __factory_gate:'open' on stdout
      // when it reaches the human decision gate. We parse it here and store
      // the gate in the server's in-process registry keyed by runId.
      // The line is NOT forwarded to SSE listeners (it is internal IPC).
      if (line.startsWith('{"__factory_gate":"open"')) {
        try {
          const signal = JSON.parse(line)
          if (signal.__factory_gate === 'open' && signal.runId) {
            registerGate({
              runId: signal.runId,
              gateType: signal.gateType ?? 'adversarial-review',
              findings: signal.findings ?? '',
              outcomes: signal.outcomes ?? [],
              oracleGate: signal.oracleGate ?? undefined,
              allowedDecisions: signal.allowedDecisions ?? ['retry', 'ignore', 'fail'],
              openedAt: signal.openedAt ?? new Date().toISOString(),
            })
            // Also broadcast a notification line so SSE listeners can react.
            broadcast(`[gate:open] runId=${signal.runId}`)
            continue
          }
        } catch { /* not a valid gate signal, fall through to broadcast */ }
      }
      broadcast(line)
    }
  })

  let stderrBuf = ''
  child.stderr.on('data', (chunk) => {
    stderrBuf += chunk.toString()
    const parts = stderrBuf.split('\n')
    stderrBuf = parts.pop()
    for (const line of parts) broadcast(`[stderr] ${line}`)
  })

  child.on('close', () => {
    clearInterval(pollInterval)
    if (stdoutBuf) broadcast(stdoutBuf)
    if (stderrBuf) broadcast(`[stderr] ${stderrBuf}`)

    // Clean up any pending gate for this run when the child exits.
    // The workflow's shutdown handler resolves the Promise via rejectAllPendingGates(),
    // but the server's registry must also be cleared so GET /review-gate returns terminal.
    if (runId) unregisterGate(runId)

    const e = entry()
    if (e) {
      for (const res of e.listeners) {
        try { res.write(`data: ${JSON.stringify({ done: true })}\n\n`) } catch {}
        try { res.end() } catch {}
      }
      e.listeners.clear()
      e.child = null
    }
  })

  return { pid: child.pid }
}

// ---------------------------------------------------------------------------
// Proxy AgentOS
// ---------------------------------------------------------------------------

async function fetchAgents(namespaceId) {
  const url = `${AGENTOS_URL}/api/agent-configs/by-parentId/${namespaceId}`
  const headers = {}
  if (RESOLVED_FACTORY_USER) headers['X-External-User-Id'] = RESOLVED_FACTORY_USER
  const res = await fetch(url, { headers })
  if (!res.ok) throw new Error(`AgentOS ${res.status}`)
  return res.json()
}

async function fetchNamespace(namespaceId) {
  const url = `${AGENTOS_URL}/api/namespaces/${encodeURIComponent(namespaceId)}`
  const headers = {}
  if (RESOLVED_FACTORY_USER) headers['X-External-User-Id'] = RESOLVED_FACTORY_USER
  const res = await fetch(url, { headers })
  if (res.status === 404) return null
  if (!res.ok) throw new Error(`AgentOS ${res.status}`)
  return res.json()
}

/**
 * Resolves the Forge run store root from the namespace configPath.
 * runStoreRoot = dirname(configPath) + '/forge/factory-runs'
 *
 * Returns null when the namespace is not found or has no configPath.
 * Throws on AgentOS errors.
 */
async function resolveRepoRoot(namespaceId) {
  const namespace = await fetchNamespace(namespaceId)
  if (!namespace?.configPath) return null
  return dirname(namespace.configPath.replace(/\/+$/, ''))
}

async function resolveRunStoreRoot(namespaceId) {
  const repoRoot = await resolveRepoRoot(namespaceId)
  return repoRoot ? join(repoRoot, 'forge', 'factory-runs') : null
}

/**
 * Récupère les événements d'un case AgentOS.
 *
 * Même endpoint que celui utilisé par `lib/agentos.mjs` pour la détection de fin
 * de tour : `GET /api/case-events/by-parentId/{caseId}`. L'ordre chronologique
 * est garanti par le backend.
 *
 * @param {string} caseId
 * @returns {Promise<object[]>}
 */
async function fetchCaseEvents(caseId) {
  const url = `${AGENTOS_URL}/api/case-events/by-parentId/${caseId}`
  const headers = {}
  if (RESOLVED_FACTORY_USER) headers['X-External-User-Id'] = RESOLVED_FACTORY_USER
  const res = await fetch(url, { headers })
  if (!res.ok) throw new Error(`AgentOS ${res.status}`)
  return res.json()
}

// ---------------------------------------------------------------------------
// Routeur HTTP
// ---------------------------------------------------------------------------

function send(res, status, body, ct = 'application/json') {
  const data = typeof body === 'string' ? body : JSON.stringify(body)
  res.writeHead(status, {
    'Content-Type': ct,
    'Access-Control-Allow-Origin': '*',
  })
  res.end(data)
}

export function isAllowedStoryEditRequestBody(body) {
  return !!body && typeof body === 'object' && !Array.isArray(body) && Object.keys(body).every((key) => ['analysisExecutionId', 'namespaceId', 'agentName', 'expectedSpecHash', 'supplement'].includes(key))
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let data = ''
    req.on('data', (c) => { data += c })
    req.on('end', () => { try { resolve(JSON.parse(data || '{}')) } catch { resolve({}) } })
    req.on('error', reject)
  })
}

const server = createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`)
  const path = url.pathname
  const method = req.method

  if (method === 'OPTIONS') {
    res.writeHead(204, {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET,POST,PUT,DELETE',
      'Access-Control-Allow-Headers': 'Content-Type,X-Factory-Namespace-Id,X-Factory-Case-Id,X-Factory-Actor-Id',
    })
    return res.end()
  }

  if (deliveryController && await handleDeliveryRequest({
    method, path,
    readBody: () => readBody(req),
    send: (status, body) => send(res, status, body),
    controller: deliveryController,
    identity: async () => {
      const namespaceId = req.headers['x-factory-namespace-id']
      const caseId = req.headers['x-factory-case-id']
      const actorId = req.headers['x-factory-actor-id']
      return typeof namespaceId === 'string' && typeof caseId === 'string' ? { namespaceId, caseId, actorId: typeof actorId === 'string' ? actorId : null } : null
    },
    log: console,
  })) return

  if (workUnitEnvironmentController && await handleWorkUnitEnvironmentRequest({
    method, path, url,
    readBody: () => readBody(req),
    send: (status, body) => send(res, status, body),
    controller: workUnitEnvironmentController,
    identity: async () => {
      const namespaceId = req.headers['x-factory-namespace-id']
      const caseId = req.headers['x-factory-case-id']
      const actorId = req.headers['x-factory-actor-id']
      return typeof namespaceId === 'string' && typeof caseId === 'string' ? { namespaceId, caseId, actorId: typeof actorId === 'string' ? actorId : 'factory-ui' } : null
    },
    log: console,
  })) return

  if (await handleWorkflowDefinitionRequest({
    method, path,
    send: (status, body) => send(res, status, body),
    registry: workflowDefinitionRegistry,
    log: console,
  })) return

  if (await handleWorkflowOracleRequest({ method, path, readBody: () => readBody(req), send: (status, body) => send(res, status, body), projectionStore: workflowProjectionStore, evidenceStore: workflowEvidenceStore, definitionRegistry: workflowDefinitionRegistry, oracleRegistry: oracleDefinitionRegistry, repoRoot: FACTORY_ORACLE_REPO_ROOT, log: console })) return

  if (await handleWorkflowCodeTransitionRequest({ method, path, readBody: () => readBody(req), send: (status, body) => send(res, status, body), store: workflowProjectionStore, evidenceStore: workflowEvidenceStore, definitionRegistry: workflowDefinitionRegistry, namespaceId: FACTORY_ORACLE_NAMESPACE_ID, notifier: workflowProjectionSseHub, log: console })) return

  if (await handleWorkflowTransitionRequest({ method, path, readBody: () => readBody(req), send: (status, body) => send(res, status, body), store: workflowProjectionStore, evidenceStore: workflowEvidenceStore, definitionRegistry: workflowDefinitionRegistry, notifier: workflowProjectionSseHub, log: console })) return

  if (await handleWorkflowHumanInteractionRequest({ method, path, url, readBody: () => readBody(req), send: (status, body) => send(res, status, body), projectionStore: workflowProjectionStore, interactionStore: workflowHumanInteractionStore, evidenceStore: workflowEvidenceStore, definitionRegistry: workflowDefinitionRegistry, identity: { actorId: async () => RESOLVED_FACTORY_USER ?? null }, notifier: workflowProjectionSseHub, log: console })) return

  if (await handleWorkflowEvidenceRequest({
    method, path, url,
    readBody: () => readBody(req),
    send: (status, body) => send(res, status, body),
    projectionStore: workflowProjectionStore,
    evidenceStore: workflowEvidenceStore,
    definitionRegistry: workflowDefinitionRegistry,
    log: console,
  })) return

  if (await handleWorkflowOperationalMetricsRequest({
    method, path, url,
    send: (status, body) => send(res, status, body),
    service: workflowOperationalMetricsService,
    clock: { now: () => new Date() },
    log: console,
  })) return

  if (await handleForgeWorkflowProjectionRequest({
    method, path, url,
    readBody: () => readBody(req),
    send: (status, body) => send(res, status, body),
    resolveRepoRoot,
    store: workflowProjectionStore,
    notifier: workflowProjectionSseHub,
    log: console,
  })) return

  // Generic WorkflowProjection API. Kept in a focused module so this legacy
  // dashboard router only owns composition and transport adaptation.
  if (await handleWorkflowProjectionRequest({
    method,
    path,
    url,
    readBody: () => readBody(req),
    send: (status, body) => send(res, status, body),
    store: workflowProjectionStore,
    definitionRegistry: workflowDefinitionRegistry,
    notifier: workflowProjectionSseHub,
    openStream: (namespaceId) => {
      res.writeHead(200, {
        'Content-Type': 'text/event-stream',
        'Cache-Control': 'no-cache',
        'Connection': 'keep-alive',
        'Access-Control-Allow-Origin': '*',
      })
      res.write(': connected\n\n')
      workflowProjectionSseHub.subscribe(
        namespaceId,
        (frame) => res.write(frame),
        (remove) => {
          req.once('close', remove)
          req.once('error', remove)
          res.once('close', remove)
          res.once('error', remove)
        },
      )
    },
    log: console,
  })) return

  // UI
  if (method === 'GET' && (path === '/' || path === '/index.html')) {
    const html = readFileSync(join(__dirname, 'index.html'), 'utf8')
    return send(res, 200, html, 'text/html; charset=utf-8')
  }

  // GET /api/config — expose l'URL AgentOS pour les liens profonds côté client,
  // et la disponibilité Jira pour que l'interface puisse informer l'utilisateur.
  //
  // IMPORTANT : on n'expose JAMAIS les valeurs des credentials, ni même
  // partiellement (pas d'email en clair, pas de token tronqué). Un booléen
  // suffit : l'interface a besoin de savoir si la fonctionnalité est disponible,
  // pas avec quels secrets. Exposer même un fragment d'un secret dans une API
  // HTTP serait contraire à la raison d'être de cette architecture.
  if (method === 'GET' && path === '/api/config') {
    const jiraConfigured = !!(JIRA_BASE_URL && JIRA_EMAIL && JIRA_API_TOKEN)
    return send(res, 200, { agentosUrl: AGENTOS_URL, factoryUser: RESOLVED_FACTORY_USER, jiraConfigured })
  }

  // GET /api/runs
  if (method === 'GET' && path === '/api/runs') {
    return send(res, 200, listRuns())
  }

  // Minimal replay-only Forge view for the existing dashboard and future UI.
  // Legacy workflow JSONL remains served by /api/runs unchanged.
  if (method === 'GET' && (path === '/api/forge/runs' || path === '/api/factory/forge/runs')) {
    const namespaceId = url.searchParams.get('namespaceId')
    if (!namespaceId) return send(res, 400, { error: 'namespaceId query param is required' })
    try {
      const runStoreRoot = await resolveRunStoreRoot(namespaceId)
      if (!runStoreRoot) return send(res, 422, { error: 'Namespace not found or has no configPath configured' })
      return send(res, 200, listForgeRunProjections(runStoreRoot))
    } catch (err) {
      return send(res, 500, { error: String(err) })
    }
  }

  const forgeStoryExecutionsMatch = path.match(/^\/api\/forge\/runs\/([^/]+)\/stories\/([^/]+)\/executions$/)
  if (method === 'GET' && forgeStoryExecutionsMatch) {
    const namespaceId = url.searchParams.get('namespaceId')
    if (!namespaceId) return send(res, 400, { error: 'namespaceId query param is required' })
    try {
      const runStoreRoot = await resolveRunStoreRoot(namespaceId)
      if (!runStoreRoot) return send(res, 422, { error: 'Namespace not found or has no configPath configured' })
      const projection = projectForgeRun(parseForgeLedger(join(runStoreRoot, `${forgeStoryExecutionsMatch[1]}.jsonl`)))
      const story = projection?.stories.find((item) => item.runId === forgeStoryExecutionsMatch[2])
      return story ? send(res, 200, story.executions) : send(res, 404, { error: 'Story run not found.' })
    } catch (err) { return send(res, 404, { error: String(err) }) }
  }
  if (method === 'POST' && forgeStoryExecutionsMatch) {
    const namespaceId = url.searchParams.get('namespaceId')
    if (!namespaceId) return send(res, 400, { error: 'namespaceId query param is required' })
    const body = await readBody(req)
    if (Object.keys(body).some((key) => !['namespaceId', 'agentName', 'expectedSpecHash', 'supplement'].includes(key))) {
      return send(res, 400, { error: 'Unsupported Story analysis request field.' })
    }
    try {
      const runStoreRoot = await resolveRunStoreRoot(namespaceId)
      if (!runStoreRoot) return send(res, 422, { error: 'Namespace not found or has no configPath configured' })
      const events = parseForgeLedger(join(runStoreRoot, `${forgeStoryExecutionsMatch[1]}.jsonl`)); const start = events.find((event) => event.event === 'run_started')
      const result = await executeStoryAnalysis({ roots: start.roots, epicRunId: forgeStoryExecutionsMatch[1], storyRunId: forgeStoryExecutionsMatch[2], namespaceId: body.namespaceId, agentName: body.agentName, supplement: body.supplement, expectedSpecHash: body.expectedSpecHash })
      return send(res, 201, result)
    } catch (error) { return send(res, 409, { error: String(error.message ?? error) }) }
  }

  const forgeStoryOraclesMatch = path.match(/^\/api\/forge\/runs\/([^/]+)\/stories\/([^/]+)\/oracles$/)
  if (method === 'GET' && forgeStoryOraclesMatch) {
    const namespaceId = url.searchParams.get('namespaceId')
    if (!namespaceId) return send(res, 400, { error: 'namespaceId query param is required' })
    try {
      const runStoreRoot = await resolveRunStoreRoot(namespaceId)
      if (!runStoreRoot) return send(res, 422, { error: 'Namespace not found or has no configPath configured' })
      const projection = projectForgeRun(parseForgeLedger(join(runStoreRoot, `${forgeStoryOraclesMatch[1]}.jsonl`)))
      const story = projection?.stories.find(item => item.runId === forgeStoryOraclesMatch[2])
      return story ? send(res, 200, story.oracleCampaigns) : send(res, 404, { error: 'Story run not found.' })
    } catch (err) { return send(res, 404, { error: String(err) }) }
  }
  if (method === 'POST' && forgeStoryOraclesMatch) {
    const namespaceId = url.searchParams.get('namespaceId')
    if (!namespaceId) return send(res, 400, { error: 'namespaceId query param is required' })
    const body = await readBody(req)
    if (!isAllowedStoryOracleRequestBody(body)) return send(res, 400, { error: 'Unsupported Story oracle request field.' })
    try {
      const runStoreRoot = await resolveRunStoreRoot(namespaceId)
      if (!runStoreRoot) return send(res, 422, { error: 'Namespace not found or has no configPath configured' })
      const events = parseForgeLedger(join(runStoreRoot, `${forgeStoryOraclesMatch[1]}.jsonl`))
      const start = events.find(event => event.event === 'run_started')
      return send(res, 201, await executeStoryOracles({ roots: start.roots, epicRunId: forgeStoryOraclesMatch[1], storyRunId: forgeStoryOraclesMatch[2], ...body }))
    } catch (error) { return send(res, 409, { error: String(error.message ?? error) }) }
  }

  const forgeStoryEditsMatch = path.match(/^\/api\/forge\/runs\/([^/]+)\/stories\/([^/]+)\/edits$/)
  if (method === 'GET' && forgeStoryEditsMatch) {
    const namespaceId = url.searchParams.get('namespaceId')
    if (!namespaceId) return send(res, 400, { error: 'namespaceId query param is required' })
    try {
      const runStoreRoot = await resolveRunStoreRoot(namespaceId)
      if (!runStoreRoot) return send(res, 422, { error: 'Namespace not found or has no configPath configured' })
      const projection = projectForgeRun(parseForgeLedger(join(runStoreRoot, `${forgeStoryEditsMatch[1]}.jsonl`)))
      const story = projection?.stories.find(item => item.runId === forgeStoryEditsMatch[2])
      return story ? send(res, 200, story.edits) : send(res, 404, { error: 'Story run not found.' })
    } catch (err) { return send(res, 404, { error: String(err) }) }
  }
  if (method === 'POST' && forgeStoryEditsMatch) {
    const namespaceId = url.searchParams.get('namespaceId')
    if (!namespaceId) return send(res, 400, { error: 'namespaceId query param is required' })
    const body = await readBody(req)
    if (!isAllowedStoryEditRequestBody(body)) return send(res, 400, { error: 'Unsupported Story edit request field.' })
    try {
      const runStoreRoot = await resolveRunStoreRoot(namespaceId)
      if (!runStoreRoot) return send(res, 422, { error: 'Namespace not found or has no configPath configured' })
      const events = parseForgeLedger(join(runStoreRoot, `${forgeStoryEditsMatch[1]}.jsonl`))
      const start = events.find(event => event.event === 'run_started')
      const result = await executeStoryEdit({ roots: start.roots, epicRunId: forgeStoryEditsMatch[1], storyRunId: forgeStoryEditsMatch[2], ...body })
      return send(res, 201, result)
    } catch (error) { return send(res, 409, { error: String(error.message ?? error) }) }
  }

  const forgeG1Match = path.match(/^\/api\/forge\/runs\/([^/]+)\/gates\/G1$/)
  if (method === 'GET' && forgeG1Match) {
    const namespaceId = url.searchParams.get('namespaceId')
    if (!namespaceId) return send(res, 400, { error: 'namespaceId query param is required' })
    try {
      const runStoreRoot = await resolveRunStoreRoot(namespaceId)
      if (!runStoreRoot) return send(res, 422, { error: 'Namespace not found or has no configPath configured' })
      const projection = projectForgeRun(parseForgeLedger(join(runStoreRoot, `${forgeG1Match[1]}.jsonl`)))
      if (!projection) return send(res, 404, { error: 'Forge run not found.' })
      return send(res, 200, projection.gates.find((gate) => gate.gate === 'G1') ?? null)
    } catch (err) { return send(res, 404, { error: String(err) }) }
  }

  const forgeG2Match = path.match(/^\/api\/forge\/runs\/([^/]+)\/gates\/G2$/)
  if (method === 'GET' && forgeG2Match) {
    const namespaceId = url.searchParams.get('namespaceId')
    if (!namespaceId) return send(res, 400, { error: 'namespaceId query param is required' })
    try {
      const runStoreRoot = await resolveRunStoreRoot(namespaceId)
      if (!runStoreRoot) return send(res, 422, { error: 'Namespace not found or has no configPath configured' })
      const projection = projectForgeRun(parseForgeLedger(join(runStoreRoot, `${forgeG2Match[1]}.jsonl`)))
      if (!projection) return send(res, 404, { error: 'Forge run not found.' })
      return send(res, 200, projection.gates.find((gate) => gate.gate === 'G2') ?? { gate: 'G2', status: 'not_evaluated' })
    } catch (err) { return send(res, 404, { error: String(err) }) }
  }

  if (method === 'POST' && forgeG2Match) {
    const namespaceId = url.searchParams.get('namespaceId')
    if (!namespaceId) return send(res, 400, { error: 'namespaceId query param is required' })
    const body = await readBody(req)
    try {
      const runStoreRoot = await resolveRunStoreRoot(namespaceId)
      if (!runStoreRoot) return send(res, 422, { error: 'Namespace not found or has no configPath configured' })
      const events = parseForgeLedger(join(runStoreRoot, `${forgeG2Match[1]}.jsonl`))
      const start = events.find((event) => event.event === 'run_started' && event.runId === forgeG2Match[1])
      if (!start?.roots) return send(res, 409, { error: 'Forge run roots are missing from the ledger.' })
      const result = evaluateG2({ roots: start.roots, runId: forgeG2Match[1], specPath: body.specPath })
      return send(res, result.status === 'recorded' ? 201 : (result.status === 'conflict' ? 409 : 200), result)
    } catch (error) { return send(res, 409, { error: String(error.message ?? error) }) }
  }

  const forgeG1DecisionMatch = path.match(/^\/api\/(?:factory\/)?forge\/runs\/([^/]+)\/gates\/G1\/decision$/)
  if (method === 'POST' && forgeG1DecisionMatch) {
    const namespaceId = url.searchParams.get('namespaceId')
    if (!namespaceId) return send(res, 400, { error: 'namespaceId query param is required' })
    const body = await readBody(req)
    // Minimal injected dashboard adapter. Production authentication replaces this
    // boundary; actor/authority are never accepted from the request body.
    const actorId = req.headers['x-factory-actor-id']
    const authorityId = req.headers['x-factory-authority-id']
    const identityPort = {
      actorId: async () => Array.isArray(actorId) ? actorId[0] : actorId,
      authorize: async ({ actorId: verifiedActor }) =>
        typeof authorityId === 'string' && authorityId && verifiedActor ? { authorityId } : null,
    }
    try {
      const runStoreRoot = await resolveRunStoreRoot(namespaceId)
      if (!runStoreRoot) return send(res, 422, { error: 'Namespace not found or has no configPath configured' })
      const roots = { runStoreRoot }
      const result = await recordHumanDecision({ roots, runId: forgeG1DecisionMatch[1], decision: body, identityPort })
      return send(res, result.status === 'recorded' ? 201 : 200, result)
    } catch (error) { return send(res, 409, { error: String(error.message ?? error) }) }
  }

  // POST /api/runs
  if (method === 'POST' && path === '/api/runs') {
    const body = await readBody(req)
    const result = launchRun(body)
    if (result.error) return send(res, 400, { error: result.error })
    return send(res, 202, result)
  }

  // GET /api/runs/:id
  const detailMatch = path.match(/^\/api\/runs\/([^/]+)$/)
  if (method === 'GET' && detailMatch) {
    const detail = detailRun(detailMatch[1])
    if (!detail) return send(res, 404, { error: 'Run introuvable' })
    return send(res, 200, detail)
  }

  // GET /api/runs/:id/stream  (SSE)
  const streamMatch = path.match(/^\/api\/runs\/([^/]+)\/stream$/)
  if (method === 'GET' && streamMatch) {
    const runId = streamMatch[1]
    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      'Connection': 'keep-alive',
      'Access-Control-Allow-Origin': '*',
    })
    res.write(': connected\n\n')

    const e = activeRuns.get(runId)
    if (!e || !e.child) {
      res.write(`data: ${JSON.stringify({ done: true })}\n\n`)
      return res.end()
    }
    // Rejouer les lignes déjà émises
    for (const line of e.lines) {
      res.write(`data: ${JSON.stringify({ line })}\n\n`)
    }
    e.listeners.add(res)
    req.on('close', () => e.listeners.delete(res))
    return
  }

  // GET /api/cases/:caseId/events — proxy AgentOS.
  const eventsMatch = path.match(/^\/api\/cases\/([^/]+)\/events$/)
  if (method === 'GET' && eventsMatch) {
    try {
      const events = await fetchCaseEvents(eventsMatch[1])
      return send(res, 200, events)
    } catch (err) {
      return send(res, 502, { error: String(err) })
    }
  }

  // GET /api/jira/:ticketId (standalone dashboard) and
  // GET /api/factory/jira/:ticketId (Angular client via /api/factory proxy)
  //
  // The Angular dev-server proxies /api/factory/* to this server (port 3141).
  // /api/jira/* has no matching proxy rule and falls through to the SPA index,
  // so the Angular FactoryApiService uses /api/factory/jira/:ticketId instead.
  // Both paths are handled here; the standalone dashboard uses the short form.
  const jiraMatch = path.match(/^\/api\/(?:factory\/)?jira\/([^/]+)$/)
  if (method === 'GET' && jiraMatch) {
    // Si les credentials Jira ne sont pas configurés dans l'environnement du
    // dashboard, on répond 501 (Not Implemented) avec un message actionnable.
    // 501 plutôt que 403 : ce n'est pas un problème d'autorisation (le serveur
    // ne sait même pas qui est l'utilisateur), c'est une configuration manquante.
    if (!JIRA_BASE_URL || !JIRA_EMAIL || !JIRA_API_TOKEN) {
      const missing = [
        !JIRA_BASE_URL ? 'JIRA_BASE_URL' : null,
        !JIRA_EMAIL ? 'JIRA_EMAIL' : null,
        !JIRA_API_TOKEN ? 'JIRA_API_TOKEN' : null,
      ].filter(Boolean)
      return send(res, 501, {
        error:
          `Le serveur du dashboard n'a pas de credentials Jira configurés ` +
          `(manquant : ${missing.join(', ')}). ` +
          `Relancez-le avec ces variables dans son environnement : ` +
          `JIRA_BASE_URL=https://votre-instance.atlassian.net ` +
          `JIRA_EMAIL=votre@email.com ` +
          `JIRA_API_TOKEN=votre-token ` +
          `node factory/dashboard/server.mjs`,
      })
    }
    try {
      const ticketId = jiraMatch[1]
      const result = await fetchJiraTicket(ticketId, JIRA_BASE_URL, JIRA_EMAIL, JIRA_API_TOKEN)
      return send(res, 200, { ...result, fetchedAt: new Date().toISOString() })
    } catch (err) {
      return send(res, 502, { error: String(err) })
    }
  }

  // ---------------------------------------------------------------------------
  // /api/factory/* — stable aliases for Angular client consumption (slice A1).
  //
  // DESIGN DECISIONS
  // ─────────────────
  // 1. These routes are ALIASES, not replacements. The original /api/runs,
  //    /api/runs/:id, and /api/runs/:id/stream routes are preserved unchanged
  //    for backward compatibility with the standalone dashboard.
  //
  // 2. namespaceId filtering (GET /api/factory/runs?namespaceId=<uuid>):
  //    - A run matches when its run_start record has a namespaceId field that
  //      equals the requested value.
  //    - Historical runs without a namespaceId field DO NOT match any namespace
  //      query. They are not fabricated into any namespace.
  //    - When no namespaceId query param is provided, all runs are returned
  //      (same behavior as GET /api/runs).
  //
  // 3. Secrets, filesystem, process spawning, Jira, and AgentOS identity
  //    remain server-side. The Angular client receives only JSON payloads.
  //
  // 4. Run launch (POST /api/factory/runs) and stream (GET /api/factory/runs/:id/stream)
  //    are aliased here so Angular can use a single base path.
  // ---------------------------------------------------------------------------

  // POST /api/factory/forge/runs/create — create a Forge EpicRun and return { runId, filePath }.
  //
  // Body: { roots: { orchestratorRoot?, repoRoot }, epic: { id, kind }, stories: [...], runId? }
  // The run store root is always derived from repoRoot (forge/factory-runs/) regardless of what
  // the caller passes in roots.runStoreRoot.
  if (method === 'POST' && path === '/api/factory/forge/runs/create') {
    const body = await readBody(req)

    if (!body.roots?.repoRoot) return send(res, 400, { error: 'roots.repoRoot is required' })
    if (!body.epic?.id || !body.epic?.kind) return send(res, 400, { error: 'epic.id and epic.kind are required' })
    if (!Array.isArray(body.stories) || body.stories.length === 0) {
      return send(res, 400, { error: 'stories must be a non-empty array' })
    }

    try {
      const orchestratorRoot = body.roots.orchestratorRoot ?? join(__dirname, '..')
      const repoRoot = body.roots.repoRoot

      const roots = resolveForgeRoots({
        ...body.roots,
        orchestratorRoot,
        runStoreRoot: defaultRunStoreRoot(repoRoot),
        runStorePolicy: REPO_RUN_STORE_POLICY,
      })

      const result = createEpicRun({
        roots,
        epic: body.epic,
        stories: body.stories,
        ...(body.runId ? { runId: body.runId } : {}),
      })

      return send(res, 201, { runId: result.runId, filePath: result.filePath })
    } catch (err) {
      return send(res, 400, { error: String(err.message ?? err) })
    }
  }

  // GET /api/factory/workstreams?namespaceId=<uuid>
  //
  // Resolves the repoRoot from the namespace configPath (AgentOS), then reads
  // forge/bmad/workstreams.toml from that repo.
  // Returns [] when the file does not exist (workspace not yet initialized).
  if (method === 'GET' && path === '/api/factory/workstreams') {
    const namespaceId = url.searchParams.get('namespaceId')
    if (!namespaceId) return send(res, 400, { error: 'namespaceId query param is required' })

    try {
      const namespace = await fetchNamespace(namespaceId)
      if (!namespace) return send(res, 404, { error: 'Namespace not found' })

      const configPath = namespace.configPath
      if (!configPath) return send(res, 422, { error: 'Namespace has no configPath configured' })

      // repoRoot is the parent directory of configPath
      // e.g. configPath = /Users/.../sprint/coday  ->  repoRoot = /Users/.../sprint
      const repoRoot = dirname(configPath.replace(/\/+$/, ''))

      const workstreams = readWorkstreams(repoRoot)
      return send(res, 200, workstreams)
    } catch (err) {
      return send(res, 500, { error: String(err) })
    }
  }

  // GET /api/factory/runs[?namespaceId=<uuid>]
  if (method === 'GET' && path === '/api/factory/runs') {
    const nsFilter = url.searchParams.get('namespaceId')
    const all = listRuns()
    if (!nsFilter) return send(res, 200, all)
    // Only return runs that explicitly carry the requested namespaceId.
    // Runs without the field (historical) are excluded — not fabricated.
    const filtered = all.filter((r) => r.namespaceId === nsFilter)
    return send(res, 200, filtered)
  }

  // POST /api/factory/runs — launches a run and waits to resolve the runId.
  //
  // Unlike POST /api/runs (which returns immediately with only pid), this alias
  // waits up to 3 seconds for the workflow to create its JSONL file and resolves
  // the runId before responding. Angular clients can navigate directly to the new
  // run without a separate polling step.
  //
  // Response: { pid, runId } on success, { error } on validation failure.
  // runId may be null if the file is not created within 3 seconds (edge case).
  if (method === 'POST' && path === '/api/factory/runs') {
    const body = await readBody(req)
    const result = launchRun(body)
    if (result.error) return send(res, 400, { error: result.error })
    const pid = result.pid
    const deadline = Date.now() + 3000
    const runId = await new Promise((resolve) => {
      const check = () => {
        for (const [key, entry] of activeRuns) {
          if (!key.startsWith('pid:') && entry.child?.pid === pid) return resolve(key)
        }
        if (Date.now() >= deadline) return resolve(null)
        setTimeout(check, 200)
      }
      check()
    })
    return send(res, 202, { pid, runId })
  }

  // GET /api/factory/runs/:id
  const factoryDetailMatch = path.match(/^\/api\/factory\/runs\/([^/]+)$/)
  if (method === 'GET' && factoryDetailMatch) {
    const detail = detailRun(factoryDetailMatch[1])
    if (!detail) return send(res, 404, { error: 'Run introuvable' })
    return send(res, 200, detail)
  }

  // POST /api/factory/runs/:id/stop — signal SIGTERM to the tracked child.
  //
  // DESIGN CONTRACT
  // ───────────────
  // - Sends SIGTERM to the child process; does NOT call endRun/write run_end.
  // - The child's own SIGTERM handler (shutdown.mjs) owns active-case cleanup
  //   and registry finalization. Duplicating that here would cause concurrent
  //   run_end writes and corrupt the JSONL.
  // - Idempotent: a second POST while already stopping returns 409.
  // - Returns 202 on a newly requested stop.
  // - Returns 409 if already stopping (guard against races).
  // - Returns 404 if the runId is unknown to this server instance.
  // - Returns 410 if the run has already finished (child is null).
  const factoryStopMatch = path.match(/^\/api\/factory\/runs\/([^/]+)\/stop$/)
  if (method === 'POST' && factoryStopMatch) {
    const runId = factoryStopMatch[1]
    const entry = activeRuns.get(runId)

    if (!entry) {
      // Unknown to this server instance — could be a finished/historical run.
      // Distinguish: if the JSONL exists and has a run_end, it's finished (410).
      // Otherwise genuinely unknown (404).
      const jsonlPath = join(RUNS_DIR, `${runId}.jsonl`)
      if (existsSync(jsonlPath)) {
        const lines = parseJsonl(jsonlPath)
        const hasEnd = lines.some((l) => l.kind === 'run_end')
        if (hasEnd) return send(res, 410, { error: 'Run already finished.' })
      }
      return send(res, 404, { error: 'Run not found.' })
    }

    if (!entry.child) {
      // Child already exited but entry still in map (cleanup pending).
      return send(res, 410, { error: 'Run already finished.' })
    }

    if (entry.stopping) {
      return send(res, 409, { error: 'Stop already requested.' })
    }

    entry.stopping = true
    try {
      entry.child.kill('SIGTERM')
    } catch {
      // Child may have exited between the check and the kill — not an error.
    }
    return send(res, 202, { runId, stopping: true })
  }

  // GET /api/factory/runs/:id/review-gate
  //
  // Returns the current state of the human review gate for a run.
  //
  // Response shapes:
  //
  //   { status: 'pending', findings, outcomes, allowedDecisions }
  //     The gate is open and waiting for a human decision. Angular must show
  //     decision actions. outcomes is an array of structured reviewer results
  //     (no markdown parsing required). allowedDecisions is the authoritative
  //     list of valid decision values.
  //
  //   { status: 'terminal', humanDecision, reason }
  //     The gate is closed (run finished, no gate active, or completed run).
  //     humanDecision is the decision that was made (if any), or null.
  //     reason explains why the gate is terminal. Angular must NOT offer
  //     decision actions. This is the correct response for completed historical
  //     runs — a terminated Node process cannot resume.
  //
  // Completed historical runs with humanDecision:fail in their JSONL:
  //   These runs are permanently terminal. The GET endpoint returns
  //   { status:'terminal', humanDecision:'fail', reason:'Run completed. ...' }.
  //   No fake resumption is attempted. Only future pending gates (from live
  //   workflow processes) can receive decisions.
  const factoryGateMatch = path.match(/^\/api\/factory\/runs\/([^/]+)\/review-gate$/)
  if (method === 'GET' && factoryGateMatch) {
    const runId = factoryGateMatch[1]

    // Check live in-process registry first.
    const pendingGate = getGate(runId)
    if (pendingGate) {
      const pendingResp = {
        status: 'pending',
        gateType: pendingGate.gateType ?? 'adversarial-review',
        findings: pendingGate.findings,
        outcomes: pendingGate.outcomes,
        allowedDecisions: pendingGate.allowedDecisions,
        openedAt: pendingGate.openedAt,
      }
      if (pendingGate.oracleGate) pendingResp.oracleGate = pendingGate.oracleGate
      return send(res, 200, pendingResp)
    }

    // No live gate. Check JSONL for completed run with a humanDecision.
    const jsonlPath = join(RUNS_DIR, `${runId}.jsonl`)
    if (existsSync(jsonlPath)) {
      const lines = parseJsonl(jsonlPath)
      const runEnd = lines.find((l) => l.kind === 'run_end')

      // Look for humanDecision in any phase_end facts (adversarial-review phase).
      let humanDecision = null
      for (const l of lines) {
        if (l.kind === 'phase_end' && l.facts?.humanDecision) {
          humanDecision = l.facts.humanDecision
          break
        }
      }

      if (runEnd) {
        // Run is completed. Terminal state — no actions possible.
        return send(res, 200, {
          status: 'terminal',
          humanDecision,
          reason: 'Run completed. A terminated process cannot resume. Only future pending gates can receive decisions.',
        })
      }

      // Run exists but has no run_end (crashed or still running but gate not open).
      return send(res, 200, {
        status: 'terminal',
        humanDecision: null,
        reason: 'No active review gate for this run.',
      })
    }

    // Unknown run.
    return send(res, 404, { error: 'Run not found.' })
  }

  // POST /api/factory/runs/:id/review-gate/reply
  //
  // Deliver a human decision to the pending review gate.
  //
  // Body: { decision: 'retry'|'ignore'|'fail', message?: string }
  //
  // 'ignore' : the run continues as PASS despite the review FAIL
  // 'fail'   : the run fails (confirms the review FAIL)
  // 'retry'  : re-run the editor with the review findings as brief
  //
  // Returns 404 if no pending gate exists for this runId.
  // The workflow polls factory/runs/<runId>.gate-reply for this file.
  const factoryGateReplyMatch = path.match(/^\/api\/factory\/runs\/([^/]+)\/review-gate\/reply$/)
  if (method === 'POST' && factoryGateReplyMatch) {
    const runId = factoryGateReplyMatch[1]

    // Validate that a live gate exists for this run.
    // We allow writing the reply even if the in-process registry was just cleared
    // (race between SIGTERM and the POST) — the workflow's poll will pick it up
    // if the process is still alive, or ignore it if not.
    const body = await readBody(req)
    const rawDecision = body.decision ?? 'fail'
    // Accept both adversarial-review decisions (ignore/retry/fail) and oracle gate decisions (continue/fail).
    const decision = ['ignore', 'retry', 'continue', 'fail'].includes(rawDecision) ? rawDecision : 'fail'
    const message = (body.message ?? '').trim()

    const result = writeGateReply(runId, decision, message)
    if (!result.ok) {
      return send(res, 500, { error: result.error })
    }
    return send(res, 200, { ok: true, decision })
  }

  // GET /api/factory/runs/:id/stream — SSE alias
  const factoryStreamMatch = path.match(/^\/api\/factory\/runs\/([^/]+)\/stream$/)
  if (method === 'GET' && factoryStreamMatch) {
    const runId = factoryStreamMatch[1]
    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      'Connection': 'keep-alive',
      'Access-Control-Allow-Origin': '*',
    })
    res.write(': connected\n\n')

    const e = activeRuns.get(runId)
    if (!e || !e.child) {
      res.write(`data: ${JSON.stringify({ done: true })}\n\n`)
      return res.end()
    }
    for (const line of e.lines) {
      res.write(`data: ${JSON.stringify({ line })}\n\n`)
    }
    e.listeners.add(res)
    req.on('close', () => e.listeners.delete(res))
    return
  }

  // GET /api/agents?namespaceId=
  if (method === 'GET' && path === '/api/agents') {
    const namespaceId = url.searchParams.get('namespaceId')
    if (!namespaceId) return send(res, 400, { error: 'namespaceId requis' })
    try {
      const agents = await fetchAgents(namespaceId)
      return send(res, 200, agents)
    } catch (err) {
      return send(res, 502, { error: String(err) })
    }
  }

  // GET /api/review-gate — DEPRECATED global endpoint.
  //
  // The global singleton gate is replaced by run-scoped gates.
  // Use GET /api/factory/runs/:runId/review-gate instead.
  // This stub returns 410 Gone with a migration message.
  if (method === 'GET' && path === '/api/review-gate') {
    return send(res, 410, {
      error: 'DEPRECATED: global /api/review-gate removed. Use GET /api/factory/runs/:runId/review-gate instead.',
    })
  }

  // POST /api/review-gate/reply — DEPRECATED global endpoint.
  //
  // Use POST /api/factory/runs/:runId/review-gate/reply instead.
  if (method === 'POST' && path === '/api/review-gate/reply') {
    return send(res, 410, {
      error: 'DEPRECATED: global /api/review-gate/reply removed. Use POST /api/factory/runs/:runId/review-gate/reply instead.',
    })
  }

  send(res, 404, { error: 'Not found' })
})

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  await workflowProjectionStore.initialize()
  await workflowDefinitionRegistry.initialize()
  if (workUnitEnvironmentController) await workUnitEnvironmentController.initialize()
  if (deliveryController) await deliveryController.initialize()
  if (oracleDefinitionRegistry) await oracleDefinitionRegistry.initialize()
  server.listen(PORT, FACTORY_BIND_POLICY.host, () => {
    console.log(`Factory dashboard → http://${FACTORY_BIND_POLICY.host}:${PORT}`)
    console.log(`Factory bind mode  : ${FACTORY_BIND_POLICY.trustMode}${FACTORY_BIND_POLICY.trustMode.startsWith('unsafe') ? ' (explicit unsafe opt-in; routes are unauthenticated)' : ''}`)
    console.log(`AgentOS           : ${AGENTOS_URL}`)
    console.log(`Factory data root : ${FACTORY_DATA_ROOT}`)
    // Report Coday config discovery diagnostics (no secrets)
    for (const msg of _codayDiscovery.diagnostics) console.log(`Coday config      : ${msg}`)
    if (JIRA_BASE_URL) {
      const src = process.env.JIRA_BASE_URL ? 'env' : 'user.yaml Coday'
      console.log(`Jira              : ${JIRA_BASE_URL} (${src})`)
    } else {
      console.log(`Jira              : non configuré (tickets Jira indisponibles)`)
    }
  })
}

export { parseJsonl, reconstructPhases }
