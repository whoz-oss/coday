import { createHash } from 'node:crypto'

export const WORKFLOW_STATUSES = Object.freeze([
  'pending', 'ready', 'running', 'waiting_human', 'blocked',
  'completed', 'failed', 'cancelled',
])
export const WORKFLOW_RESPONSIBILITY_KINDS = Object.freeze(['human', 'agent', 'code'])

export const WORKFLOW_PROJECTION_LIMITS = Object.freeze({
  id: 128, text: 256, description: 4096, steps: 500, dependenciesPerStep: 100, actorName: 256,
})

export const WORKFLOW_PROJECTION_ERROR_CODES = Object.freeze({
  INVALID_PROJECTION: 'INVALID_PROJECTION', INVALID_SCHEMA_VERSION: 'INVALID_SCHEMA_VERSION',
  INVALID_ID: 'INVALID_ID', INVALID_VALUE: 'INVALID_VALUE', INVALID_STATUS: 'INVALID_STATUS',
  EXCESSIVE_SIZE: 'EXCESSIVE_SIZE', DUPLICATE_STEP_ID: 'DUPLICATE_STEP_ID',
  MISSING_DEPENDENCY: 'MISSING_DEPENDENCY', SELF_DEPENDENCY: 'SELF_DEPENDENCY',
  DEPENDENCY_CYCLE: 'DEPENDENCY_CYCLE',
})

const SAFE_ID = /^[A-Za-z0-9](?:[A-Za-z0-9._-]{0,127})$/
const STATUS_SET = new Set(WORKFLOW_STATUSES)
const ACTOR_KIND_SET = new Set(WORKFLOW_RESPONSIBILITY_KINDS)
const PROJECTION_FIELDS = new Set(['schemaVersion', 'workflowId', 'workflowType', 'title', 'status', 'expectedRevision', 'steps'])
const STEP_V1_FIELDS = new Set(['id', 'name', 'status', 'description', 'dependsOn'])
const STEP_V2_FIELDS = new Set([...STEP_V1_FIELDS, 'responsibility'])
const RESPONSIBILITY_FIELDS = new Set(['kind', 'name'])

function failure(code, path, details = {}) { return { ok: false, error: { code, path, details } } }
function boundedString(value, maximum, path, { safe = false, optional = false } = {}) {
  if (optional && value === undefined) return { ok: true, value: undefined }
  if (typeof value !== 'string' || !value.trim()) return failure(safe ? WORKFLOW_PROJECTION_ERROR_CODES.INVALID_ID : WORKFLOW_PROJECTION_ERROR_CODES.INVALID_VALUE, path)
  if (value.length > maximum) return failure(WORKFLOW_PROJECTION_ERROR_CODES.EXCESSIVE_SIZE, path, { maximum })
  if (safe && !SAFE_ID.test(value)) return failure(WORKFLOW_PROJECTION_ERROR_CODES.INVALID_ID, path)
  return { ok: true, value }
}

export function validateWorkflowProjectionId(value, path = 'id') {
  return boundedString(value, WORKFLOW_PROJECTION_LIMITS.id, path, { safe: true })
}

/** Validate and normalize a WorkflowProjection v1 or v2 command. */
export function validateWorkflowProjection(input) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) return failure(WORKFLOW_PROJECTION_ERROR_CODES.INVALID_PROJECTION, '$')
  if (input.schemaVersion !== '1' && input.schemaVersion !== '2') return failure(WORKFLOW_PROJECTION_ERROR_CODES.INVALID_SCHEMA_VERSION, 'schemaVersion')
  if (Object.keys(input).some((key) => !PROJECTION_FIELDS.has(key))) return failure(WORKFLOW_PROJECTION_ERROR_CODES.INVALID_VALUE, '$', { reason: 'unknown_field' })
  const schemaVersion = input.schemaVersion
  for (const [field, limit, safe] of [['workflowId', WORKFLOW_PROJECTION_LIMITS.id, true], ['workflowType', WORKFLOW_PROJECTION_LIMITS.text, false], ['title', WORKFLOW_PROJECTION_LIMITS.text, false]]) {
    const result = boundedString(input[field], limit, field, { safe })
    if (!result.ok) return result
  }
  if (!STATUS_SET.has(input.status)) return failure(WORKFLOW_PROJECTION_ERROR_CODES.INVALID_STATUS, 'status')
  if (input.expectedRevision !== undefined && (!Number.isSafeInteger(input.expectedRevision) || input.expectedRevision < 0)) return failure(WORKFLOW_PROJECTION_ERROR_CODES.INVALID_VALUE, 'expectedRevision')
  if (!Array.isArray(input.steps)) return failure(WORKFLOW_PROJECTION_ERROR_CODES.INVALID_VALUE, 'steps')
  if (input.steps.length > WORKFLOW_PROJECTION_LIMITS.steps) return failure(WORKFLOW_PROJECTION_ERROR_CODES.EXCESSIVE_SIZE, 'steps', { maximum: WORKFLOW_PROJECTION_LIMITS.steps })

  const steps = []
  const ids = new Set()
  for (let index = 0; index < input.steps.length; index++) {
    const raw = input.steps[index]
    const base = `steps[${index}]`
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return failure(WORKFLOW_PROJECTION_ERROR_CODES.INVALID_VALUE, base)
    const allowed = schemaVersion === '2' ? STEP_V2_FIELDS : STEP_V1_FIELDS
    if (Object.keys(raw).some((key) => !allowed.has(key))) return failure(WORKFLOW_PROJECTION_ERROR_CODES.INVALID_VALUE, base, { reason: 'unknown_field' })
    const id = boundedString(raw.id, WORKFLOW_PROJECTION_LIMITS.id, `${base}.id`, { safe: true }); if (!id.ok) return id
    if (ids.has(raw.id)) return failure(WORKFLOW_PROJECTION_ERROR_CODES.DUPLICATE_STEP_ID, `${base}.id`, { stepId: raw.id }); ids.add(raw.id)
    const name = boundedString(raw.name, WORKFLOW_PROJECTION_LIMITS.text, `${base}.name`); if (!name.ok) return name
    if (!STATUS_SET.has(raw.status)) return failure(WORKFLOW_PROJECTION_ERROR_CODES.INVALID_STATUS, `${base}.status`)
    const description = boundedString(raw.description, WORKFLOW_PROJECTION_LIMITS.description, `${base}.description`, { optional: true }); if (!description.ok) return description
    if (raw.dependsOn !== undefined && !Array.isArray(raw.dependsOn)) return failure(WORKFLOW_PROJECTION_ERROR_CODES.INVALID_VALUE, `${base}.dependsOn`)
    const dependencies = raw.dependsOn ?? []
    if (dependencies.length > WORKFLOW_PROJECTION_LIMITS.dependenciesPerStep) return failure(WORKFLOW_PROJECTION_ERROR_CODES.EXCESSIVE_SIZE, `${base}.dependsOn`, { maximum: WORKFLOW_PROJECTION_LIMITS.dependenciesPerStep })
    const seen = new Set()
    for (let i = 0; i < dependencies.length; i++) {
      const dependency = boundedString(dependencies[i], WORKFLOW_PROJECTION_LIMITS.id, `${base}.dependsOn[${i}]`, { safe: true }); if (!dependency.ok) return dependency
      if (seen.has(dependency.value)) return failure(WORKFLOW_PROJECTION_ERROR_CODES.INVALID_VALUE, `${base}.dependsOn[${i}]`, { reason: 'duplicate_dependency' }); seen.add(dependency.value)
    }
    let responsibility
    if (schemaVersion === '2') {
      const actor = raw.responsibility
      if (!actor || typeof actor !== 'object' || Array.isArray(actor)) return failure(WORKFLOW_PROJECTION_ERROR_CODES.INVALID_VALUE, `${base}.responsibility`)
      if (Object.keys(actor).some((key) => !RESPONSIBILITY_FIELDS.has(key))) return failure(WORKFLOW_PROJECTION_ERROR_CODES.INVALID_VALUE, `${base}.responsibility`, { reason: 'unknown_field' })
      if (!ACTOR_KIND_SET.has(actor.kind)) return failure(WORKFLOW_PROJECTION_ERROR_CODES.INVALID_VALUE, `${base}.responsibility.kind`)
      const actorName = boundedString(actor.name, WORKFLOW_PROJECTION_LIMITS.actorName, `${base}.responsibility.name`, { optional: true }); if (!actorName.ok) return actorName
      responsibility = { kind: actor.kind, ...(actorName.value === undefined ? {} : { name: actorName.value }) }
    }
    steps.push({ id: raw.id, name: raw.name, status: raw.status, ...(raw.description === undefined ? {} : { description: raw.description }), dependsOn: [...dependencies], ...(responsibility ? { responsibility } : {}) })
  }
  for (const step of steps) for (const target of step.dependsOn) {
    if (target === step.id) return failure(WORKFLOW_PROJECTION_ERROR_CODES.SELF_DEPENDENCY, `steps.${step.id}.dependsOn`, { stepId: step.id })
    if (!ids.has(target)) return failure(WORKFLOW_PROJECTION_ERROR_CODES.MISSING_DEPENDENCY, `steps.${step.id}.dependsOn`, { stepId: step.id, target })
  }
  const graph = new Map(steps.map((step) => [step.id, step.dependsOn])), visiting = new Set(), visited = new Set()
  function hasCycle(id) { if (visiting.has(id)) return true; if (visited.has(id)) return false; visiting.add(id); for (const target of graph.get(id)) if (hasCycle(target)) return true; visiting.delete(id); visited.add(id); return false }
  for (const step of steps) if (hasCycle(step.id)) return failure(WORKFLOW_PROJECTION_ERROR_CODES.DEPENDENCY_CYCLE, 'steps', { stepId: step.id })
  return { ok: true, projection: { schemaVersion, workflowId: input.workflowId, workflowType: input.workflowType, title: input.title, status: input.status, steps }, expectedRevision: input.expectedRevision }
}

export function canonicalizeWorkflowProjection(projection) {
  function canonical(value) { if (Array.isArray(value)) return value.map(canonical); if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map((key) => [key, canonical(value[key])])); return value }
  return JSON.stringify(canonical(projection))
}
export function hashWorkflowProjection(projection) { return createHash('sha256').update(canonicalizeWorkflowProjection(projection), 'utf8').digest('hex') }
