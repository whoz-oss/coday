/**
 * Pure worker domain: identity, liveness vocabulary, lifecycle states, error
 * codes and the state-machine rules a worker node must satisfy before it is
 * persisted or driven through its transitions (Jalon C1-T1a).
 *
 * The durable surface is the V6 `workers` table extended by V7
 * (`last_heartbeat_at`, `protocol_version`, `capabilities`). This module never
 * touches that table: it is the dependency-free vocabulary shared by the SQL
 * adapter and any future control-plane code.
 *
 * Domain purity: no `node:*` import, no Git, no AgentOS, no I/O.
 */

/** Ordered lifecycle states a worker can take (V6 `workers.status` CHECK). */
export const WORKER_STATES = Object.freeze(['offline', 'idle', 'busy', 'maintenance'] as const)

/** Lifecycle state of a worker. */
export type WorkerState = (typeof WORKER_STATES)[number]

/** Machine-readable error codes raised by worker persistence/validation. */
export const WORKER_ERROR_CODES = Object.freeze({
  INVALID_WORKER: 'INVALID_WORKER',
  INVALID_STATE: 'INVALID_STATE',
  INVALID_TRANSITION: 'INVALID_TRANSITION',
  REVISION_CONFLICT: 'REVISION_CONFLICT',
  NOT_FOUND: 'NOT_FOUND',
} as const)

/** One of the machine-readable worker error codes. */
export type WorkerErrorCode = (typeof WORKER_ERROR_CODES)[keyof typeof WORKER_ERROR_CODES]

/**
 * Allowed lifecycle transitions. A worker may always be drained to `offline`
 * (crash / shutdown) and taken into `maintenance`; `busy` returns to `idle`
 * when its work unit completes.
 */
export const WORKER_TRANSITIONS: Readonly<Record<WorkerState, readonly WorkerState[]>> = {
  offline: ['idle', 'maintenance'],
  idle: ['busy', 'offline', 'maintenance'],
  busy: ['idle', 'offline', 'maintenance'],
  maintenance: ['offline', 'idle'],
}

/** A persisted worker, as stored in the V6/V7 `workers` row. */
export interface Worker {
  workerId: string
  workerType: string
  status: WorkerState
  revision: number
  lastHeartbeatAt: string | null
  protocolVersion: string | null
  capabilities: string[]
  payload: Record<string, unknown>
  createdAt: string
  updatedAt: string
}

/**
 * Untyped create input: identity and type are mandatory, every liveness /
 * capability field falls back to its V7 default.
 */
export type WorkerCreateInput = Omit<
  Worker,
  'revision' | 'createdAt' | 'updatedAt' | 'status' | 'lastHeartbeatAt' | 'protocolVersion' | 'capabilities' | 'payload'
> &
  Partial<Pick<Worker, 'revision' | 'status' | 'lastHeartbeatAt' | 'protocolVersion' | 'capabilities' | 'payload'>>

/** A validation failure: a machine code plus the JSON path that failed. */
export interface WorkerValidationFailure {
  ok: false
  error: { code: WorkerErrorCode; path: string }
}

/** Result of a full worker validation. */
export type WorkerValidationResult = { ok: true; worker: Worker } | WorkerValidationFailure

const SAFE_ID = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/
const STATE_SET: ReadonlySet<string> = new Set(WORKER_STATES)

function fail(code: WorkerErrorCode, path: string): WorkerValidationFailure {
  return { ok: false, error: { code, path } }
}

function isPlainRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/** Accept only the canonical UTC millisecond ISO form emitted by `Date#toISOString`. */
export function isWorkerIsoInstant(value: unknown): value is string {
  if (typeof value !== 'string') return false
  const milliseconds = Date.parse(value)
  return Number.isFinite(milliseconds) && new Date(milliseconds).toISOString() === value
}

/** Narrows an unknown value to a valid worker lifecycle state. */
export function isWorkerState(value: unknown): value is WorkerState {
  return typeof value === 'string' && STATE_SET.has(value)
}

/** Whether the `from -> to` lifecycle transition is allowed by the state machine. */
export function canTransitionWorker(from: WorkerState, to: WorkerState): boolean {
  return WORKER_TRANSITIONS[from].includes(to)
}

/**
 * Validates a full worker record and returns its canonical normalized form.
 * Every field is checked, so a value that passed here can be written verbatim.
 */
export function validateWorker(input: unknown): WorkerValidationResult {
  if (!isPlainRecord(input)) return fail(WORKER_ERROR_CODES.INVALID_WORKER, '$')
  if (typeof input.workerId !== 'string' || !SAFE_ID.test(input.workerId))
    return fail(WORKER_ERROR_CODES.INVALID_WORKER, 'workerId')
  if (typeof input.workerType !== 'string' || !SAFE_ID.test(input.workerType))
    return fail(WORKER_ERROR_CODES.INVALID_WORKER, 'workerType')
  if (!isWorkerState(input.status)) return fail(WORKER_ERROR_CODES.INVALID_STATE, 'status')
  if (typeof input.revision !== 'number' || !Number.isSafeInteger(input.revision) || input.revision < 1)
    return fail(WORKER_ERROR_CODES.INVALID_WORKER, 'revision')
  if (input.lastHeartbeatAt !== null && !isWorkerIsoInstant(input.lastHeartbeatAt))
    return fail(WORKER_ERROR_CODES.INVALID_WORKER, 'lastHeartbeatAt')
  if (input.protocolVersion !== null && typeof input.protocolVersion !== 'string')
    return fail(WORKER_ERROR_CODES.INVALID_WORKER, 'protocolVersion')
  if (!Array.isArray(input.capabilities) || !input.capabilities.every((entry) => typeof entry === 'string'))
    return fail(WORKER_ERROR_CODES.INVALID_WORKER, 'capabilities')
  if (!isPlainRecord(input.payload)) return fail(WORKER_ERROR_CODES.INVALID_WORKER, 'payload')
  if (!isWorkerIsoInstant(input.createdAt)) return fail(WORKER_ERROR_CODES.INVALID_WORKER, 'createdAt')
  if (!isWorkerIsoInstant(input.updatedAt)) return fail(WORKER_ERROR_CODES.INVALID_WORKER, 'updatedAt')

  const worker: Worker = {
    workerId: input.workerId,
    workerType: input.workerType,
    status: input.status,
    revision: input.revision,
    lastHeartbeatAt: input.lastHeartbeatAt,
    protocolVersion: input.protocolVersion,
    capabilities: [...input.capabilities],
    payload: input.payload,
    createdAt: input.createdAt,
    updatedAt: input.updatedAt,
  }
  return { ok: true, worker }
}
