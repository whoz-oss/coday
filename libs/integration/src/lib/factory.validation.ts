import { CommandContext } from '@coday/model'
import { safeId, safeRuntimeId, statuses } from './factory.schemas'
export function validateConfig(config: CommandContext['project']['factory']): string | undefined {
  if (config?.enabled !== true) return 'Factory integration is disabled for this project.'
  if (!config.baseUrl) return 'Factory baseUrl is not configured for this project.'
  try {
    const url = new URL(config.baseUrl)
    if (!['http:', 'https:'].includes(url.protocol)) throw new Error()
  } catch {
    return 'Factory baseUrl is invalid.'
  }
  if (
    !config.namespaceId ||
    !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(config.namespaceId)
  )
    return 'Factory namespaceId must be a valid UUID.'
  if (config.runtimeId !== undefined && !safeRuntimeId.test(config.runtimeId)) return 'Factory runtimeId is invalid.'
}
export function validateEvidenceToolInput(kind: 'agent-result' | 'artifact', input: unknown): string | undefined {
  if (!input || typeof input !== 'object' || Array.isArray(input)) return 'Evidence must be an object.'
  const value = input as Record<string, unknown>
  const allowed =
    kind === 'artifact'
      ? ['workflowId', 'stepId', 'artifactRef', 'artifactHash', 'idempotencyKey']
      : ['workflowId', 'stepId', 'outcome', 'facts', 'idempotencyKey']
  if (Object.keys(value).some((k) => !allowed.includes(k)))
    return 'Evidence contains unsupported or attribution fields.'
  if (
    typeof value.workflowId !== 'string' ||
    !safeId.test(value.workflowId) ||
    typeof value.stepId !== 'string' ||
    !safeId.test(value.stepId)
  )
    return 'workflowId and stepId are invalid.'
  if (
    value.idempotencyKey !== undefined &&
    (typeof value.idempotencyKey !== 'string' || value.idempotencyKey.length === 0 || value.idempotencyKey.length > 128)
  )
    return 'idempotencyKey is invalid.'
  if (kind === 'artifact')
    return typeof value.artifactRef !== 'string' ||
      value.artifactRef.length === 0 ||
      value.artifactRef.length > 1024 ||
      !/^\S(?:.*\S)?$/.test(value.artifactRef) ||
      /[\r\n]/.test(value.artifactRef) ||
      typeof value.artifactHash !== 'string' ||
      !/^sha256:[0-9a-f]{64}$/.test(value.artifactHash)
      ? 'artifactRef or artifactHash is invalid.'
      : undefined
  if (value.outcome !== undefined && !['pass', 'fail', 'indeterminate'].includes(value.outcome as string))
    return 'outcome is invalid.'
  const facts = value.facts
  if (
    !facts ||
    typeof facts !== 'object' ||
    Array.isArray(facts) ||
    Object.keys(facts).length === 0 ||
    Object.keys(facts).some((k) => !['resultCode', 'category', 'attempt', 'durationMs', 'itemCount'].includes(k))
  )
    return 'facts must be a non-empty allow-listed object.'
}
export function validateTransitionToolInput(input: unknown): string | undefined {
  if (!input || typeof input !== 'object' || Array.isArray(input)) return 'Transition must be an object.'
  const value = input as Record<string, unknown>,
    allowed = ['workflowId', 'stepId', 'expectedRevision', 'requestedStatus', 'evidenceIds', 'idempotencyKey']
  if (Object.keys(value).some((k) => !allowed.includes(k)))
    return 'Transition contains unsupported, attribution, or requestId fields.'
  if (
    typeof value.workflowId !== 'string' ||
    !safeId.test(value.workflowId) ||
    typeof value.stepId !== 'string' ||
    !safeId.test(value.stepId)
  )
    return 'workflowId and stepId are invalid.'
  if (
    !Number.isSafeInteger(value.expectedRevision) ||
    (value.expectedRevision as number) < 1 ||
    !statuses.includes(value.requestedStatus as never)
  )
    return 'Revision or status is invalid.'
  if (
    !Array.isArray(value.evidenceIds) ||
    value.evidenceIds.length > 100 ||
    new Set(value.evidenceIds).size !== value.evidenceIds.length ||
    value.evidenceIds.some((id) => typeof id !== 'string' || !safeId.test(id))
  )
    return 'evidenceIds are invalid.'
  if (
    value.idempotencyKey !== undefined &&
    (typeof value.idempotencyKey !== 'string' ||
      !value.idempotencyKey ||
      value.idempotencyKey.length > 128 ||
      /[\r\n]/.test(value.idempotencyKey))
  )
    return 'idempotencyKey is invalid.'
}
export function validateWorkflowProjection(input: unknown): string | undefined {
  if (!input || typeof input !== 'object' || Array.isArray(input)) return 'Projection must be an object.'
  const p = input as Record<string, unknown>,
    allowed = new Set(['schemaVersion', 'workflowId', 'workflowType', 'title', 'status', 'expectedRevision', 'steps'])
  if (Object.keys(p).some((k) => !allowed.has(k))) return 'Projection contains unsupported fields.'
  if (p.schemaVersion !== '1' && p.schemaVersion !== '2') return 'schemaVersion must be "1" or "2".'
  if (typeof p.workflowId !== 'string' || !safeId.test(p.workflowId)) return 'workflowId is invalid.'
  for (const [key, limit] of [
    ['workflowType', 256],
    ['title', 256],
  ] as const) {
    const v = p[key]
    if (typeof v !== 'string' || !v.trim() || v.length > limit) return `${key} is invalid.`
  }
  if (typeof p.status !== 'string' || !statuses.includes(p.status as never)) return 'Workflow status is invalid.'
  if (p.expectedRevision !== undefined && (!Number.isInteger(p.expectedRevision) || (p.expectedRevision as number) < 0))
    return 'expectedRevision must be a non-negative integer.'
  if (!Array.isArray(p.steps) || p.steps.length > 500) return 'steps must contain at most 500 items.'
  const ids = new Set<string>(),
    deps = new Map<string, string[]>()
  for (const raw of p.steps) {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return 'Each step must be an object.'
    const s = raw as Record<string, unknown>,
      allowedStep = new Set([
        'id',
        'name',
        'status',
        'description',
        'dependsOn',
        ...(p.schemaVersion === '2' ? ['responsibility'] : []),
      ])
    if (Object.keys(s).some((k) => !allowedStep.has(k))) return 'A step contains unsupported fields.'
    if (typeof s.id !== 'string' || !safeId.test(s.id) || ids.has(s.id)) return 'Step IDs must be safe and unique.'
    if (typeof s.name !== 'string' || !s.name.trim() || s.name.length > 256) return `Step ${s.id} has an invalid name.`
    if (typeof s.status !== 'string' || !statuses.includes(s.status as never))
      return `Step ${s.id} has an invalid status.`
    if (s.description !== undefined && (typeof s.description !== 'string' || s.description.length > 4096))
      return `Step ${s.id} has an invalid description.`
    if (
      s.dependsOn !== undefined &&
      (!Array.isArray(s.dependsOn) || s.dependsOn.length > 100 || s.dependsOn.some((id) => typeof id !== 'string'))
    )
      return `Step ${s.id} has invalid dependencies.`
    if (p.schemaVersion === '2') {
      const r = s.responsibility as Record<string, unknown> | undefined
      if (!r || Array.isArray(r) || typeof r !== 'object') return `Step ${s.id} requires responsibility.`
      if (Object.keys(r).some((k) => !['kind', 'name'].includes(k)))
        return `Step ${s.id} responsibility contains unsupported fields.`
      if (!['human', 'agent', 'code'].includes(r.kind as string))
        return `Step ${s.id} has an invalid responsibility kind.`
      if (r.name !== undefined && (typeof r.name !== 'string' || !r.name.trim() || r.name.length > 256))
        return `Step ${s.id} has an invalid responsibility name.`
    }
    ids.add(s.id)
    deps.set(s.id, (s.dependsOn as string[] | undefined) ?? [])
  }
  for (const [id, d] of deps) if (d.some((x) => x === id || !ids.has(x))) return `Step ${id} has an invalid dependency.`
  const visiting = new Set<string>(),
    visited = new Set<string>()
  const cyclic = (id: string): boolean => {
    if (visiting.has(id)) return true
    if (visited.has(id)) return false
    visiting.add(id)
    for (const dep of deps.get(id) ?? []) if (cyclic(dep)) return true
    visiting.delete(id)
    visited.add(id)
    return false
  }
  if ([...ids].some(cyclic)) return 'Step dependencies contain a cycle.'
}
