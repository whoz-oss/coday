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
import { readFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { fetchJiraTicket } from '../lib/jira.mjs'
import { discoverJiraCredentials } from '../lib/coday-config.mjs'
import { registerGate, unregisterGate, getGate, writeGateReply, validateGateSignal } from '../lib/review-gate.mjs'
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
import { DeliveryOperationController } from '../lib/delivery-operation-controller.mjs'
import { DeliveryTargetRegistry } from '../lib/delivery-target-registry.mjs'
import { handleDeliveryOperationRequest } from './delivery-operation-routes.mjs'
import { FactoryOperationalMetricsService } from '../lib/factory-operational-metrics-service.mjs'
import { handleWorkflowOperationalMetricsRequest } from './workflow-operational-metrics-routes.mjs'
import { WorkflowResumeDispatchStore } from '../lib/workflow-resume-dispatch-store.mjs'
// New route modules
import { send, readBody } from './http-utils.mjs'
import { createAgentOsProxy } from './agentos-proxy.mjs'
import { handleActiveRunRequest } from './active-run-routes.mjs'
import { handleWorkstreamRequest } from './workstream-routes.mjs'
import { handleForgeRunRequest } from './forge-routes.mjs'
import { createRunRouter, parseJsonl, reconstructPhases } from './run-routes.mjs'

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
const workflowResumeDispatchStore = new WorkflowResumeDispatchStore(FACTORY_DATA_ROOT)
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
// Lot 2 intentionally has no configured deployment provider or target registry.
// These fail closed with 503 until trusted server-side composition is added.
const deliveryOperationController = deliveryController ? new DeliveryOperationController({
  deliveryController,
  store: deliveryStore,
  targetRegistry: new DeliveryTargetRegistry(),
}) : null
// Oracle definitions are supplied by the trusted composition root. The repository
// currently publishes no production oracle; source tests inject their fixture registry.
const FACTORY_ORACLE_DEFINITIONS_ROOT = process.env.FACTORY_ORACLE_DEFINITIONS_ROOT
const oracleDefinitionRegistry = FACTORY_ORACLE_DEFINITIONS_ROOT ? new OracleDefinitionRegistry(FACTORY_ORACLE_DEFINITIONS_ROOT) : null
// Trusted control-plane configuration: never inferred from cwd or namespace configPath.
const FACTORY_ORACLE_REPO_ROOT = process.env.FACTORY_ORACLE_REPO_ROOT
const FACTORY_ORACLE_NAMESPACE_ID = process.env.FACTORY_ORACLE_NAMESPACE_ID
// Explicit store location for Forge Epic/Story projections. It is intentionally
// independent from both this dashboard's source tree and the target repoRoot.
// FACTORY_USER: used only to identify the AgentOS user for proxy headers.
// No hardcoded personal username — resolved from Coday config or left undefined.
const FACTORY_USER = process.env.FACTORY_USER
const CODAY_EXPRESS_URL = process.env.CODAY_EXPRESS_URL
const CODAY_EXPRESS_PROJECT = process.env.CODAY_EXPRESS_PROJECT
const CODAY_CONTROL_PLANE_TOKEN = process.env.CODAY_CONTROL_PLANE_TOKEN
const RESUME_MESSAGE = 'Reload the authoritative workflow and continue only from the next ready step.'

async function dispatchExpressWorkflowResume({namespaceId,storageId,workflowId,interactionId,revision,snapshot}){
  const controller=snapshot?.instance?.controllerExecution??snapshot?.controllerExecution
  if(controller?.kind!=='coday-express')return{status:'not-applicable'}
  if(!CODAY_EXPRESS_URL||!CODAY_EXPRESS_PROJECT||!CODAY_CONTROL_PLANE_TOKEN)return{status:'not-configured'}
  if(typeof controller.runtimeId!=='string'||typeof controller.threadId!=='string'||typeof controller.agentId!=='string')return{status:'identity-invalid'}
  const dispatchId=`human:${interactionId}:revision:${revision}`
  const reserved=await workflowResumeDispatchStore.reserve(namespaceId,storageId,{dispatchId,workflowId,interactionId,revision,controllerExecution:{kind:controller.kind,runtimeId:controller.runtimeId,threadId:controller.threadId,agentId:controller.agentId}})
  if(!reserved.ok)return{status:'indeterminate'}
  if(reserved.delivered)return{status:'delivered'}
  const url=new URL(`/api/projects/${encodeURIComponent(CODAY_EXPRESS_PROJECT)}/threads/${encodeURIComponent(controller.threadId)}/control-plane-resume`,CODAY_EXPRESS_URL)
  const response=await fetch(url,{method:'POST',headers:{'content-type':'application/json','x-coday-control-plane-token':CODAY_CONTROL_PLANE_TOKEN,'x-coday-runtime-id':controller.runtimeId,'x-coday-agent-id':controller.agentId},body:JSON.stringify({message:RESUME_MESSAGE,workflowId,revision})})
  if(!response.ok)return{status:'failed'}
  await workflowResumeDispatchStore.delivered(namespaceId,storageId,dispatchId)
  return{status:'delivered'}
}

// ---------------------------------------------------------------------------
// Jira credentials — env vars take priority; Coday user.yaml is the fallback.
// ---------------------------------------------------------------------------
const _codayDiscovery = discoverJiraCredentials(FACTORY_USER)
const _codayJira = _codayDiscovery.credentials
const JIRA_BASE_URL = process.env.JIRA_BASE_URL ?? _codayJira?.apiUrl ?? null
const JIRA_EMAIL = process.env.JIRA_EMAIL ?? _codayJira?.jiraUsername ?? null
const JIRA_API_TOKEN = process.env.JIRA_API_TOKEN ?? _codayJira?.apiKey ?? null

// Resolved Coday username for AgentOS proxy headers (may be undefined)
const RESOLVED_FACTORY_USER = FACTORY_USER ?? _codayJira?.codayUsername ?? undefined

// ---------------------------------------------------------------------------
// Route module instances (created after all constants are resolved)
// ---------------------------------------------------------------------------

const proxy = createAgentOsProxy({ agentosUrl: AGENTOS_URL, resolvedFactoryUser: RESOLVED_FACTORY_USER })

const runRouter = createRunRouter({
  runsDir: RUNS_DIR,
  runEntry: RUN_ENTRY,
  agentosUrl: AGENTOS_URL,
  resolvedFactoryUser: RESOLVED_FACTORY_USER,
  jiraBaseUrl: JIRA_BASE_URL,
  jiraEmail: JIRA_EMAIL,
  jiraApiToken: JIRA_API_TOKEN,
  registerGate,
  unregisterGate,
  getGate,
  writeGateReply,
  validateGateSignal,
  log: console,
})

const forgeDeps = {
  forgeLedger: { listForgeRunProjections, parseForgeLedger, projectForgeRun, createEpicRun },
  forgeG2: { evaluateG2 },
  forgeStoryAnalysis: { executeStoryAnalysis },
  forgeStoryEdit: { executeStoryEdit },
  forgeStoryOracles: { executeStoryOracles, isAllowedStoryOracleRequestBody },
  forgeHumanDecision: { recordHumanDecision },
  forgeRoots: { resolveForgeRoots, defaultRunStoreRoot, REPO_RUN_STORE_POLICY },
  orchestratorDir: join(__dirname, '..'),
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

  // Shared adapters for all route modules
  const sendFn = (status, body) => send(res, status, body)
  const readBodyFn = () => readBody(req)

  // Identity helpers (trusted because dashboard binds to loopback by default)
  const deliveryIdentity = async () => {
    const namespaceId = req.headers['x-factory-namespace-id']
    const caseId = req.headers['x-factory-case-id']
    const actorId = req.headers['x-factory-actor-id']
    return typeof namespaceId === 'string' && typeof caseId === 'string' ? { namespaceId, caseId, actorId: typeof actorId === 'string' ? actorId : null, resolvedActorId: RESOLVED_FACTORY_USER ?? undefined } : null
  }
  if (deliveryOperationController && await handleDeliveryOperationRequest({ method, path, readBody: readBodyFn, send: sendFn, controller: deliveryOperationController, identity: deliveryIdentity, log: console })) return

  if (deliveryController && await handleDeliveryRequest({ method, path, readBody: readBodyFn, send: sendFn, controller: deliveryController, identity: deliveryIdentity, log: console })) return

  if (workUnitEnvironmentController && await handleWorkUnitEnvironmentRequest({
    method, path, url,
    readBody: readBodyFn,
    send: sendFn,
    controller: workUnitEnvironmentController,
    identity: async () => {
      const namespaceId = req.headers['x-factory-namespace-id']
      const caseId = req.headers['x-factory-case-id']
      const actorId = req.headers['x-factory-actor-id']
      return typeof namespaceId === 'string' && typeof caseId === 'string' ? { namespaceId, caseId, actorId: typeof actorId === 'string' ? actorId : 'factory-ui' } : null
    },
    log: console,
  })) return

  if (await handleWorkflowDefinitionRequest({ method, path, send: sendFn, registry: workflowDefinitionRegistry, log: console })) return

  if (await handleWorkflowOracleRequest({ method, path, readBody: readBodyFn, send: sendFn, projectionStore: workflowProjectionStore, evidenceStore: workflowEvidenceStore, definitionRegistry: workflowDefinitionRegistry, oracleRegistry: oracleDefinitionRegistry, repoRoot: FACTORY_ORACLE_REPO_ROOT, log: console })) return

  if (await handleWorkflowCodeTransitionRequest({ method, path, readBody: readBodyFn, send: sendFn, store: workflowProjectionStore, evidenceStore: workflowEvidenceStore, definitionRegistry: workflowDefinitionRegistry, namespaceId: FACTORY_ORACLE_NAMESPACE_ID, notifier: workflowProjectionSseHub, log: console })) return

  if (await handleWorkflowTransitionRequest({ method, path, readBody: readBodyFn, send: sendFn, store: workflowProjectionStore, evidenceStore: workflowEvidenceStore, definitionRegistry: workflowDefinitionRegistry, notifier: workflowProjectionSseHub, log: console })) return

  if (await handleWorkflowHumanInteractionRequest({ method, path, url, readBody: readBodyFn, send: sendFn, projectionStore: workflowProjectionStore, interactionStore: workflowHumanInteractionStore, evidenceStore: workflowEvidenceStore, definitionRegistry: workflowDefinitionRegistry, identity: { actorId: async () => RESOLVED_FACTORY_USER ?? null }, controllerIdentity: async () => { const namespaceId=req.headers['x-factory-namespace-id'],runtimeId=req.headers['x-factory-runtime-id'],agentId=req.headers['x-factory-agent-id'],caseId=req.headers['x-factory-case-id'],threadId=req.headers['x-factory-thread-id'];return typeof namespaceId==='string'&&typeof runtimeId==='string'&&typeof agentId==='string'&&(typeof caseId==='string'||typeof threadId==='string')?{namespaceId,runtimeId,agentId,kind:typeof caseId==='string'?'agentos':'coday-express',...(typeof caseId==='string'?{caseId}:{threadId})}:null }, resumeDispatcher: dispatchExpressWorkflowResume, notifier: workflowProjectionSseHub, log: console })) return

  if (await handleWorkflowEvidenceRequest({ method, path, url, readBody: readBodyFn, send: sendFn, projectionStore: workflowProjectionStore, evidenceStore: workflowEvidenceStore, definitionRegistry: workflowDefinitionRegistry, log: console })) return

  if (await handleWorkflowOperationalMetricsRequest({ method, path, url, send: sendFn, service: workflowOperationalMetricsService, clock: { now: () => new Date() }, log: console })) return

  if (await handleForgeWorkflowProjectionRequest({ method, path, url, readBody: readBodyFn, send: sendFn, resolveRepoRoot: proxy.resolveRepoRoot, store: workflowProjectionStore, notifier: workflowProjectionSseHub, log: console })) return

  if (await handleWorkflowProjectionRequest({
    method, path, url,
    readBody: readBodyFn,
    send: sendFn,
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
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Access-Control-Allow-Origin': '*' })
    return res.end(html)
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
    return sendFn(200, { agentosUrl: AGENTOS_URL, factoryUser: RESOLVED_FACTORY_USER, jiraConfigured })
  }

  // Forge routes
  if (path.match(/^\/api\/(?:factory\/)?forge\/runs\/[^/]+\/gates\/G1\/decision$/) && method === 'POST') {
    const actorId = req.headers['x-factory-actor-id']
    const authorityId = req.headers['x-factory-authority-id']
    const readBodyWithIdentity = async () => {
      const body = await readBodyFn()
      return { ...body, _actorId: actorId, _authorityId: authorityId }
    }
    if (await handleForgeRunRequest({ method, path, url, readBody: readBodyWithIdentity, send: sendFn, proxy, log: console, ...forgeDeps })) return
  }

  if (await handleForgeRunRequest({ method, path, url, readBody: readBodyFn, send: sendFn, proxy, log: console, ...forgeDeps })) return

  // Active-run routes
  if (await handleActiveRunRequest({ method, path, url, readBody: readBodyFn, send: sendFn, proxy, log: console })) return

  // Workstream routes
  if (await handleWorkstreamRequest({ method, path, url, readBody: readBodyFn, send: sendFn, proxy, log: console })) return

  // Run routes (legacy JSONL runs + review gates)
  if (await runRouter.handleRequest({ method, path, url, readBody: readBodyFn, send: sendFn, req, res })) return

  // AgentOS proxy pass-through
  if (method === 'GET' && path === '/api/agents') {
    const namespaceId = url.searchParams.get('namespaceId')
    if (!namespaceId) return sendFn(400, { error: 'namespaceId requis' })
    try { return sendFn(200, await proxy.fetchAgents(namespaceId)) }
    catch (err) { return sendFn(502, { error: String(err) }) }
  }

  const eventsMatch = path.match(/^\/api\/cases\/([^/]+)\/events$/)
  if (method === 'GET' && eventsMatch) {
    try { return sendFn(200, await proxy.fetchCaseEvents(eventsMatch[1])) }
    catch (err) { return sendFn(502, { error: String(err) }) }
  }

  // Jira proxy
  const jiraMatch = path.match(/^\/api\/(?:factory\/)?jira\/([^/]+)$/)
  if (method === 'GET' && jiraMatch) {
    if (!JIRA_BASE_URL || !JIRA_EMAIL || !JIRA_API_TOKEN) {
      const missing = [!JIRA_BASE_URL ? 'JIRA_BASE_URL' : null, !JIRA_EMAIL ? 'JIRA_EMAIL' : null, !JIRA_API_TOKEN ? 'JIRA_API_TOKEN' : null].filter(Boolean)
      return sendFn(501, { error: `Le serveur du dashboard n'a pas de credentials Jira configurés (manquant : ${missing.join(', ')}). Relancez-le avec ces variables dans son environnement : JIRA_BASE_URL=https://votre-instance.atlassian.net JIRA_EMAIL=votre@email.com JIRA_API_TOKEN=votre-token node factory/dashboard/server.mjs` })
    }
    try { return sendFn(200, { ...await fetchJiraTicket(jiraMatch[1], JIRA_BASE_URL, JIRA_EMAIL, JIRA_API_TOKEN), fetchedAt: new Date().toISOString() }) }
    catch (err) { return sendFn(502, { error: String(err) }) }
  }

  sendFn(404, { error: 'Not found' })
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
