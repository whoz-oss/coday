// Local worker-runtime entrypoint.
//
// This module is the *operational* surface of the Jalon C2 worker runtime: it
// re-exports the frozen C2-T1 domain (`domain/worker-runtime/`) and wires it
// against the real C1 SQL persistence adapters (`adapters/persistence/sql/`)
// through a single local launcher, `createLocalWorkerRuntime` / `runLocalWorker`.
//
// It introduces **no** safety rule of its own: the lease protocol, fencing and
// expiry stay owned by the SQL adapters and the pure lease domain. The only
// behaviour added here is (a) dependency wiring and (b) a deterministic *demo*
// executor that never dispatches a real ADW task.
//
// Dependency direction: this file may import `adapters/`, `domain/` and the
// PostgreSQL client factory; the frozen C2-T1 domain and the C1 SQL adapters
// never import it back.

import {
  DEFAULT_ORGANIZATION_ID,
  DEFAULT_WORKSTREAM_ID,
  createPgPoolClient,
  resolveSqlDatabaseConfig,
  type SqlClient,
  type SqlDatabaseConfig,
} from '../adapters/persistence/sql/db.js'
import {
  createSqlLeaseRepository,
  createSqlWorkerRepository,
  createSqlWorkUnitRepository,
} from '../adapters/persistence/sql/index.js'
import { WorkerRuntime } from '../domain/worker-runtime/worker-runtime.js'
import type {
  WorkExecutionResult,
  WorkExecutor,
  WorkerRuntimeConfig,
  WorkerRuntimeLogger,
  WorkerRuntimeStopOptions,
} from '../domain/worker-runtime/types.js'
import type { WorkUnit } from '../domain/work-unit.js'

// Public surface: the whole worker-runtime domain (vocabulary + loop) is
// re-exported so a single import from this entrypoint (and therefore from the
// generated `factory-operational.mjs` bundle) exposes types, `WorkerRuntime`,
// `createDemoWorkExecutor` and the local launchers.
export * from '../domain/worker-runtime/types.js'
export * from '../domain/worker-runtime/worker-runtime.js'

/** Default demo executor delay, in milliseconds. */
const DEFAULT_DEMO_DELAY_MS = 50
/** Default lease TTL applied when `LEASE_TTL_MS` is absent or invalid. */
const DEFAULT_LEASE_TTL_MS = 30_000
/** Default heartbeat interval applied when unset. */
const DEFAULT_HEARTBEAT_INTERVAL_MS = 10_000
/** Default poll backoff applied when unset. */
const DEFAULT_POLL_BACKOFF_MS = 2_000

/** Options accepted by {@link createDemoWorkExecutor}. */
export interface DemoWorkExecutorOptions {
  /** Deterministic no-op delay before reporting success; defaults to 50ms. */
  delayMs?: number
  /** Structured logger the executor reports to. */
  logger?: WorkerRuntimeLogger
}

/** Configuration for the locally-wired worker runtime. */
export interface LocalWorkerRuntimeOptions {
  /** PostgreSQL settings; missing keys fall back to the `PG*` environment. */
  dbConfig?: Partial<SqlDatabaseConfig>
  /** Tenant the SQL repositories are scoped to. */
  organizationId?: string
  /** Workstream the lease / work-unit repositories are scoped to. */
  workstreamId?: string
  /** Durable worker identity; defaults to `WORKER_ID` then `local-worker-1`. */
  workerId?: string
  /** Worker type recorded at registration. */
  workerType?: string
  /** Lease TTL; defaults to `LEASE_TTL_MS` then 30000ms. */
  leaseTtlMs?: number
  /** Heartbeat interval; defaults to 10000ms. */
  heartbeatIntervalMs?: number
  /** Empty-claim poll backoff; defaults to 2000ms. */
  pollBackoffMs?: number
  /** Maximum concurrent executions; defaults to 1. */
  concurrency?: number
  /** Optional environment bound to every acquired lease. */
  environmentId?: string | null
  /** Capabilities advertised at worker registration. */
  capabilities?: readonly string[]
  /** Lease-protocol version advertised at registration. */
  protocolVersion?: string
  /** Job executor; defaults to {@link createDemoWorkExecutor}. */
  executor?: WorkExecutor
  /** Structured logger; defaults to a console-backed logger. */
  logger?: WorkerRuntimeLogger
  /**
   * Pre-built SQL client. Injected by tests (and by a caller that already owns
   * a pool); when omitted the entrypoint lazily loads the `pg` driver and opens
   * its own pool via {@link createPgPoolClient}.
   */
  client?: SqlClient
}

/** A locally-wired runtime plus the client it owns and its lifecycle helpers. */
export interface LocalWorkerRuntimeHandle {
  /** The wired runtime instance (domain-owned loop). */
  runtime: WorkerRuntime
  /** The SQL client backing the three repositories. */
  client: SqlClient
  /** The executor the runtime delegates work to. */
  executor: WorkExecutor
  /** The resolved runtime configuration. */
  config: WorkerRuntimeConfig
  /** Registers the worker and enters the claim loop. */
  start: () => Promise<void>
  /** Gracefully drains the runtime. */
  stop: (options?: WorkerRuntimeStopOptions) => Promise<void>
}

/** Structured console logger used when no logger is injected. */
export function createConsoleWorkerRuntimeLogger(prefix = '[worker-runtime]'): WorkerRuntimeLogger {
  const write = (
    level: 'info' | 'warn' | 'error' | 'debug',
    message: string,
    details?: Record<string, unknown>
  ): void => {
    const line = `${prefix} ${level}: ${message}`
    if (details === undefined) console[level === 'debug' ? 'log' : level](line)
    else console[level === 'debug' ? 'log' : level](line, details)
  }
  return {
    info: (message, details) => write('info', message, details),
    warn: (message, details) => write('warn', message, details),
    error: (message, details) => write('error', message, details),
    debug: (message, details) => write('debug', message, details),
  }
}

/** Parses a positive integer from an environment value, falling back otherwise. */
function positiveInt(value: string | undefined, fallback: number): number {
  if (value === undefined) return fallback
  const parsed = Number.parseInt(value, 10)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback
}

/** Waits `ms`, rejecting with the abort reason if `signal` fires meanwhile. */
function sleepWithSignal(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise<void>((resolve, reject) => {
    if (signal.aborted) {
      reject(signal.reason ?? new Error('ABORTED'))
      return
    }
    const onAbort = (): void => {
      clearTimeout(handle)
      reject(signal.reason ?? new Error('ABORTED'))
    }
    const handle = setTimeout(() => {
      signal.removeEventListener('abort', onAbort)
      resolve()
    }, ms)
    signal.addEventListener('abort', onAbort, { once: true })
  })
}

/**
 * Builds the deterministic **demo** {@link WorkExecutor}.
 *
 * It logs the work unit it was handed, sleeps a short configurable delay (while
 * observing the abort signal) and reports `completed` with an `executor:
 * 'demo-echo'` payload patch. It performs **no** ADW dispatch, no external
 * call, no filesystem or network I/O — it is a pure scaffold for local runs.
 */
export function createDemoWorkExecutor(options: DemoWorkExecutorOptions = {}): WorkExecutor {
  const delayMs = options.delayMs ?? DEFAULT_DEMO_DELAY_MS
  const logger = options.logger ?? createConsoleWorkerRuntimeLogger('[worker-runtime:demo-executor]')
  return {
    async execute(workUnit: WorkUnit, signal: AbortSignal): Promise<WorkExecutionResult> {
      logger.info('demo executor: start (no real ADW dispatch)', {
        workUnitId: workUnit.workUnitId,
        unitType: workUnit.unitType,
        status: workUnit.status,
        payload: workUnit.payload,
      })
      await sleepWithSignal(delayMs, signal)
      logger.info('demo executor: completed', { workUnitId: workUnit.workUnitId })
      return {
        status: 'completed',
        payloadUpdate: { executedAt: new Date().toISOString(), executor: 'demo-echo' },
      }
    },
  }
}

/**
 * Wires and returns a local worker runtime against PostgreSQL (or an injected
 * client). The three SQL repositories and the demo executor are constructed
 * here; `start`/`stop` delegate to the domain-owned loop.
 */
export async function createLocalWorkerRuntime(
  options: LocalWorkerRuntimeOptions = {}
): Promise<LocalWorkerRuntimeHandle> {
  const logger = options.logger ?? createConsoleWorkerRuntimeLogger()
  const dbConfig: SqlDatabaseConfig = { ...resolveSqlDatabaseConfig(), ...options.dbConfig }
  const client = options.client ?? (await createPgPoolClient(dbConfig))

  const organizationId = options.organizationId ?? DEFAULT_ORGANIZATION_ID
  const workstreamId = options.workstreamId ?? DEFAULT_WORKSTREAM_ID

  const leaseRepo = createSqlLeaseRepository(client, { organizationId, workstreamId })
  const workUnitRepo = createSqlWorkUnitRepository(client, { organizationId, workstreamId })
  const workerRepo = createSqlWorkerRepository(client, { organizationId })

  const config: WorkerRuntimeConfig = {
    organizationId,
    workstreamId,
    workerId: options.workerId ?? process.env.WORKER_ID ?? 'local-worker-1',
    workerType: options.workerType ?? 'local-demo-worker',
    leaseTtlMs: options.leaseTtlMs ?? positiveInt(process.env.LEASE_TTL_MS, DEFAULT_LEASE_TTL_MS),
    heartbeatIntervalMs: options.heartbeatIntervalMs ?? DEFAULT_HEARTBEAT_INTERVAL_MS,
    pollBackoffMs: options.pollBackoffMs ?? DEFAULT_POLL_BACKOFF_MS,
    concurrency: options.concurrency ?? 1,
  }
  if (options.environmentId !== undefined) config.environmentId = options.environmentId
  if (options.capabilities !== undefined) config.capabilities = [...options.capabilities]
  if (options.protocolVersion !== undefined) config.protocolVersion = options.protocolVersion

  const executor = options.executor ?? createDemoWorkExecutor({ logger })
  const runtime = new WorkerRuntime(config, {
    leaseRepo,
    workUnitRepo,
    workerRepo,
    executor,
    logger,
  })

  return {
    runtime,
    client,
    executor,
    config,
    start: () => runtime.start(),
    stop: (stopOptions?: WorkerRuntimeStopOptions) => runtime.stop(stopOptions),
  }
}

/**
 * Convenience launcher: wires the local runtime and immediately starts its
 * claim loop, returning the running handle.
 */
export async function runLocalWorker(options: LocalWorkerRuntimeOptions = {}): Promise<LocalWorkerRuntimeHandle> {
  const handle = await createLocalWorkerRuntime(options)
  await handle.start()
  return handle
}
