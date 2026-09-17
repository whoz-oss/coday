import { createHash } from 'node:crypto'

export const WORKFLOW_DEFINITION_SCHEMA_VERSION = '1'
// Responsibility identifies the executor, never the produced artifact:
// human = work performed by a person; agent = all agent work, including source edits;
// code = deterministic execution owned by Factory (for example builds, tests, scans, oracles).
export const WORKFLOW_DEFINITION_RESPONSIBILITIES = Object.freeze(['human', 'agent', 'code'])
export const WORKFLOW_DEFINITION_ERROR_CODES = Object.freeze({
  INVALID_DEFINITION: 'INVALID_DEFINITION',
  INVALID_SCHEMA_VERSION: 'INVALID_SCHEMA_VERSION',
  INVALID_VALUE: 'INVALID_VALUE',
  DUPLICATE_STEP_ID: 'DUPLICATE_STEP_ID',
  MISSING_DEPENDENCY: 'MISSING_DEPENDENCY',
  SELF_DEPENDENCY: 'SELF_DEPENDENCY',
  DEPENDENCY_CYCLE: 'DEPENDENCY_CYCLE',
  INVALID_RESPONSIBILITY: 'INVALID_RESPONSIBILITY',
})

const SAFE_ID = /^[A-Za-z0-9](?:[A-Za-z0-9._-]{0,127})$/
const SEMVER = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/
const DEFINITION_FIELDS = new Set(['schemaVersion', 'workflowType', 'version', 'title', 'steps'])
const STEP_FIELDS = new Set(['id', 'name', 'responsibility', 'dependsOn'])
const RESPONSIBILITY_FIELDS = new Set(['kind', 'name'])
const KINDS = new Set(WORKFLOW_DEFINITION_RESPONSIBILITIES)

function failure(code, path, details = {}) { return { ok: false, error: { code, path, details } } }
function text(value, path, { safe = false, maximum = 256 } = {}) {
  if (typeof value !== 'string' || !value.trim() || value.length > maximum || (safe && !SAFE_ID.test(value))) return failure(WORKFLOW_DEFINITION_ERROR_CODES.INVALID_VALUE, path)
  return { ok: true, value }
}

export function validateWorkflowDefinition(input) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) return failure(WORKFLOW_DEFINITION_ERROR_CODES.INVALID_DEFINITION, '$')
  if (Object.keys(input).some((field) => !DEFINITION_FIELDS.has(field))) return failure(WORKFLOW_DEFINITION_ERROR_CODES.INVALID_VALUE, '$', { reason: 'unknown_field' })
  if (input.schemaVersion !== WORKFLOW_DEFINITION_SCHEMA_VERSION) return failure(WORKFLOW_DEFINITION_ERROR_CODES.INVALID_SCHEMA_VERSION, 'schemaVersion')
  const type = text(input.workflowType, 'workflowType', { safe: true }); if (!type.ok) return type
  if (typeof input.version !== 'string' || !SEMVER.test(input.version)) return failure(WORKFLOW_DEFINITION_ERROR_CODES.INVALID_VALUE, 'version')
  const title = text(input.title, 'title'); if (!title.ok) return title
  if (!Array.isArray(input.steps) || input.steps.length === 0 || input.steps.length > 500) return failure(WORKFLOW_DEFINITION_ERROR_CODES.INVALID_VALUE, 'steps')
  const ids = new Set(), steps = []
  for (let index = 0; index < input.steps.length; index++) {
    const raw = input.steps[index], base = `steps[${index}]`
    if (!raw || typeof raw !== 'object' || Array.isArray(raw) || Object.keys(raw).some((field) => !STEP_FIELDS.has(field))) return failure(WORKFLOW_DEFINITION_ERROR_CODES.INVALID_VALUE, base)
    const id = text(raw.id, `${base}.id`, { safe: true }); if (!id.ok) return id
    if (ids.has(raw.id)) return failure(WORKFLOW_DEFINITION_ERROR_CODES.DUPLICATE_STEP_ID, `${base}.id`, { stepId: raw.id }); ids.add(raw.id)
    const name = text(raw.name, `${base}.name`); if (!name.ok) return name
    const responsibility = raw.responsibility
    if (!responsibility || typeof responsibility !== 'object' || Array.isArray(responsibility) || Object.keys(responsibility).some((field) => !RESPONSIBILITY_FIELDS.has(field)) || !KINDS.has(responsibility.kind)) return failure(WORKFLOW_DEFINITION_ERROR_CODES.INVALID_RESPONSIBILITY, `${base}.responsibility`)
    const responsibilityName = text(responsibility.name, `${base}.responsibility.name`); if (!responsibilityName.ok) return responsibilityName
    if (!Array.isArray(raw.dependsOn)) return failure(WORKFLOW_DEFINITION_ERROR_CODES.INVALID_VALUE, `${base}.dependsOn`)
    const dependencies = [], seen = new Set()
    for (let dependencyIndex = 0; dependencyIndex < raw.dependsOn.length; dependencyIndex++) {
      const dependency = text(raw.dependsOn[dependencyIndex], `${base}.dependsOn[${dependencyIndex}]`, { safe: true }); if (!dependency.ok) return dependency
      if (seen.has(dependency.value)) return failure(WORKFLOW_DEFINITION_ERROR_CODES.INVALID_VALUE, `${base}.dependsOn[${dependencyIndex}]`, { reason: 'duplicate_dependency' })
      seen.add(dependency.value); dependencies.push(dependency.value)
    }
    steps.push({ id: raw.id, name: raw.name, responsibility: { kind: responsibility.kind, name: responsibility.name }, dependsOn: dependencies })
  }
  for (const step of steps) for (const dependency of step.dependsOn) {
    if (dependency === step.id) return failure(WORKFLOW_DEFINITION_ERROR_CODES.SELF_DEPENDENCY, `steps.${step.id}.dependsOn`)
    if (!ids.has(dependency)) return failure(WORKFLOW_DEFINITION_ERROR_CODES.MISSING_DEPENDENCY, `steps.${step.id}.dependsOn`, { target: dependency })
  }
  const graph = new Map(steps.map((step) => [step.id, step.dependsOn])), visiting = new Set(), visited = new Set()
  function cyclic(id) { if (visiting.has(id)) return true; if (visited.has(id)) return false; visiting.add(id); for (const dependency of graph.get(id)) if (cyclic(dependency)) return true; visiting.delete(id); visited.add(id); return false }
  for (const step of steps) if (cyclic(step.id)) return failure(WORKFLOW_DEFINITION_ERROR_CODES.DEPENDENCY_CYCLE, 'steps')
  return { ok: true, definition: { schemaVersion: input.schemaVersion, workflowType: input.workflowType, version: input.version, title: input.title, steps } }
}

export function canonicalizeWorkflowDefinition(definition) {
  function canonical(value) { if (Array.isArray(value)) return value.map(canonical); if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map((key) => [key, canonical(value[key])])); return value }
  return JSON.stringify(canonical(definition))
}
export function hashWorkflowDefinition(definition) { return createHash('sha256').update(canonicalizeWorkflowDefinition(definition), 'utf8').digest('hex') }
