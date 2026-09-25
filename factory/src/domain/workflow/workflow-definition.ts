import { createHash } from 'node:crypto'

/**
 * Pure workflow definition domain: schema validation, canonicalization and hash.
 *
 * Authority: this TypeScript source is bundled into
 * `factory/runtime/factory-operational.mjs`; `factory/lib/workflow-definition.mjs`
 * is a stateless compatibility facade re-exporting from that bundle.
 *
 * Domain purity: this module must not import `node:fs`, HTTP clients, AgentOS or
 * a Git CLI. Only `node:crypto` is allowed.
 */

export const WORKFLOW_DEFINITION_SCHEMA_VERSION = '1' as const

// Responsibility identifies the executor, never the produced artifact:
// human = work performed by a person; agent = all agent work, including source edits;
// code = deterministic execution owned by Factory (for example builds, tests, scans, oracles).
export const WORKFLOW_DEFINITION_RESPONSIBILITIES = Object.freeze(['human', 'agent', 'code'] as const)

export type ResponsibilityKind = (typeof WORKFLOW_DEFINITION_RESPONSIBILITIES)[number]

export const WORKFLOW_DEFINITION_ERROR_CODES = Object.freeze({
  INVALID_DEFINITION: 'INVALID_DEFINITION',
  INVALID_SCHEMA_VERSION: 'INVALID_SCHEMA_VERSION',
  INVALID_VALUE: 'INVALID_VALUE',
  DUPLICATE_STEP_ID: 'DUPLICATE_STEP_ID',
  MISSING_DEPENDENCY: 'MISSING_DEPENDENCY',
  SELF_DEPENDENCY: 'SELF_DEPENDENCY',
  DEPENDENCY_CYCLE: 'DEPENDENCY_CYCLE',
  INVALID_RESPONSIBILITY: 'INVALID_RESPONSIBILITY',
} as const)

export type WorkflowDefinitionErrorCode =
  (typeof WORKFLOW_DEFINITION_ERROR_CODES)[keyof typeof WORKFLOW_DEFINITION_ERROR_CODES]

export interface WorkflowStepResponsibility {
  kind: ResponsibilityKind
  name: string
}

export interface WorkflowStepDefinition {
  id: string
  name: string
  responsibility: WorkflowStepResponsibility
  dependsOn: string[]
}

export interface TrustedExecutionConfig {
  allowedPaths: string[]
}

export interface WorkflowDefinition {
  schemaVersion: string
  workflowType: string
  version: string
  title: string
  trustedExecution?: TrustedExecutionConfig
  steps: WorkflowStepDefinition[]
}

export interface WorkflowDefinitionError {
  code: WorkflowDefinitionErrorCode
  path: string
  details: Record<string, unknown>
}

export type ValidateWorkflowDefinitionResult =
  | { ok: true; definition: WorkflowDefinition }
  | { ok: false; error: WorkflowDefinitionError }

const SAFE_ID = /^[A-Za-z0-9](?:[A-Za-z0-9._-]{0,127})$/
const SEMVER = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/
const DEFINITION_FIELDS = new Set(['schemaVersion', 'workflowType', 'version', 'title', 'trustedExecution', 'steps'])
const TRUSTED_EXECUTION_FIELDS = new Set(['allowedPaths'])
const STEP_FIELDS = new Set(['id', 'name', 'responsibility', 'dependsOn'])
const RESPONSIBILITY_FIELDS = new Set(['kind', 'name'])
const KINDS = new Set<string>(WORKFLOW_DEFINITION_RESPONSIBILITIES)

type ValidationFailure = { ok: false; error: WorkflowDefinitionError }
type TextResult = { ok: true; value: string } | ValidationFailure

function failure(
  code: WorkflowDefinitionErrorCode,
  path: string,
  details: Record<string, unknown> = {}
): ValidationFailure {
  return { ok: false, error: { code, path, details } }
}

function text(value: unknown, path: string, options: { safe?: boolean; maximum?: number } = {}): TextResult {
  const { safe = false, maximum = 256 } = options
  if (typeof value !== 'string' || !value.trim() || value.length > maximum || (safe && !SAFE_ID.test(value)))
    return failure(WORKFLOW_DEFINITION_ERROR_CODES.INVALID_VALUE, path)
  return { ok: true, value }
}

export function validateWorkflowDefinition(input: unknown): ValidateWorkflowDefinitionResult {
  if (!input || typeof input !== 'object' || Array.isArray(input))
    return failure(WORKFLOW_DEFINITION_ERROR_CODES.INVALID_DEFINITION, '$')
  const record = input as Record<string, unknown>
  if (Object.keys(record).some((field) => !DEFINITION_FIELDS.has(field)))
    return failure(WORKFLOW_DEFINITION_ERROR_CODES.INVALID_VALUE, '$', { reason: 'unknown_field' })
  if (record.schemaVersion !== WORKFLOW_DEFINITION_SCHEMA_VERSION)
    return failure(WORKFLOW_DEFINITION_ERROR_CODES.INVALID_SCHEMA_VERSION, 'schemaVersion')
  const type = text(record.workflowType, 'workflowType', { safe: true })
  if (!type.ok) return type
  const version = record.version
  if (typeof version !== 'string' || !SEMVER.test(version))
    return failure(WORKFLOW_DEFINITION_ERROR_CODES.INVALID_VALUE, 'version')
  const title = text(record.title, 'title')
  if (!title.ok) return title
  let trustedExecution: TrustedExecutionConfig | undefined
  if (record.trustedExecution !== undefined) {
    const rawTrusted = record.trustedExecution
    if (
      !rawTrusted ||
      typeof rawTrusted !== 'object' ||
      Array.isArray(rawTrusted) ||
      Object.keys(rawTrusted).some((field) => !TRUSTED_EXECUTION_FIELDS.has(field)) ||
      !Array.isArray((rawTrusted as Record<string, unknown>).allowedPaths) ||
      ((rawTrusted as Record<string, unknown>).allowedPaths as unknown[]).length === 0
    )
      return failure(WORKFLOW_DEFINITION_ERROR_CODES.INVALID_VALUE, 'trustedExecution')
    const allowedPaths: string[] = []
    const rawAllowedPaths = (rawTrusted as Record<string, unknown>).allowedPaths as unknown[]
    for (let index = 0; index < rawAllowedPaths.length; index++) {
      const path = rawAllowedPaths[index]
      if (
        typeof path !== 'string' ||
        !path ||
        path.startsWith('/') ||
        path.includes('\\') ||
        path.split('/').includes('..') ||
        path.includes('\0')
      )
        return failure(WORKFLOW_DEFINITION_ERROR_CODES.INVALID_VALUE, `trustedExecution.allowedPaths[${index}]`)
      allowedPaths.push(path)
    }
    trustedExecution = { allowedPaths }
  }
  if (!Array.isArray(record.steps) || record.steps.length === 0 || record.steps.length > 500)
    return failure(WORKFLOW_DEFINITION_ERROR_CODES.INVALID_VALUE, 'steps')
  const ids = new Set<string>()
  const steps: WorkflowStepDefinition[] = []
  const rawSteps = record.steps as unknown[]
  for (let index = 0; index < rawSteps.length; index++) {
    const raw = rawSteps[index]
    const base = `steps[${index}]`
    if (
      !raw ||
      typeof raw !== 'object' ||
      Array.isArray(raw) ||
      Object.keys(raw).some((field) => !STEP_FIELDS.has(field))
    )
      return failure(WORKFLOW_DEFINITION_ERROR_CODES.INVALID_VALUE, base)
    const step = raw as Record<string, unknown>
    const id = text(step.id, `${base}.id`, { safe: true })
    if (!id.ok) return id
    if (ids.has(id.value))
      return failure(WORKFLOW_DEFINITION_ERROR_CODES.DUPLICATE_STEP_ID, `${base}.id`, { stepId: id.value })
    ids.add(id.value)
    const name = text(step.name, `${base}.name`)
    if (!name.ok) return name
    const responsibility = step.responsibility
    if (
      !responsibility ||
      typeof responsibility !== 'object' ||
      Array.isArray(responsibility) ||
      Object.keys(responsibility).some((field) => !RESPONSIBILITY_FIELDS.has(field)) ||
      !KINDS.has((responsibility as Record<string, unknown>).kind as string)
    )
      return failure(WORKFLOW_DEFINITION_ERROR_CODES.INVALID_RESPONSIBILITY, `${base}.responsibility`)
    const responsibilityRecord = responsibility as Record<string, unknown>
    const responsibilityName = text(responsibilityRecord.name, `${base}.responsibility.name`)
    if (!responsibilityName.ok) return responsibilityName
    if (!Array.isArray(step.dependsOn))
      return failure(WORKFLOW_DEFINITION_ERROR_CODES.INVALID_VALUE, `${base}.dependsOn`)
    const dependencies: string[] = []
    const seen = new Set<string>()
    const rawDependencies = step.dependsOn as unknown[]
    for (let dependencyIndex = 0; dependencyIndex < rawDependencies.length; dependencyIndex++) {
      const dependency = text(rawDependencies[dependencyIndex], `${base}.dependsOn[${dependencyIndex}]`, {
        safe: true,
      })
      if (!dependency.ok) return dependency
      if (seen.has(dependency.value))
        return failure(WORKFLOW_DEFINITION_ERROR_CODES.INVALID_VALUE, `${base}.dependsOn[${dependencyIndex}]`, {
          reason: 'duplicate_dependency',
        })
      seen.add(dependency.value)
      dependencies.push(dependency.value)
    }
    steps.push({
      id: id.value,
      name: name.value,
      responsibility: { kind: responsibilityRecord.kind as ResponsibilityKind, name: responsibilityName.value },
      dependsOn: dependencies,
    })
  }
  for (const step of steps)
    for (const dependency of step.dependsOn) {
      if (dependency === step.id)
        return failure(WORKFLOW_DEFINITION_ERROR_CODES.SELF_DEPENDENCY, `steps.${step.id}.dependsOn`)
      if (!ids.has(dependency))
        return failure(WORKFLOW_DEFINITION_ERROR_CODES.MISSING_DEPENDENCY, `steps.${step.id}.dependsOn`, {
          target: dependency,
        })
    }
  const graph = new Map<string, string[]>(steps.map((step) => [step.id, step.dependsOn]))
  const visiting = new Set<string>()
  const visited = new Set<string>()
  function cyclic(id: string): boolean {
    if (visiting.has(id)) return true
    if (visited.has(id)) return false
    visiting.add(id)
    for (const dependency of graph.get(id) ?? []) if (cyclic(dependency)) return true
    visiting.delete(id)
    visited.add(id)
    return false
  }
  for (const step of steps)
    if (cyclic(step.id)) return failure(WORKFLOW_DEFINITION_ERROR_CODES.DEPENDENCY_CYCLE, 'steps')
  return {
    ok: true,
    definition: {
      schemaVersion: WORKFLOW_DEFINITION_SCHEMA_VERSION,
      workflowType: type.value,
      version,
      title: title.value,
      ...(trustedExecution ? { trustedExecution } : {}),
      steps,
    },
  }
}

function canonicalizeValue(value: unknown): unknown {
  if (Array.isArray(value)) return value.map((entry) => canonicalizeValue(entry))
  if (value !== null && typeof value === 'object') {
    const record = value as Record<string, unknown>
    return Object.fromEntries(
      Object.keys(record)
        .sort()
        .map((key) => [key, canonicalizeValue(record[key])])
    )
  }
  return value
}

export function canonicalizeWorkflowDefinition(definition: unknown): string {
  return JSON.stringify(canonicalizeValue(definition))
}

export function hashWorkflowDefinition(definition: unknown): string {
  return createHash('sha256').update(canonicalizeWorkflowDefinition(definition), 'utf8').digest('hex')
}
