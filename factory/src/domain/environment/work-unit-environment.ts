/**
 * Pure work-unit-environment domain: descriptor vocabulary, lifecycle states,
 * error codes and the validation rules an environment descriptor must satisfy
 * before it is persisted or driven through its state machine.
 *
 * Authority: this TypeScript source is bundled into
 * `factory/runtime/factory-operational.mjs`; `factory/lib/work-unit-environment.mjs`
 * is a stateless compatibility facade re-exporting from that bundle.
 *
 * Domain purity: this module carries no `node:fs`, Git or AgentOS dependency.
 * The only `node:*` import is the pure path algebra (`node:path`) needed to
 * decide whether a path is canonical and absolute.
 */

import { isAbsolute, normalize, resolve } from 'node:path'

/** Ordered lifecycle states a work-unit environment can take. */
export const WORK_UNIT_ENVIRONMENT_STATES = Object.freeze([
  'provisioning',
  'active',
  'completed',
  'abandoned',
  'error',
  'removed',
] as const)

/** Lifecycle state of a work-unit environment. */
export type WorkUnitEnvironmentState = (typeof WORK_UNIT_ENVIRONMENT_STATES)[number]

/** Machine-readable validation error codes. */
export const WORK_UNIT_ENVIRONMENT_ERROR_CODES = Object.freeze({
  INVALID_ENVIRONMENT: 'INVALID_ENVIRONMENT',
  INVALID_SCHEMA_VERSION: 'INVALID_SCHEMA_VERSION',
  INVALID_ID: 'INVALID_ID',
  INVALID_UUID: 'INVALID_UUID',
  INVALID_PATH: 'INVALID_PATH',
  INVALID_REF: 'INVALID_REF',
  INVALID_SHA: 'INVALID_SHA',
  INVALID_INSTANT: 'INVALID_INSTANT',
  INVALID_STATE: 'INVALID_STATE',
} as const)

/** One of the machine-readable validation error codes. */
export type WorkUnitEnvironmentErrorCode =
  (typeof WORK_UNIT_ENVIRONMENT_ERROR_CODES)[keyof typeof WORK_UNIT_ENVIRONMENT_ERROR_CODES]

/**
 * A persisted work-unit environment descriptor. Optional fields are omitted —
 * never present as `undefined` — so the canonical JSON form is stable.
 */
export interface WorkUnitEnvironment {
  schemaVersion: '1'
  environmentId: string
  workUnitId: string
  workflowId?: string
  namespaceId: string
  parentCaseId?: string
  businessRef?: string
  businessType?: string
  repoRoot: string
  integrationBranch: string
  branch: string
  worktreePath: string
  baseCommit: string | null
  createdAt: string
  createdBy: string
  lifecycleState: WorkUnitEnvironmentState
}

/** Untyped descriptor input accepted by `validateWorkUnitEnvironment`. */
export type WorkUnitEnvironmentInput = Partial<WorkUnitEnvironment> & Record<string, unknown>

/** A validation failure: a machine code plus the JSON path that failed. */
export interface ValidationFailure {
  ok: false
  error: { code: WorkUnitEnvironmentErrorCode; path: string }
}

/** Result of a field-level validation. */
export type ValidationResult = { ok: true } | ValidationFailure

/** Result of a full environment-descriptor validation. */
export type EnvironmentValidationResult = { ok: true; environment: WorkUnitEnvironment } | ValidationFailure

const SAFE = /^[A-Za-z0-9](?:[A-Za-z0-9._-]{0,127})$/
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const SHA = /^(?:[0-9a-f]{40}|[0-9a-f]{64})$/i
const REF = /^(?!\/|.*(?:\.\.|@\{|\\|[ ~^:?*\[]|\/\/|\.$|\.lock(?:\/|$)))(?!.*\/$)[A-Za-z0-9._\/-]+$/
const STATES = new Set<string>(WORK_UNIT_ENVIRONMENT_STATES)

function fail(code: WorkUnitEnvironmentErrorCode, path: string): ValidationFailure {
  return { ok: false, error: { code, path } }
}

function safe(value: unknown, path: string, optional = false): ValidationResult {
  if (optional && value === undefined) return { ok: true }
  return typeof value === 'string' && SAFE.test(value)
    ? { ok: true }
    : fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_ID, path)
}

/** Validates that `value` is a canonical namespace UUID. */
export function validateNamespaceId(value: unknown, path = 'namespaceId'): ValidationResult {
  return typeof value === 'string' && UUID.test(value)
    ? { ok: true }
    : fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_UUID, path)
}

/** Validates that `value` is an absolute path in canonical (normalized, resolved) form. */
export function validateCanonicalAbsolutePath(value: unknown, path = 'path'): ValidationResult {
  if (typeof value !== 'string' || !isAbsolute(value) || normalize(value) !== value || resolve(value) !== value)
    return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_PATH, path)
  return { ok: true }
}

/** Validates that `value` is a safe Git ref name. */
export function validateGitRef(value: unknown, path = 'ref'): ValidationResult {
  return typeof value === 'string' && value.length <= 255 && REF.test(value)
    ? { ok: true }
    : fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_REF, path)
}

/** Accept only the canonical UTC millisecond ISO form emitted by Date#toISOString. */
export function validateIsoInstant(value: unknown, path = 'instant'): ValidationResult {
  if (typeof value !== 'string') return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_INSTANT, path)
  const milliseconds = Date.parse(value)
  if (!Number.isFinite(milliseconds)) return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_INSTANT, path)
  if (new Date(milliseconds).toISOString() !== value)
    return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_INSTANT, path)
  return { ok: true }
}

/**
 * Validates a full environment descriptor and returns its canonical normalized
 * form. Optional fields are included only when truthy, so the descriptor's JSON
 * shape is stable across callers.
 */
export function validateWorkUnitEnvironment(input: unknown): EnvironmentValidationResult {
  if (!input || typeof input !== 'object' || Array.isArray(input))
    return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_ENVIRONMENT, '$')
  const record = input as Record<string, unknown>
  if (record.schemaVersion !== '1')
    return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_SCHEMA_VERSION, 'schemaVersion')
  for (const field of ['environmentId', 'workUnitId', 'createdBy']) {
    const result = safe(record[field], field)
    if (!result.ok) return result
  }
  const workflow = safe(record.workflowId, 'workflowId', true)
  if (!workflow.ok) return workflow
  for (const field of ['businessRef', 'businessType']) {
    const result = safe(record[field], field, true)
    if (!result.ok) return result
  }
  const namespace = validateNamespaceId(record.namespaceId)
  if (!namespace.ok) return namespace
  if (record.parentCaseId !== undefined && !UUID.test(record.parentCaseId as string))
    return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_UUID, 'parentCaseId')
  for (const field of ['repoRoot', 'worktreePath']) {
    const result = validateCanonicalAbsolutePath(record[field], field)
    if (!result.ok) return result
  }
  if (record.repoRoot === record.worktreePath)
    return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_PATH, 'worktreePath')
  for (const field of ['integrationBranch', 'branch']) {
    const result = validateGitRef(record[field], field)
    if (!result.ok) return result
  }
  if (record.baseCommit !== null && !SHA.test((record.baseCommit as string) ?? ''))
    return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_SHA, 'baseCommit')
  if (record.baseCommit === null && record.lifecycleState !== 'provisioning')
    return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_SHA, 'baseCommit')
  const createdAt = validateIsoInstant(record.createdAt, 'createdAt')
  if (!createdAt.ok) return createdAt
  if (!STATES.has(record.lifecycleState as string))
    return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_STATE, 'lifecycleState')
  if (record.lifecycleState === 'active' && !record.parentCaseId)
    return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_STATE, 'parentCaseId')
  if (record.lifecycleState === 'provisioning' && record.parentCaseId)
    return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_STATE, 'parentCaseId')
  const environment: WorkUnitEnvironment = {
    schemaVersion: '1',
    environmentId: record.environmentId as string,
    workUnitId: record.workUnitId as string,
    ...(record.workflowId ? { workflowId: record.workflowId as string } : {}),
    namespaceId: record.namespaceId as string,
    ...(record.parentCaseId ? { parentCaseId: record.parentCaseId as string } : {}),
    ...(record.businessRef ? { businessRef: record.businessRef as string } : {}),
    ...(record.businessType ? { businessType: record.businessType as string } : {}),
    repoRoot: record.repoRoot as string,
    integrationBranch: record.integrationBranch as string,
    branch: record.branch as string,
    worktreePath: record.worktreePath as string,
    baseCommit: record.baseCommit as string | null,
    createdAt: record.createdAt as string,
    createdBy: record.createdBy as string,
    lifecycleState: record.lifecycleState as WorkUnitEnvironmentState,
  }
  return { ok: true, environment }
}
