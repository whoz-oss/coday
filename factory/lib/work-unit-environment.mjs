import { isAbsolute, normalize, resolve } from 'node:path'

export const WORK_UNIT_ENVIRONMENT_STATES = Object.freeze(['provisioning', 'active', 'completed', 'abandoned', 'error', 'removed'])
export const WORK_UNIT_ENVIRONMENT_ERROR_CODES = Object.freeze({ INVALID_ENVIRONMENT: 'INVALID_ENVIRONMENT', INVALID_SCHEMA_VERSION: 'INVALID_SCHEMA_VERSION', INVALID_ID: 'INVALID_ID', INVALID_UUID: 'INVALID_UUID', INVALID_PATH: 'INVALID_PATH', INVALID_REF: 'INVALID_REF', INVALID_SHA: 'INVALID_SHA', INVALID_INSTANT: 'INVALID_INSTANT', INVALID_STATE: 'INVALID_STATE' })
const SAFE = /^[A-Za-z0-9](?:[A-Za-z0-9._-]{0,127})$/
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const SHA = /^(?:[0-9a-f]{40}|[0-9a-f]{64})$/i
const REF = /^(?!\/|.*(?:\.\.|@\{|\\|[ ~^:?*\[]|\/\/|\.$|\.lock(?:\/|$)))(?!.*\/$)[A-Za-z0-9._\/-]+$/
const STATES = new Set(WORK_UNIT_ENVIRONMENT_STATES)
function fail(code, path) { return { ok: false, error: { code, path } } }
function safe(value, path, optional = false) { if (optional && value === undefined) return { ok: true }; return typeof value === 'string' && SAFE.test(value) ? { ok: true } : fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_ID, path) }
export function validateNamespaceId(value, path = 'namespaceId') { return typeof value === 'string' && UUID.test(value) ? { ok: true } : fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_UUID, path) }
export function validateCanonicalAbsolutePath(value, path = 'path') { if (typeof value !== 'string' || !isAbsolute(value) || normalize(value) !== value || resolve(value) !== value) return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_PATH, path); return { ok: true } }
export function validateGitRef(value, path = 'ref') { return typeof value === 'string' && value.length <= 255 && REF.test(value) ? { ok: true } : fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_REF, path) }
/** Accept only the canonical UTC millisecond ISO form emitted by Date#toISOString. */
export function validateIsoInstant(value, path = 'instant') {
  if (typeof value !== 'string') return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_INSTANT, path)
  const milliseconds = Date.parse(value)
  if (!Number.isFinite(milliseconds)) return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_INSTANT, path)
  if (new Date(milliseconds).toISOString() !== value) return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_INSTANT, path)
  return { ok: true }
}
export function validateWorkUnitEnvironment(input) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_ENVIRONMENT, '$')
  if (input.schemaVersion !== '1') return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_SCHEMA_VERSION, 'schemaVersion')
  for (const field of ['environmentId','workUnitId','createdBy']) { const result = safe(input[field], field); if (!result.ok) return result }
  for (const field of ['businessRef','businessType']) { const result = safe(input[field], field, true); if (!result.ok) return result }
  const namespace = validateNamespaceId(input.namespaceId)
  if (!namespace.ok) return namespace
  if (input.parentCaseId !== undefined && !UUID.test(input.parentCaseId)) return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_UUID, 'parentCaseId')
  for (const field of ['repoRoot','worktreePath']) { const result = validateCanonicalAbsolutePath(input[field], field); if (!result.ok) return result }
  if (input.repoRoot === input.worktreePath) return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_PATH, 'worktreePath')
  for (const field of ['integrationBranch','branch']) { const result = validateGitRef(input[field], field); if (!result.ok) return result }
  if (input.baseCommit !== null && !SHA.test(input.baseCommit ?? '')) return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_SHA, 'baseCommit')
  if (input.baseCommit === null && input.lifecycleState !== 'provisioning') return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_SHA, 'baseCommit')
  const createdAt = validateIsoInstant(input.createdAt, 'createdAt')
  if (!createdAt.ok) return createdAt
  if (!STATES.has(input.lifecycleState)) return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_STATE, 'lifecycleState')
  if (input.lifecycleState === 'active' && !input.parentCaseId) return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_STATE, 'parentCaseId')
  if (input.lifecycleState === 'provisioning' && input.parentCaseId) return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_STATE, 'parentCaseId')
  const environment = { schemaVersion:'1', environmentId:input.environmentId, workUnitId:input.workUnitId, namespaceId:input.namespaceId, ...(input.parentCaseId ? { parentCaseId:input.parentCaseId } : {}), ...(input.businessRef ? { businessRef:input.businessRef } : {}), ...(input.businessType ? { businessType:input.businessType } : {}), repoRoot:input.repoRoot, integrationBranch:input.integrationBranch, branch:input.branch, worktreePath:input.worktreePath, baseCommit:input.baseCommit, createdAt:input.createdAt, createdBy:input.createdBy, lifecycleState:input.lifecycleState }
  return { ok: true, environment }
}
