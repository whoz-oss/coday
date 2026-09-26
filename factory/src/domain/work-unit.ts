/**
 * Pure work-unit domain: identity, scheduling vocabulary, lifecycle states,
 * error codes and the state-machine rules a work unit must satisfy before it is
 * persisted or driven through its transitions (Jalon C1-T1a).
 *
 * The durable surface is the V6 `work_units` table extended by V7 (`priority`,
 * `not_before`, `attempt_count`). This module never touches that table: it is
 * the dependency-free vocabulary shared by the SQL adapter, the filesystem
 * reference and any future control-plane code.
 *
 * Domain purity: no `node:*` import, no Git, no AgentOS, no I/O. The only
 * inputs are plain values.
 */

/** Ordered lifecycle states a work unit can take (V6 `work_units.status` CHECK). */
export const WORK_UNIT_STATES = Object.freeze([
  'created',
  'assigned',
  'running',
  'completed',
  'failed',
  'cancelled',
] as const)

/** Lifecycle state of a work unit. */
export type WorkUnitState = (typeof WORK_UNIT_STATES)[number]

/** Terminal lifecycle states: no transition leaves them. */
export const WORK_UNIT_TERMINAL_STATES = Object.freeze(['completed', 'failed', 'cancelled'] as const)

/** Terminal lifecycle state of a work unit. */
export type WorkUnitTerminalState = (typeof WORK_UNIT_TERMINAL_STATES)[number]

/** Machine-readable error codes raised by work-unit persistence/validation. */
export const WORK_UNIT_ERROR_CODES = Object.freeze({
  INVALID_WORK_UNIT: 'INVALID_WORK_UNIT',
  INVALID_STATE: 'INVALID_STATE',
  INVALID_TRANSITION: 'INVALID_TRANSITION',
  REVISION_CONFLICT: 'REVISION_CONFLICT',
  NOT_FOUND: 'NOT_FOUND',
} as const)

/** One of the machine-readable work-unit error codes. */
export type WorkUnitErrorCode = (typeof WORK_UNIT_ERROR_CODES)[keyof typeof WORK_UNIT_ERROR_CODES]

/**
 * Allowed lifecycle transitions. `failed` is a terminal state: a retry is a new
 * work unit, the `attempt_count` only records how many attempts were made.
 */
export const WORK_UNIT_TRANSITIONS: Readonly<Record<WorkUnitState, readonly WorkUnitState[]>> = {
  created: ['assigned', 'cancelled'],
  assigned: ['running', 'created', 'cancelled', 'failed'],
  running: ['completed', 'failed', 'cancelled'],
  completed: [],
  failed: [],
  cancelled: [],
}

/** A persisted work unit, as stored in the V6/V7 `work_units` row. */
export interface WorkUnit {
  workUnitId: string
  unitType: string
  status: WorkUnitState
  revision: number
  priority: number
  notBefore: string | null
  attemptCount: number
  payload: Record<string, unknown>
  createdAt: string
  updatedAt: string
}

/**
 * Untyped create input: identity and type are mandatory, every scheduling /
 * payload field falls back to its V7 default.
 */
export type WorkUnitCreateInput = Omit<
  WorkUnit,
  'revision' | 'createdAt' | 'updatedAt' | 'status' | 'priority' | 'notBefore' | 'attemptCount' | 'payload'
> &
  Partial<Pick<WorkUnit, 'revision' | 'status' | 'priority' | 'notBefore' | 'attemptCount' | 'payload'>>

/** A validation failure: a machine code plus the JSON path that failed. */
export interface WorkUnitValidationFailure {
  ok: false
  error: { code: WorkUnitErrorCode; path: string }
}

/** Result of a full work-unit validation. */
export type WorkUnitValidationResult = { ok: true; workUnit: WorkUnit } | WorkUnitValidationFailure

const SAFE_ID = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/
const STATE_SET: ReadonlySet<string> = new Set(WORK_UNIT_STATES)
const TERMINAL_SET: ReadonlySet<string> = new Set(WORK_UNIT_TERMINAL_STATES)

function fail(code: WorkUnitErrorCode, path: string): WorkUnitValidationFailure {
  return { ok: false, error: { code, path } }
}

function isPlainRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/** Accept only the canonical UTC millisecond ISO form emitted by `Date#toISOString`. */
export function isWorkUnitIsoInstant(value: unknown): value is string {
  if (typeof value !== 'string') return false
  const milliseconds = Date.parse(value)
  return Number.isFinite(milliseconds) && new Date(milliseconds).toISOString() === value
}

/** Narrows an unknown value to a valid work-unit lifecycle state. */
export function isWorkUnitState(value: unknown): value is WorkUnitState {
  return typeof value === 'string' && STATE_SET.has(value)
}

/** Whether `state` is a terminal work-unit lifecycle state. */
export function isTerminalWorkUnitState(state: WorkUnitState): state is WorkUnitTerminalState {
  return TERMINAL_SET.has(state)
}

/** Whether the `from -> to` lifecycle transition is allowed by the state machine. */
export function canTransitionWorkUnit(from: WorkUnitState, to: WorkUnitState): boolean {
  return WORK_UNIT_TRANSITIONS[from].includes(to)
}

/**
 * Validates a full work-unit record and returns its canonical normalized form.
 * Every field is checked, so a value that passed here can be written verbatim.
 */
export function validateWorkUnit(input: unknown): WorkUnitValidationResult {
  if (!isPlainRecord(input)) return fail(WORK_UNIT_ERROR_CODES.INVALID_WORK_UNIT, '$')
  if (typeof input.workUnitId !== 'string' || !SAFE_ID.test(input.workUnitId))
    return fail(WORK_UNIT_ERROR_CODES.INVALID_WORK_UNIT, 'workUnitId')
  if (typeof input.unitType !== 'string' || !SAFE_ID.test(input.unitType))
    return fail(WORK_UNIT_ERROR_CODES.INVALID_WORK_UNIT, 'unitType')
  if (!isWorkUnitState(input.status)) return fail(WORK_UNIT_ERROR_CODES.INVALID_STATE, 'status')
  if (typeof input.revision !== 'number' || !Number.isSafeInteger(input.revision) || input.revision < 1)
    return fail(WORK_UNIT_ERROR_CODES.INVALID_WORK_UNIT, 'revision')
  if (typeof input.priority !== 'number' || !Number.isSafeInteger(input.priority))
    return fail(WORK_UNIT_ERROR_CODES.INVALID_WORK_UNIT, 'priority')
  if (input.notBefore !== null && !isWorkUnitIsoInstant(input.notBefore))
    return fail(WORK_UNIT_ERROR_CODES.INVALID_WORK_UNIT, 'notBefore')
  if (typeof input.attemptCount !== 'number' || !Number.isSafeInteger(input.attemptCount) || input.attemptCount < 0)
    return fail(WORK_UNIT_ERROR_CODES.INVALID_WORK_UNIT, 'attemptCount')
  if (!isPlainRecord(input.payload)) return fail(WORK_UNIT_ERROR_CODES.INVALID_WORK_UNIT, 'payload')
  if (!isWorkUnitIsoInstant(input.createdAt)) return fail(WORK_UNIT_ERROR_CODES.INVALID_WORK_UNIT, 'createdAt')
  if (!isWorkUnitIsoInstant(input.updatedAt)) return fail(WORK_UNIT_ERROR_CODES.INVALID_WORK_UNIT, 'updatedAt')

  const workUnit: WorkUnit = {
    workUnitId: input.workUnitId,
    unitType: input.unitType,
    status: input.status,
    revision: input.revision,
    priority: input.priority,
    notBefore: input.notBefore,
    attemptCount: input.attemptCount,
    payload: input.payload,
    createdAt: input.createdAt,
    updatedAt: input.updatedAt,
  }
  return { ok: true, workUnit }
}
