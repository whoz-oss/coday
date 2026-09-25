/**
 * Factory dashboard Composition Root.
 *
 * This is the only place where the dashboard's object graph is assembled. The
 * lifecycle is explicit and ordered so each step depends only on the previous:
 *
 *   loadConfig(env)                      → validated, immutable configuration
 *   createStores(config)                 → one instance of every persistence store
 *   createAdapters(config, stores)       → external systems & registries
 *   createApplication(config, stores, adapters) → controllers/services (use cases)
 *   createHttpServer(application, config)        → transport: routing + TrustContext
 *
 * Consequences enforced here:
 *   - Stores are instantiated exactly once, in `createStores`. No route module,
 *     handler, or application file calls `new *Store()`.
 *   - Routes are transport-only: they parse the request, call a controller or
 *     service with the resolved TrustContext, and format the response.
 *   - The HTTP boundary resolves correlation ids and TrustContext once, then
 *     hands them to every route module.
 *
 * `createCompositionRoot(env)` never starts the server; `start()` does. Importing
 * this module (directly or through `server.mjs`) is therefore side-effect free,
 * which is what keeps the offline factory tests importable.
 */

import { createServer } from 'node:http'
import { readFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

// Domain / lib — untouched by this migration, only wired.
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
import { WorkflowDefinitionRegistry } from '../lib/workflow-definition-registry.mjs'
import { WorkflowEvidenceStore } from '../lib/workflow-evidence-store.mjs'
import { OracleDefinitionRegistry } from '../lib/oracle-definition.mjs'
import { WorkflowHumanInteractionStore } from '../lib/workflow-human-interaction-store.mjs'
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
import { FactoryOperationalMetricsService } from '../lib/factory-operational-metrics-service.mjs'
import { WorkflowResumeDispatchStore } from '../lib/workflow-resume-dispatch-store.mjs'
import { createFactoryFrontendRunner } from '../lib/factory-frontend-composition.mjs'
import { AgentStepResultStore } from '../lib/agent-step-result-store.mjs'

// Transport — route modules and their shared utilities.
import { send, readBody, sendError, extractTrustContext, resolveCorrelationId } from './http-utils.mjs'
import { DEFAULT_FAKE_IDP_SECRET, LocalDevMembershipResolver } from '../src/domain/identity/index.ts'
import { createAgentOsProxy } from './agentos-proxy.mjs'
import { handleWorkflowProjectionRequest } from './workflow-projection-routes.mjs'
import { WorkflowProjectionSseHub } from './workflow-projection-sse.mjs'
import { handleForgeWorkflowProjectionRequest } from './forge-workflow-projection-routes.mjs'
import { handleWorkflowDefinitionRequest } from './workflow-definition-routes.mjs'
import { handleWorkflowEvidenceRequest } from './workflow-evidence-routes.mjs'
import { handleWorkflowTransitionRequest } from './workflow-transition-routes.mjs'
import { handleWorkflowOracleRequest } from './workflow-oracle-routes.mjs'
import { handleWorkflowCodeTransitionRequest } from './workflow-code-transition-routes.mjs'
import { handleWorkflowHumanInteractionRequest } from './workflow-human-interaction-routes.mjs'
import { handleDeliveryOperationRequest } from './delivery-operation-routes.mjs'
import { handleWorkflowOperationalMetricsRequest } from './workflow-operational-metrics-routes.mjs'
import { handleFactoryFrontendRunRequest } from './factory-frontend-run-routes.mjs'
import { handleAgentStepResultRequest } from './agent-step-result-routes.mjs'
import { handleActiveRunRequest } from './active-run-routes.mjs'
import { handleWorkstreamRequest } from './workstream-routes.mjs'
import { handleForgeRunRequest } from './forge-routes.mjs'
import { createRunRouter } from './run-routes.mjs'

const __dirname = dirname(fileURLToPath(import.meta.url))
const DASHBOARD_DIR = __dirname
const FACTORY_DIR = join(__dirname, '..')
const LOOPBACK_HOSTS = new Set(['127.0.0.1', '::1', 'localhost'])

/**
 * Local unauthenticated trust-boundary policy.
 *
 * The dashboard is unauthenticated, so it may only bind to loopback unless the
 * operator explicitly opts into exposing it. Remote bind without the flag is a
 * configuration error, not a warning.
 *
 * @param {Record<string, string|undefined>} [env]
 * @returns {{ host: string, trustMode: 'loopback-only'|'unsafe-remote-unauthenticated' }}
 */
export function resolveFactoryBindPolicy(env = process.env) {
  const host = env.FACTORY_BIND_HOST ?? '127.0.0.1'
  const unsafeRemote = env.FACTORY_UNSAFE_ALLOW_REMOTE_BIND === 'true'
  if (!LOOPBACK_HOSTS.has(host) && !unsafeRemote) {
    throw new Error('FACTORY_BIND_HOST must be loopback unless FACTORY_UNSAFE_ALLOW_REMOTE_BIND=true is explicitly set.')
  }
  return { host, trustMode: LOOPBACK_HOSTS.has(host) ? 'loopback-only' : 'unsafe-remote-unauthenticated' }
}

/**
 * Build the identity primitives used once at the HTTP boundary: the
 * server-side membership resolver and the Fake IdP shared secret.
 *
 * The secret is dev-only by default; a deployment is expected to provide
 * `FACTORY_FAKE_IDP_SECRET` out of band. The resolver is the only place
 * memberships (`organizationId`, `workstreamId`, `squadId`, `roles`) may come
 * from — never from client headers.
 *
 * @param {Record<string, string|undefined>} [env]
 * @returns {{ membershipResolver: LocalDevMembershipResolver, fakeIdpSecret: string }}
 */
export function createIdentityBoundaryOptions(env = process.env) {
  return {
    membershipResolver: new LocalDevMembershipResolver(),
    fakeIdpSecret: env.FACTORY_FAKE_IDP_SECRET ?? env.FACTORY_IDP_SECRET ?? DEFAULT_FAKE_IDP_SECRET,
  }
}

/**
 * Attach the identity options to the bind policy non-enumerably.
 *
 * `extractTrustContext(req, config.bindPolicy)` reads them, while the bind
 * policy stays deep-equal to `{ host, trustMode }` for every existing caller.
 */
function withIdentityBoundaryOptions(bindPolicy, identityOptions) {
  const enriched = { ...bindPolicy }
  Object.defineProperty(enriched, 'identity', {
    value: identityOptions,
    enumerable: false,
    writable: false,
    configurable: true,
  })
  return enriched
}

// ---------------------------------------------------------------------------
// 1. loadConfig — read + validate the environment once.
// ---------------------------------------------------------------------------

/**
 * @param {Record<string, string|undefined>} [env]
 * @returns {object} frozen configuration consumed by every later step
 */
export function loadConfig(env = process.env) {
  const bindPolicy = withIdentityBoundaryOptions(resolveFactoryBindPolicy(env), createIdentityBoundaryOptions(env))

  // Jira credentials — env vars take priority; Coday user.yaml is the fallback.
  // Discovery diagnostics are reported at startup, never any secret value.
  const factoryUser = env.FACTORY_USER
  const codayDiscovery = discoverJiraCredentials(factoryUser)
  const codayJira = codayDiscovery.credentials

  const deliveryAllowedPaths = (env.FACTORY_DELIVERY_ALLOWED_PATHS ?? '').split(',').map((value) => value.trim()).filter(Boolean)
  const deliveryProtectedPaths = (env.FACTORY_DELIVERY_PROTECTED_PATHS ?? '.git,.coday').split(',').map((value) => value.trim()).filter(Boolean)

  return {
    port: parseInt(env.PORT ?? '3141', 10),
    bindPolicy,
    dashboardDir: DASHBOARD_DIR,
    factoryDir: FACTORY_DIR,
    runsDir: join(FACTORY_DIR, 'runs'),
    runEntry: join(FACTORY_DIR, 'run.mjs'),
    workflowsDir: join(FACTORY_DIR, 'workflows'),
    oraclesDir: env.FACTORY_ORACLE_DEFINITIONS_ROOT ?? join(FACTORY_DIR, 'oracles'),
    agentosUrl: env.AGENTOS_URL ?? 'http://localhost:8124',
    // Generic workflow data is independent from cwd, repo roots, namespace
    // configuration and target checkouts.
    factoryDataRoot: env.FACTORY_DATA_ROOT ?? join(homedir(), '.coday', 'factory'),
    repoRoot: env.FACTORY_REPO_ROOT,
    worktreesRoot: env.FACTORY_WORKTREES_ROOT,
    factoryUser,
    jira: {
      baseUrl: env.JIRA_BASE_URL ?? codayJira?.apiUrl ?? null,
      email: env.JIRA_EMAIL ?? codayJira?.jiraUsername ?? null,
      apiToken: env.JIRA_API_TOKEN ?? codayJira?.apiKey ?? null,
      codayUsername: codayJira?.codayUsername ?? undefined,
      diagnostics: codayDiscovery.diagnostics,
    },
    codayExpress: {
      url: env.CODAY_EXPRESS_URL,
      project: env.CODAY_EXPRESS_PROJECT,
      token: env.CODAY_CONTROL_PLANE_TOKEN,
      resumeMessage: 'Reload the authoritative workflow and continue only from the next ready step.',
    },
    delivery: {
      remote: env.FACTORY_DELIVERY_GIT_REMOTE ?? null,
      allowedPaths: deliveryAllowedPaths,
      protectedPaths: deliveryProtectedPaths,
      serviceIdentity: {
        name: env.FACTORY_GIT_COMMITTER_NAME ?? 'Coday Factory',
        email: env.FACTORY_GIT_COMMITTER_EMAIL ?? 'factory@localhost',
      },
      github: {
        owner: env.FACTORY_GITHUB_OWNER,
        repo: env.FACTORY_GITHUB_REPO,
        baseBranch: env.FACTORY_DELIVERY_BASE_BRANCH,
      },
    },
    oracle: {
      repoRoot: env.FACTORY_ORACLE_REPO_ROOT,
      namespaceId: env.FACTORY_ORACLE_NAMESPACE_ID,
    },
  }
}

/** Coday username used for AgentOS proxy headers (may be undefined). */
export function resolveFactoryUser(config) {
  return config.factoryUser ?? config.jira?.codayUsername ?? undefined
}

// ---------------------------------------------------------------------------
// 2. createStores — single instantiation of every persistence store.
// ---------------------------------------------------------------------------

/**
 * @param {ReturnType<typeof loadConfig>} config
 * @returns {{ workflowProjectionStore: WorkflowProjectionStore, workflowEvidenceStore: WorkflowEvidenceStore, agentStepResultStore: AgentStepResultStore, workflowHumanInteractionStore: WorkflowHumanInteractionStore, workflowResumeDispatchStore: WorkflowResumeDispatchStore, workUnitEnvironmentStore: WorkUnitEnvironmentStore, deliveryStore: DeliveryStore, deliveryEvidenceStore: DeliveryEvidenceStore }}
 */
export function createStores(config) {
  return {
    workflowProjectionStore: new WorkflowProjectionStore(config.factoryDataRoot),
    workflowEvidenceStore: new WorkflowEvidenceStore(config.factoryDataRoot),
    agentStepResultStore: new AgentStepResultStore(config.factoryDataRoot),
    workflowHumanInteractionStore: new WorkflowHumanInteractionStore(config.factoryDataRoot),
    workflowResumeDispatchStore: new WorkflowResumeDispatchStore(config.factoryDataRoot),
    workUnitEnvironmentStore: new WorkUnitEnvironmentStore(config.factoryDataRoot),
    deliveryStore: new DeliveryStore(config.factoryDataRoot),
    deliveryEvidenceStore: new DeliveryEvidenceStore(config.factoryDataRoot),
  }
}

// ---------------------------------------------------------------------------
// 3. createAdapters — external systems, registries and process boundaries.
// ---------------------------------------------------------------------------

/**
 * @param {ReturnType<typeof loadConfig>} config
 * @param {ReturnType<typeof createStores>} stores
 * @returns {object}
 */
export function createAdapters(config, stores) {
  const workflowProjectionSseHub = new WorkflowProjectionSseHub()
  const workflowDefinitionRegistry = new WorkflowDefinitionRegistry(config.workflowsDir)
  const oracleDefinitionRegistry = new OracleDefinitionRegistry(config.oraclesDir)
  const gitWorktreeProvisioner = config.worktreesRoot
    ? new GitWorktreeProvisioner({ worktreesRoot: config.worktreesRoot })
    : null

  const deliveryGit = new DeliveryGitControlPlane({
    serviceIdentity: config.delivery.serviceIdentity,
    configuredRemote: config.delivery.remote,
    allowedPaths: config.delivery.allowedPaths,
    protectedPaths: config.delivery.protectedPaths,
  })
  // GitHub provider wiring is intentionally absent until a trusted server-side
  // adapter is configured. The adapter reports PULL_REQUEST_NOT_CONFIGURED; it
  // never fabricates a PR success.
  // Lot 2 intentionally has no configured deployment provider or target registry.
  // These fail closed with 503 until trusted server-side composition is added.
  const deliveryPullRequests = new DeliveryPullRequestAdapter()
  const deliveryTargetRegistry = new DeliveryTargetRegistry()

  const agentOsProxy = createAgentOsProxy({
    agentosUrl: config.agentosUrl,
    resolvedFactoryUser: resolveFactoryUser(config),
  })

  return {
    workflowProjectionSseHub,
    workflowDefinitionRegistry,
    oracleDefinitionRegistry,
    gitWorktreeProvisioner,
    deliveryGit,
    deliveryPullRequests,
    deliveryTargetRegistry,
    agentOsProxy,
  }
}

// ---------------------------------------------------------------------------
// 4. createApplication — use cases. Controllers/services own business logic;
//    the HTTP layer only delegates to them.
// ---------------------------------------------------------------------------

function buildWorkUnitEnvironmentPolicy(config) {
  return {
    resolve: async (_namespaceId, request) => ({
      repoRoot: config.repoRoot,
      worktreePath: join(config.worktreesRoot, `${request.workflowId}-${request.workUnitId}`),
    }),
  }
}

function buildDeliveryTrustedConfiguration(config) {
  const { owner, repo, baseBranch } = config.delivery.github
  return owner && repo && baseBranch ? { pullRequest: { owner, repo, baseBranch } } : {}
}

/**
 * @param {ReturnType<typeof loadConfig>} config
 * @param {ReturnType<typeof createStores>} stores
 * @param {ReturnType<typeof createAdapters>} adapters
 * @returns {object}
 */
export function createApplication(config, stores, adapters) {
  const {
    workflowProjectionStore,
    workflowEvidenceStore,
    agentStepResultStore,
    workflowHumanInteractionStore,
    workflowResumeDispatchStore,
    workUnitEnvironmentStore,
    deliveryStore,
    deliveryEvidenceStore,
  } = stores
  const { gitWorktreeProvisioner, deliveryGit, deliveryPullRequests, deliveryTargetRegistry } = adapters

  const workUnitEnvironmentController = config.repoRoot && config.worktreesRoot && gitWorktreeProvisioner
    ? new WorkUnitEnvironmentController({
        store: workUnitEnvironmentStore,
        git: gitWorktreeProvisioner,
        policy: buildWorkUnitEnvironmentPolicy(config),
        workflowStore: workflowProjectionStore,
      })
    : null

  const deliveryController = workUnitEnvironmentController
    ? new DeliveryController({
        store: deliveryStore,
        evidenceStore: deliveryEvidenceStore,
        environmentController: workUnitEnvironmentController,
        workflowStore: workflowProjectionStore,
        git: deliveryGit,
        pullRequests: deliveryPullRequests,
        definition: defaultDeliveryDefinition(),
        trustedConfiguration: buildDeliveryTrustedConfiguration(config),
      })
    : null

  const deliveryOperationController = deliveryController
    ? new DeliveryOperationController({
        deliveryController,
        store: deliveryStore,
        targetRegistry: deliveryTargetRegistry,
      })
    : null

  const factoryOperationalMetricsService = new FactoryOperationalMetricsService({
    workflowStore: workflowProjectionStore,
    humanInteractionStore: workflowHumanInteractionStore,
    deliveryStore,
    deliveryEvidenceStore,
  })

  const factoryFrontendRunner = createFactoryFrontendRunner({
    dataRoot: config.factoryDataRoot,
    workflowRoot: config.workflowsDir,
    projectionStore: workflowProjectionStore,
    evidenceStore: workflowEvidenceStore,
    humanInteractionStore: workflowHumanInteractionStore,
    definitionRegistry: adapters.workflowDefinitionRegistry,
    oracleRegistry: adapters.oracleDefinitionRegistry,
    workUnitEnvironmentStore,
    resultStore: agentStepResultStore,
    notifier: adapters.workflowProjectionSseHub,
  })

  const runRouter = createRunRouter({
    runsDir: config.runsDir,
    runEntry: config.runEntry,
    agentosUrl: config.agentosUrl,
    resolvedFactoryUser: resolveFactoryUser(config),
    jiraBaseUrl: config.jira.baseUrl,
    jiraEmail: config.jira.email,
    jiraApiToken: config.jira.apiToken,
    registerGate,
    unregisterGate,
    getGate,
    writeGateReply,
    validateGateSignal,
    log: console,
  })

  const dispatchExpressWorkflowResume = createExpressWorkflowResumeDispatcher(config, workflowResumeDispatchStore)

  // The application object is the assembled runtime: controllers/services plus
  // the stores and adapters they were wired with, so the HTTP layer can be
  // built from `(application, config)` alone.
  return {
    stores,
    adapters,
    workUnitEnvironmentController,
    deliveryController,
    deliveryOperationController,
    factoryOperationalMetricsService,
    factoryFrontendRunner,
    runRouter,
    dispatchExpressWorkflowResume,
  }
}

/**
 * Best-effort resume dispatch for a coday-express controlled workflow.
 * Idempotent: a dispatch id is reserved then marked delivered.
 */
function createExpressWorkflowResumeDispatcher(config, workflowResumeDispatchStore) {
  return async function dispatchExpressWorkflowResume({ namespaceId, storageId, workflowId, interactionId, revision, snapshot }) {
    const controller = snapshot?.instance?.controllerExecution ?? snapshot?.controllerExecution
    if (controller?.kind !== 'coday-express') return { status: 'not-applicable' }
    const { url: expressUrl, project, token, resumeMessage } = config.codayExpress
    if (!expressUrl || !project || !token) return { status: 'not-configured' }
    if (typeof controller.runtimeId !== 'string' || typeof controller.threadId !== 'string' || typeof controller.agentId !== 'string') return { status: 'identity-invalid' }
    const dispatchId = `human:${interactionId}:revision:${revision}`
    const reserved = await workflowResumeDispatchStore.reserve(namespaceId, storageId, {
      dispatchId,
      workflowId,
      interactionId,
      revision,
      controllerExecution: {
        kind: controller.kind,
        runtimeId: controller.runtimeId,
        threadId: controller.threadId,
        agentId: controller.agentId,
      },
    })
    if (!reserved.ok) return { status: 'indeterminate' }
    if (reserved.delivered) return { status: 'delivered' }
    const target = new URL(
      `/api/projects/${encodeURIComponent(project)}/threads/${encodeURIComponent(controller.threadId)}/control-plane-resume`,
      expressUrl,
    )
    const response = await fetch(target, {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        'x-coday-control-plane-token': token,
        'x-coday-runtime-id': controller.runtimeId,
        'x-coday-agent-id': controller.agentId,
      },
      body: JSON.stringify({ message: resumeMessage, workflowId, revision }),
    })
    if (!response.ok) return { status: 'failed' }
    await workflowResumeDispatchStore.delivered(namespaceId, storageId, dispatchId)
    return { status: 'delivered' }
  }
}

// ---------------------------------------------------------------------------
// 5. createHttpServer — transport only.
//
// Responsibilities kept strictly here:
//   - resolve the correlation id and TrustContext once, per request;
//   - delegate to application controllers/services in a deterministic order;
//   - serialize responses through the standardized transport utilities.
// ---------------------------------------------------------------------------

/**
 * @param {ReturnType<typeof createApplication>} application
 * @param {ReturnType<typeof loadConfig>} config
 * @returns {import('node:http').Server}
 */
export function createHttpServer(application, config) {
  const {
    workflowProjectionStore,
    workflowEvidenceStore,
    agentStepResultStore,
    workflowHumanInteractionStore,
  } = application.stores
  const { workflowDefinitionRegistry, oracleDefinitionRegistry, workflowProjectionSseHub, agentOsProxy } = application.adapters
  const {
    workUnitEnvironmentController,
    deliveryController,
    deliveryOperationController,
    factoryOperationalMetricsService,
    factoryFrontendRunner,
    runRouter,
    dispatchExpressWorkflowResume,
  } = application
  const resolvedFactoryUser = resolveFactoryUser(config)
  const { baseUrl: jiraBaseUrl, email: jiraEmail, apiToken: jiraApiToken } = config.jira
  const indexHtml = () => readFileSync(join(config.dashboardDir, 'index.html'), 'utf8')

  const forgeDeps = {
    forgeLedger: { listForgeRunProjections, parseForgeLedger, projectForgeRun, createEpicRun },
    forgeG2: { evaluateG2 },
    forgeStoryAnalysis: { executeStoryAnalysis },
    forgeStoryEdit: { executeStoryEdit },
    forgeStoryOracles: { executeStoryOracles, isAllowedStoryOracleRequestBody },
    forgeHumanDecision: { recordHumanDecision },
    forgeRoots: { resolveForgeRoots, defaultRunStoreRoot, REPO_RUN_STORE_POLICY },
    orchestratorDir: config.factoryDir,
  }

  return createServer(async (req, res) => {
    const url = new URL(req.url, `http://localhost:${config.port}`)
    const path = url.pathname
    const method = req.method

    // Correlation + trust context resolved once, at the edge.
    res.correlationId = resolveCorrelationId(req)
    const trust = extractTrustContext(req, config.bindPolicy)

    if (method === 'OPTIONS') {
      res.writeHead(204, {
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Methods': 'GET,POST,PUT,DELETE',
        'Access-Control-Allow-Headers': 'Content-Type,X-Factory-Namespace-Id,X-Factory-Case-Id,X-Factory-Actor-Id',
        'X-Correlation-Id': res.correlationId,
      })
      return res.end()
    }

    const sendFn = (status, body) => send(res, status, body)
    const readBodyFn = () => readBody(req)

    // Delivery / operation identity requires a namespace + case; the actor may
    // be supplied by the caller, otherwise it stays null (audited downstream).
    const deliveryIdentity = async () =>
      trust.namespaceId && trust.caseId
        ? { namespaceId: trust.namespaceId, caseId: trust.caseId, actorId: trust.actorId ?? null, resolvedActorId: resolvedFactoryUser ?? undefined }
        : null

    // Work-unit environment provisioning defaults the actor to the UI.
    const workUnitIdentity = async () =>
      trust.namespaceId && trust.caseId
        ? { namespaceId: trust.namespaceId, caseId: trust.caseId, actorId: trust.actorId ?? 'factory-ui' }
        : null

    if (deliveryOperationController && await handleDeliveryOperationRequest({ method, path, readBody: readBodyFn, send: sendFn, controller: deliveryOperationController, identity: deliveryIdentity, log: console })) return

    if (deliveryController && await handleDeliveryRequest({ method, path, readBody: readBodyFn, send: sendFn, controller: deliveryController, identity: deliveryIdentity, log: console })) return

    if (workUnitEnvironmentController && await handleWorkUnitEnvironmentRequest({
      method, path, url,
      readBody: readBodyFn,
      send: sendFn,
      controller: workUnitEnvironmentController,
      identity: workUnitIdentity,
      log: console,
    })) return

    if (await handleWorkflowDefinitionRequest({ method, path, send: sendFn, registry: workflowDefinitionRegistry, log: console })) return

    if (await handleAgentStepResultRequest({ method, path, headers: req.headers, readBody: readBodyFn, send: sendFn, resultStore: agentStepResultStore, log: console })) return

    if (await handleFactoryFrontendRunRequest({ method, path, readBody: readBodyFn, send: sendFn, runner: factoryFrontendRunner, log: console })) return

    if (await handleWorkflowOracleRequest({ method, path, readBody: readBodyFn, send: sendFn, projectionStore: workflowProjectionStore, evidenceStore: workflowEvidenceStore, definitionRegistry: workflowDefinitionRegistry, oracleRegistry: oracleDefinitionRegistry, repoRoot: config.oracle.repoRoot, log: console })) return

    if (await handleWorkflowCodeTransitionRequest({ method, path, readBody: readBodyFn, send: sendFn, store: workflowProjectionStore, evidenceStore: workflowEvidenceStore, definitionRegistry: workflowDefinitionRegistry, namespaceId: config.oracle.namespaceId, notifier: workflowProjectionSseHub, log: console })) return

    if (await handleWorkflowTransitionRequest({ method, path, readBody: readBodyFn, send: sendFn, store: workflowProjectionStore, evidenceStore: workflowEvidenceStore, definitionRegistry: workflowDefinitionRegistry, notifier: workflowProjectionSseHub, log: console })) return

    if (await handleWorkflowHumanInteractionRequest({
      method, path, url,
      readBody: readBodyFn,
      send: sendFn,
      projectionStore: workflowProjectionStore,
      interactionStore: workflowHumanInteractionStore,
      evidenceStore: workflowEvidenceStore,
      definitionRegistry: workflowDefinitionRegistry,
      identity: { actorId: async () => resolvedFactoryUser ?? null },
      controllerIdentity: async () =>
        trust.namespaceId && trust.runtimeId && trust.agentId && (trust.caseId || trust.threadId)
          ? {
              namespaceId: trust.namespaceId,
              runtimeId: trust.runtimeId,
              agentId: trust.agentId,
              kind: trust.caseId ? 'agentos' : 'coday-express',
              ...(trust.caseId ? { caseId: trust.caseId } : { threadId: trust.threadId }),
            }
          : null,
      resumeDispatcher: dispatchExpressWorkflowResume,
      notifier: workflowProjectionSseHub,
      log: console,
    })) return

    if (await handleWorkflowEvidenceRequest({ method, path, url, readBody: readBodyFn, send: sendFn, projectionStore: workflowProjectionStore, evidenceStore: workflowEvidenceStore, definitionRegistry: workflowDefinitionRegistry, log: console })) return

    if (await handleWorkflowOperationalMetricsRequest({ method, path, url, send: sendFn, service: factoryOperationalMetricsService, clock: { now: () => new Date() }, log: console })) return

    if (await handleForgeWorkflowProjectionRequest({ method, path, url, readBody: readBodyFn, send: sendFn, resolveRepoRoot: agentOsProxy.resolveRepoRoot, store: workflowProjectionStore, notifier: workflowProjectionSseHub, log: console })) return

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
          'X-Correlation-Id': res.correlationId,
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
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Access-Control-Allow-Origin': '*', 'X-Correlation-Id': res.correlationId })
      return res.end(indexHtml())
    }

    // GET /api/config — expose l'URL AgentOS pour les liens profonds côté client,
    // et la disponibilité Jira. On n'expose JAMAIS les valeurs des credentials.
    if (method === 'GET' && path === '/api/config') {
      const jiraConfigured = !!(jiraBaseUrl && jiraEmail && jiraApiToken)
      return sendFn(200, { agentosUrl: config.agentosUrl, factoryUser: resolvedFactoryUser, jiraConfigured })
    }

    // Forge routes — G1 decision injects actor/authority read only from headers.
    if (path.match(/^\/api\/(?:factory\/)?forge\/runs\/[^/]+\/gates\/G1\/decision$/) && method === 'POST') {
      const readBodyWithIdentity = async () => ({ ...await readBodyFn(), _actorId: trust.actorId, _authorityId: trust.authorityId })
      if (await handleForgeRunRequest({ method, path, url, readBody: readBodyWithIdentity, send: sendFn, proxy: agentOsProxy, log: console, ...forgeDeps })) return
    }

    if (await handleForgeRunRequest({ method, path, url, readBody: readBodyFn, send: sendFn, proxy: agentOsProxy, log: console, ...forgeDeps })) return

    if (await handleActiveRunRequest({ method, path, url, readBody: readBodyFn, send: sendFn, proxy: agentOsProxy, log: console })) return

    if (await handleWorkstreamRequest({ method, path, url, readBody: readBodyFn, send: sendFn, proxy: agentOsProxy, log: console })) return

    // Run routes — legacy JSONL runs + review gates.
    if (await runRouter.handleRequest({ method, path, url, readBody: readBodyFn, send: sendFn, req, res })) return

    // AgentOS proxy pass-through
    if (method === 'GET' && path === '/api/agents') {
      const namespaceId = url.searchParams.get('namespaceId')
      if (!namespaceId) return sendError(sendFn, 400, 'MISSING_NAMESPACE_ID', 'namespaceId requis')
      try { return sendFn(200, await agentOsProxy.fetchAgents(namespaceId)) }
      catch (err) { return sendError(sendFn, 502, 'AGENTOS_UNAVAILABLE', String(err)) }
    }

    const eventsMatch = path.match(/^\/api\/cases\/([^/]+)\/events$/)
    if (method === 'GET' && eventsMatch) {
      try { return sendFn(200, await agentOsProxy.fetchCaseEvents(eventsMatch[1])) }
      catch (err) { return sendError(sendFn, 502, 'AGENTOS_UNAVAILABLE', String(err)) }
    }

    // Jira proxy
    const jiraMatch = path.match(/^\/api\/(?:factory\/)?jira\/([^/]+)$/)
    if (method === 'GET' && jiraMatch) {
      if (!jiraBaseUrl || !jiraEmail || !jiraApiToken) {
        const missing = [!jiraBaseUrl ? 'JIRA_BASE_URL' : null, !jiraEmail ? 'JIRA_EMAIL' : null, !jiraApiToken ? 'JIRA_API_TOKEN' : null].filter(Boolean)
        return sendError(
          sendFn,
          501,
          'JIRA_NOT_CONFIGURED',
          `Le serveur du dashboard n'a pas de credentials Jira configurés (manquant : ${missing.join(', ')}). Relancez-le avec ces variables dans son environnement : JIRA_BASE_URL=https://votre-instance.atlassian.net JIRA_EMAIL=votre@email.com JIRA_API_TOKEN=votre-token node factory/dashboard/server.mjs`,
          { missing },
        )
      }
      try { return sendFn(200, { ...await fetchJiraTicket(jiraMatch[1], jiraBaseUrl, jiraEmail, jiraApiToken), fetchedAt: new Date().toISOString() }) }
      catch (err) { return sendError(sendFn, 502, 'JIRA_UNAVAILABLE', String(err)) }
    }

    sendError(sendFn, 404, 'NOT_FOUND', 'Not found')
  })
}

// ---------------------------------------------------------------------------
// createCompositionRoot — assemble everything, expose initialize()/start().
// ---------------------------------------------------------------------------

/**
 * @param {Record<string, string|undefined>} [env]
 * @returns {{
 *   config: object, stores: object, adapters: object, application: object,
 *   server: import('node:http').Server,
 *   initialize: () => Promise<void>,
 *   start: () => Promise<import('node:http').Server>,
 * }}
 */
export function createCompositionRoot(env = process.env) {
  const config = loadConfig(env)
  const stores = createStores(config)
  const adapters = createAdapters(config, stores)
  const application = createApplication(config, stores, adapters)
  const server = createHttpServer(application, config)

  async function initialize() {
    await stores.workflowProjectionStore.initialize()
    await stores.agentStepResultStore.initialize()
    await adapters.workflowDefinitionRegistry.initialize()
    if (application.workUnitEnvironmentController) await application.workUnitEnvironmentController.initialize()
    if (application.deliveryController) await application.deliveryController.initialize()
    if (adapters.oracleDefinitionRegistry) await adapters.oracleDefinitionRegistry.initialize()
  }

  async function start() {
    await initialize()
    await new Promise((resolve, reject) => {
      server.once('error', reject)
      server.listen(config.port, config.bindPolicy.host, () => {
        server.removeListener('error', reject)
        logStartup(config)
        resolve()
      })
    })
    return server
  }

  return { config, stores, adapters, application, server, initialize, start }
}

function logStartup(config) {
  console.log(`Factory dashboard → http://${config.bindPolicy.host}:${config.port}`)
  console.log(`Factory bind mode  : ${config.bindPolicy.trustMode}${config.bindPolicy.trustMode.startsWith('unsafe') ? ' (explicit unsafe opt-in; routes are unauthenticated)' : ''}`)
  console.log(`AgentOS           : ${config.agentosUrl}`)
  console.log(`Factory data root : ${config.factoryDataRoot}`)
  for (const msg of config.jira.diagnostics ?? []) console.log(`Coday config      : ${msg}`)
  if (config.jira.baseUrl) {
    const src = process.env.JIRA_BASE_URL ? 'env' : 'user.yaml Coday'
    console.log(`Jira              : ${config.jira.baseUrl} (${src})`)
  } else {
    console.log(`Jira              : non configuré (tickets Jira indisponibles)`)
  }
}
