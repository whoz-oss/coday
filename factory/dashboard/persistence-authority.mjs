/**
 * Factory persistence authority selection (Milestone B4-T2).
 *
 * This module owns the *policy* of the persistence migration, not the object
 * graph: it decides, from configuration, whether the dashboard serves its
 * aggregates from the filesystem (the historical authority), from PostgreSQL
 * (the writer-unique target), or from the filesystem while PostgreSQL is read
 * in *shadow* mode for drift detection.
 *
 * The concrete filesystem stores are still instantiated in
 * `composition-root.mjs` (the only place allowed to do so); this module only
 * decorates or replaces the persistence-facing methods.
 *
 * Three modes, driven by `FACTORY_PERSISTENCE` / `FACTORY_PERSISTENCE_SHADOW`:
 *
 *   - `fs`   (default)            → filesystem authority, untouched behaviour.
 *   - `fs` + `FACTORY_PERSISTENCE_SHADOW=true`
 *                                 → filesystem authority; every shadowed read
 *                                   also reads PostgreSQL and logs a canonical
 *                                   hash discrepancy. Never fails the caller
 *                                   and never changes the served result.
 *   - `sql`                       → PostgreSQL authority: the store methods that
 *                                   have a SQL repository are served by it;
 *                                   filesystem receives zero writes. Operations
 *                                   with no SQL adapter fail closed with
 *                                   {@link PersistenceOperationNotMigratedError}
 *                                   instead of silently writing to disk.
 *
 * Safety contract of shadow mode: the filesystem result is *always* what the
 * caller receives, even when the shadow query throws or the comparison fails.
 */

import {
  computeCanonicalHash,
  createPgPoolClient,
  createSqlAgentStepResultRepository,
  createSqlDeliveryRepository,
  createSqlWorkEnvironmentRepository,
  createSqlWorkflowEvidenceRepository,
  createSqlWorkflowHumanInteractionRepository,
  createSqlWorkflowInstanceRepository,
  evaluateHumanCheckpointOpen,
  evaluateHumanResolutionTransition,
  resolveSqlDatabaseConfig,
} from '../runtime/factory-operational.mjs'

/** Canonical persistence modes. */
export const PERSISTENCE_MODES = Object.freeze({ FS: 'fs', SQL: 'sql' })

/** Prefixes used by the shadow-read diagnostics so operators can grep logs. */
export const SHADOW_READ_DISCREPANCY = '[SHADOW_READ_DISCREPANCY]'
export const SHADOW_READ_ERROR = '[SHADOW_READ_ERROR]'

/** Raised when SQL mode is asked for an operation that has no SQL adapter yet. */
export class PersistenceOperationNotMigratedError extends Error {
  constructor(store, method) {
    super(
      `Persistence operation not migrated to SQL: ${store}.${method}. ` +
        'Set FACTORY_PERSISTENCE=fs (default) or roll back until the SQL adapter covers this operation.'
    )
    this.name = 'PersistenceOperationNotMigratedError'
    this.code = 'PERSISTENCE_OPERATION_NOT_MIGRATED'
    this.store = store
    this.method = method
  }
}

/**
 * Parse the persistence configuration from an environment.
 *
 * `FACTORY_PERSISTENCE=fs|sql` (default `fs`) selects the authority.
 * `FACTORY_PERSISTENCE_SHADOW=true` enables shadow reads, but only while the
 * filesystem remains the authority — a `sql` authority already reads the target
 * directly, so shadowing it would be meaningless.
 *
 * @param {Record<string, string|undefined>} [env]
 * @returns {{ persistenceMode: 'fs'|'sql', shadowReadEnabled: boolean }}
 */
export function resolvePersistenceSettings(env = {}) {
  const requested = String(env.FACTORY_PERSISTENCE ?? PERSISTENCE_MODES.FS).toLowerCase()
  const persistenceMode = requested === PERSISTENCE_MODES.SQL ? PERSISTENCE_MODES.SQL : PERSISTENCE_MODES.FS
  const shadowRequested = String(env.FACTORY_PERSISTENCE_SHADOW ?? 'false').toLowerCase() === 'true'
  const shadowReadEnabled = persistenceMode === PERSISTENCE_MODES.FS && shadowRequested
  return { persistenceMode, shadowReadEnabled }
}

/**
 * A lazily-connected `SqlClient` over a `pg` pool.
 *
 * `createPgPoolClient` loads the driver asynchronously, so the composition root
 * (which stays synchronous) is handed this client and the driver is only loaded
 * on the first query. Shadow-read errors are caught by the shadow wrapper, so a
 * missing driver can never fail a request.
 */
export function createLazySqlClient(env = process.env) {
  let pending = null
  return {
    async query(text, params) {
      if (!pending) pending = createPgPoolClient(resolveSqlDatabaseConfig(env))
      return (await pending).query(text, params)
    },
  }
}

/**
 * Build every SQL repository that participates in the migration, scoped to one
 * tenant (organization + workstream).
 *
 * @param {{ client: object, organizationId?: string, workstreamId?: string }} options
 */
export function createSqlRepositories({ client, organizationId, workstreamId }) {
  const scope = { organizationId, workstreamId }
  return {
    workflowInstance: createSqlWorkflowInstanceRepository(client, scope),
    workflowEvidence: createSqlWorkflowEvidenceRepository(client, scope),
    workflowHumanInteraction: createSqlWorkflowHumanInteractionRepository(client, scope),
    agentStepResult: createSqlAgentStepResultRepository(client, scope),
    workEnvironment: createSqlWorkEnvironmentRepository(client, scope),
    delivery: createSqlDeliveryRepository(client, scope),
  }
}

// ---------------------------------------------------------------------------
// Shadow reads
// ---------------------------------------------------------------------------

/** Enumerate every own + inherited member of an object (methods and fields). */
function collectMemberNames(target) {
  const names = new Set()
  let current = target
  while (current && current !== Object.prototype) {
    for (const name of Object.getOwnPropertyNames(current)) names.add(name)
    current = Object.getPrototypeOf(current)
  }
  names.delete('constructor')
  return names
}

/** Wrap one read method so it serves the filesystem result and shadows SQL. */
function createShadowMethod(fsStore, method, probe, label, log) {
  return async function shadowRead(...args) {
    const fsResult = await fsStore[method].apply(fsStore, args)
    try {
      const sqlResult = await probe.read(...args)
      const fsAggregate = probe.projectFilesystem ? probe.projectFilesystem(fsResult) : fsResult
      const sqlAggregate = probe.projectSql ? probe.projectSql(sqlResult) : sqlResult
      const filesystemHash = computeCanonicalHash(fsAggregate)
      const sqlHash = computeCanonicalHash(sqlAggregate)
      if (filesystemHash !== sqlHash) {
        log.warn(SHADOW_READ_DISCREPANCY, {
          store: label,
          method,
          key: typeof probe.describe === 'function' ? probe.describe(...args) : null,
          filesystemHash,
          sqlHash,
        })
      }
    } catch (error) {
      log.warn(SHADOW_READ_ERROR, {
        store: label,
        method,
        message: error?.message ?? String(error),
      })
    }
    return fsResult
  }
}

/**
 * Decorate a filesystem store so its shadowed read methods also query SQL.
 *
 * Every non-shadowed member is delegated unchanged (bound to the original
 * instance so TypeScript `#private` fields keep working). The returned object
 * is a plain store-shaped facade; it is not an instance of the filesystem
 * store class, which is irrelevant to callers that only use the store API.
 *
 * @param {object} fsStore
 * @param {Array<object>} probes each with `{ method, read, projectFilesystem?, projectSql?, describe? }`
 * @param {{ label?: string, log?: Console }} [options]
 */
export function createShadowReadStore(fsStore, probes, { label = 'store', log = console } = {}) {
  const probeByMethod = new Map(probes.map((probe) => [probe.method, probe]))
  const facade = {}
  for (const name of collectMemberNames(fsStore)) {
    const value = fsStore[name]
    if (typeof value !== 'function') {
      facade[name] = value
      continue
    }
    facade[name] = probeByMethod.has(name)
      ? createShadowMethod(fsStore, name, probeByMethod.get(name), label, log)
      : value.bind(fsStore)
  }
  Object.defineProperty(facade, '__persistenceAuthority', { value: 'fs-shadow', enumerable: false })
  Object.defineProperty(facade, '__storeLabel', { value: label, enumerable: false })
  return facade
}

/**
 * The read-only SQL probes used by shadow mode.
 *
 * Each probe normalizes the filesystem and SQL results to the same canonical
 * aggregate before hashing, so shape differences between a store snapshot and
 * its repository projection do not produce false positives.
 */
export function buildShadowProbes(repositories) {
  return {
    workflowProjectionStore: [
      {
        method: 'read',
        describe: (namespaceId, workflowId) => `${namespaceId}/${workflowId}`,
        read: (namespaceId, workflowId) => repositories.workflowInstance.get(namespaceId, workflowId),
        projectFilesystem: (snapshot) =>
          snapshot ? { instance: snapshot.instance, projection: snapshot.projection } : null,
        projectSql: (snapshot) => (snapshot ? { instance: snapshot.instance, projection: snapshot.projection } : null),
      },
      {
        method: 'list',
        describe: (namespaceId) => namespaceId,
        read: (namespaceId) => repositories.workflowInstance.list(namespaceId),
        projectFilesystem: (snapshots) => (snapshots ?? []).map((snapshot) => snapshot.projection),
        projectSql: (projections) => projections ?? [],
      },
    ],
    // The evidence, human-interaction and agent-step-result stores key their
    // filesystem journal by a *storage digest* while their SQL adapters expose a
    // scope-keyed ledger. They are deliberately not shadowed here to avoid
    // key-convention false positives; their SQL repositories are still wired for
    // SQL authority mode.
    workUnitEnvironmentStore: [
      {
        method: 'list',
        describe: (namespaceId) => namespaceId,
        read: (namespaceId, filter) => repositories.workEnvironment.list(namespaceId, filter),
      },
      {
        method: 'read',
        describe: (namespaceId, environmentId) => `${namespaceId}/${environmentId}`,
        read: (namespaceId, environmentId) => repositories.workEnvironment.read(namespaceId, environmentId),
      },
    ],
    deliveryStore: [
      {
        method: 'read',
        describe: (namespaceId, deliveryId) => `${namespaceId}/${deliveryId}`,
        read: (namespaceId, deliveryId) => repositories.delivery.read(namespaceId, deliveryId),
      },
      {
        method: 'readWithOperations',
        describe: (namespaceId, deliveryId) => `${namespaceId}/${deliveryId}`,
        read: (namespaceId, deliveryId) => repositories.delivery.readWithOperations(namespaceId, deliveryId),
      },
      {
        method: 'inspectDeliveryOperations',
        describe: (namespaceId, deliveryId) => `${namespaceId}/${deliveryId}`,
        read: (namespaceId, deliveryId) => repositories.delivery.inspectDeliveryOperations(namespaceId, deliveryId),
      },
    ],
  }
}

/**
 * Wrap a filesystem store bundle with shadow-read decorators.
 *
 * Stores that have no SQL probe stay exactly as-is; the SQL repositories are
 * only *read* from, never written.
 *
 * @param {{ stores: object, repositories: object, log?: Console }} options
 */
export function createShadowReadStores({ stores, repositories, log = console }) {
  const probesByStore = buildShadowProbes(repositories)
  const decorated = {}
  for (const [key, store] of Object.entries(stores)) {
    const probes = probesByStore[key]
    decorated[key] = probes && probes.length > 0 ? createShadowReadStore(store, probes, { label: key, log }) : store
  }
  Object.defineProperty(decorated, '__persistenceAuthority', { value: 'fs-shadow', enumerable: false })
  return decorated
}

// ---------------------------------------------------------------------------
// SQL authority stores
// ---------------------------------------------------------------------------

/**
 * Build a store-shaped facade over explicit method handlers.
 *
 * Unmapped members are undefined: calling one fails with a TypeError rather
 * than silently falling back to a filesystem write. `initialize()` defaults to
 * a no-op because the PostgreSQL schema is owned by the Flyway migrations.
 */
export function createSqlBoundStore(label, handlers, properties = {}) {
  const facade = { ...properties, __persistenceAuthority: 'sql', __storeLabel: label }
  for (const [name, handler] of Object.entries(handlers)) facade[name] = handler
  if (typeof facade.initialize !== 'function') facade.initialize = async () => {}
  return facade
}

/** Delegate a fixed set of methods that share the repository's name and shape. */
function createDelegatingSqlStore(label, repository, methodNames) {
  const handlers = {}
  for (const name of methodNames) {
    const method = repository[name]
    if (typeof method !== 'function') throw new Error(`SQL repository for ${label} is missing method ${name}`)
    handlers[name] = method.bind(repository)
  }
  return createSqlBoundStore(label, handlers)
}

/**
 * SQL-backed workflow projection store.
 *
 * The workflow-instance repository implements the lifecycle (create / get /
 * list / transition / remove / restore / purge). Operations that depend on the
 * filesystem journal and transition policy contract — `publish`, `timing`,
 * `facts`, `listRemoved`, the human-checkpoint helpers — are intentionally not
 * mapped: they fail closed until the instance repository covers them.
 */
function createProjectionSqlStore(repository) {
  const transition = async (namespaceId, request, definition, evidence, controllerExecution, options = {}) => {
    try {
      const snapshot = await repository.transition(namespaceId, request.workflowId, {
        request,
        definition,
        evidence,
        execution: controllerExecution,
        policy: options.policy,
      })
      return { ok: true, changed: true, idempotent: false, snapshot }
    } catch (error) {
      return { ok: false, error: { code: error?.code ?? 'TRANSITION_REJECTED' }, decision: error?.decision }
    }
  }
  const lifecycle = (operation) => async (namespaceId, workflowId, actor) => {
    try {
      await repository[operation](namespaceId, workflowId, actor)
      const snapshot = operation === 'restore' ? await repository.get(namespaceId, workflowId) : null
      return snapshot ? { ok: true, snapshot } : { ok: true }
    } catch (error) {
      return { ok: false, error: { code: error?.code ?? 'WORKFLOW_STORAGE_FAILURE' } }
    }
  }
  return createSqlBoundStore('workflowProjectionStore', {
    read: (namespaceId, workflowId) => repository.get(namespaceId, workflowId),
    get: (namespaceId, workflowId) => repository.get(namespaceId, workflowId),
    lookup: async (namespaceId, workflowId) => {
      const snapshot = await repository.get(namespaceId, workflowId)
      return snapshot ? { state: 'existing', workflowId, snapshot } : { state: 'absent', workflowId }
    },
    list: async (namespaceId) => {
      const projections = await repository.list(namespaceId)
      const snapshots = await Promise.all(
        projections.map((projection) => repository.get(namespaceId, projection.workflowId))
      )
      return snapshots.filter(Boolean)
    },
    start: async (namespaceId, command, definition, controllerExecution) => {
      try {
        const existing = await repository.get(namespaceId, command.workflowId)
        const snapshot = await repository.create(namespaceId, command, definition, controllerExecution)
        return { ok: true, created: existing === null, idempotent: existing !== null, snapshot }
      } catch (error) {
        return { ok: false, error: { code: error?.code ?? 'WORKFLOW_INSTANCE_CREATE_FAILED' } }
      }
    },
    transition,
    openHumanCheckpoint: (namespaceId, request, definition, controllerExecution, options = {}) =>
      transition(namespaceId, request, definition, [], controllerExecution, {
        ...options,
        policy: evaluateHumanCheckpointOpen,
      }),
    resolveHumanCheckpoint: (namespaceId, request, definition, evidence, humanExecution, options = {}) =>
      transition(namespaceId, request, definition, evidence, humanExecution, {
        ...options,
        policy: evaluateHumanResolutionTransition,
      }),
    remove: lifecycle('remove'),
    restore: lifecycle('restore'),
    purge: lifecycle('purge'),
  })
}

/**
 * Build the PostgreSQL-authority store bundle.
 *
 * Every mapped method is served by the SQL repository; the filesystem is never
 * touched. Stores without a SQL adapter (delivery evidence, resume dispatch)
 * are represented by an empty facade, so calling them fails closed rather than
 * silently writing to disk under a PostgreSQL authority.
 */
export function createSqlAuthorityStores({ repositories }) {
  return {
    workflowProjectionStore: createProjectionSqlStore(repositories.workflowInstance),
    workflowEvidenceStore: createDelegatingSqlStore('workflowEvidenceStore', repositories.workflowEvidence, [
      'list',
      'record',
    ]),
    workflowHumanInteractionStore: createDelegatingSqlStore(
      'workflowHumanInteractionStore',
      repositories.workflowHumanInteraction,
      ['list', 'events', 'reconcileOpen', 'recordOpen', 'recordTransition']
    ),
    agentStepResultStore: createDelegatingSqlStore('agentStepResultStore', repositories.agentStepResult, [
      'issue',
      'submit',
      'getByAttempt',
      'list',
    ]),
    workUnitEnvironmentStore: createDelegatingSqlStore('workUnitEnvironmentStore', repositories.workEnvironment, [
      'paths',
      'read',
      'list',
      'reserve',
      'transition',
    ]),
    deliveryStore: createDelegatingSqlStore('deliveryStore', repositories.delivery, [
      'read',
      'create',
      'promote',
      'readWithOperations',
      'inspectDeliveryOperations',
      'createRollbackRequest',
      'approveRollbackRequest',
      'createDeliveryOperation',
      'recordDeliveryOperation',
      'startDeliveryOperation',
      'reconcileDeliveryOperation',
      'hasIndeterminateOperation',
      'updateSnapshot',
    ]),
    // No SQL adapter exists yet for these contexts. They are represented by an
    // empty facade so that *any* call fails closed instead of writing to the
    // filesystem under a SQL authority.
    deliveryEvidenceStore: createSqlBoundStore('deliveryEvidenceStore', {}),
    workflowResumeDispatchStore: createSqlBoundStore('workflowResumeDispatchStore', {}),
    __persistenceAuthority: 'sql',
  }
}
