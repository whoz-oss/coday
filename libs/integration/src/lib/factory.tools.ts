import { AssistantToolFactory, CodayTool, CommandContext, IntegrationConfig, Interactor } from '@coday/model'
import { agentResultEvidenceSchema, artifactEvidenceSchema, transitionSchema } from './factory.schemas'
import { validateEvidenceToolInput, validateTransitionToolInput } from './factory.validation'

/** @deprecated Transitional Express adapter. Use AgentOS FactoryPublishProjectionTool when available. */
export class FactoryTools extends AssistantToolFactory {
  static readonly TYPE = 'FACTORY' as const
  private static readonly TIMEOUT_MS = 20_000

  constructor(interactor: Interactor, instanceName: string, config: IntegrationConfig = {}) {
    super(interactor, instanceName, config)
  }

  protected async buildTools(context: CommandContext, agentName: string): Promise<CodayTool[]> {
    return [
      {
        type: 'function',
        function: {
          name: `${this.name}__get_workflow`,
          description: 'Read the authoritative namespace-scoped state of a Factory workflow before creation or resume.',
          parameters: workflowLookupSchema,
          parse: JSON.parse,
          function: async (input: unknown) => this.getWorkflow(context, input),
        },
      },
      {
        type: 'function',
        function: {
          name: `${this.name}__start_workflow`,
          description: 'Create an authoritative governed workflow from the unique configured immutable definition.',
          parameters: startSchema,
          parse: JSON.parse,
          function: async (input: unknown) => this.startWorkflow(context, agentName, input),
        },
      },
      ...(['agent-result', 'artifact'] as const).map(
        (kind): CodayTool => ({
          type: 'function',
          function: {
            name: `${this.name}__record_${kind === 'agent-result' ? 'agent_result' : 'artifact'}`,
            description: `Record immutable structured ${kind} evidence for a governed Factory workflow step.`,
            parameters: kind === 'agent-result' ? agentResultEvidenceSchema : artifactEvidenceSchema,
            parse: JSON.parse,
            function: async (input: unknown) => this.recordEvidence(context, agentName, kind, input),
          },
        })
      ),
      {
        type: 'function',
        function: {
          name: `${this.name}__request_transition`,
          description: 'Request a governed transition for an agent-owned step.',
          parameters: transitionSchema,
          parse: JSON.parse,
          function: async (input: unknown) => this.requestTransition(context, agentName, input),
        },
      },
      {
        type: 'function',
        function: {
          name: `${this.name}__publish_projection`,
          description: 'Publish a generic WorkflowProjection v1 or v2 to the configured Factory runtime.',
          parameters: projectionSchema,
          parse: JSON.parse,
          function: async (input: unknown) => this.publish(context, agentName, input),
        },
      },
    ]
  }

  private async getWorkflow(context: CommandContext, input: unknown): Promise<string> {
    const config = context.project.factory
    const configError = validateConfig(config)
    if (configError) return errorResult('FACTORY_UNAVAILABLE', configError)
    if (
      !input ||
      typeof input !== 'object' ||
      Array.isArray(input) ||
      Object.keys(input).some((key) => key !== 'workflowId')
    ) {
      return errorResult('INVALID_REQUEST', 'Only workflowId is accepted.')
    }
    const workflowId = (input as { workflowId?: unknown }).workflowId
    if (typeof workflowId !== 'string' || !safeId.test(workflowId))
      return errorResult('INVALID_WORKFLOW_ID', 'workflowId is invalid.')
    return this.requestLookup(config!.baseUrl!, config!.namespaceId!, workflowId)
  }

  private async requestLookup(baseUrl: string, namespaceId: string, workflowId: string): Promise<string> {
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), FactoryTools.TIMEOUT_MS)
    try {
      const response = await fetch(
        `${baseUrl.replace(/\/+$/, '')}/api/factory/workflows/${encodeURIComponent(workflowId)}?namespaceId=${encodeURIComponent(namespaceId)}`,
        { signal: controller.signal }
      )
      const payload: unknown = await response.json().catch(() => undefined)
      if (!response.ok) {
        const factoryError = readFactoryError(payload)
        return factoryError
          ? errorResult(factoryError.code, factoryError.message)
          : errorResult('FACTORY_UNAVAILABLE', 'Factory rejected the lookup without a valid error response.')
      }
      const data = readLookupSuccess(payload, workflowId)
      return data
        ? JSON.stringify(data)
        : errorResult('MALFORMED_FACTORY_RESPONSE', 'Factory returned a malformed workflow lookup response.')
    } catch (error) {
      return error instanceof Error && error.name === 'AbortError'
        ? errorResult('FACTORY_TIMEOUT', 'Factory lookup timed out.')
        : errorResult('FACTORY_UNAVAILABLE', 'Factory is unavailable.')
    } finally {
      clearTimeout(timer)
    }
  }

  private async startWorkflow(context: CommandContext, agentName: string, input: unknown): Promise<string> {
    const config = context.project.factory
    const configError = validateConfig(config)
    if (configError) return errorResult('FACTORY_UNAVAILABLE', configError)
    if (
      !input ||
      typeof input !== 'object' ||
      Array.isArray(input) ||
      Object.keys(input).some((key) => !['workflowId', 'workflowType', 'title'].includes(key))
    )
      return errorResult('INVALID_START_REQUEST', 'Only workflowId, workflowType and title are accepted.')
    const workflow = input as Record<string, unknown>
    if (
      typeof workflow.workflowId !== 'string' ||
      !safeId.test(workflow.workflowId) ||
      typeof workflow.workflowType !== 'string' ||
      !workflow.workflowType.trim() ||
      typeof workflow.title !== 'string' ||
      !workflow.title.trim()
    )
      return errorResult('INVALID_START_REQUEST', 'workflowId, workflowType and title are required.')
    const threadId = context.aiThread?.id?.trim()
    if (!threadId) return errorResult('FACTORY_UNAVAILABLE', 'A controlling Coday thread identity is required.')
    const execution: CodayExpressExecution = {
      namespaceId: config!.namespaceId!,
      runtimeId: config!.runtimeId?.trim() || 'coday-express-transitional',
      kind: 'coday-express',
      agentId: agentName?.trim() || 'default',
      threadId,
    }
    if (context.username?.trim()) execution.actorId = context.username.trim()
    try {
      const response = await fetch(
        `${config!.baseUrl!.replace(/\/+$/, '')}/api/factory/workflows/${encodeURIComponent(workflow.workflowId)}/start`,
        {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify({ workflow, execution }),
        }
      )
      const payload: unknown = await response.json().catch(() => undefined)
      if (!response.ok) {
        const error = readFactoryError(payload)
        return error
          ? errorResult(error.code, error.message)
          : errorResult('FACTORY_UNAVAILABLE', 'Factory rejected workflow creation.')
      }
      return JSON.stringify((payload as { data?: unknown }).data ?? payload)
    } catch {
      return errorResult('FACTORY_UNAVAILABLE', 'Factory is unavailable.')
    }
  }

  private async recordEvidence(
    context: CommandContext,
    agentName: string,
    kind: 'agent-result' | 'artifact',
    input: unknown
  ): Promise<string> {
    const config = context.project.factory
    const configError = validateConfig(config)
    if (configError) return errorResult('FACTORY_UNAVAILABLE', configError)
    const validationError = validateEvidenceToolInput(kind, input)
    if (validationError) return errorResult('INVALID_EVIDENCE', validationError)
    const threadId = context.aiThread?.id?.trim()
    if (!threadId) return errorResult('FACTORY_UNAVAILABLE', 'A controlling Coday thread identity is required.')
    const value = input as Record<string, unknown>
    const execution: CodayExpressExecution = {
      namespaceId: config!.namespaceId!,
      runtimeId: config!.runtimeId?.trim() || 'coday-express-transitional',
      kind: 'coday-express',
      agentId: agentName?.trim() || 'default',
      threadId,
    }
    if (context.username?.trim()) execution.actorId = context.username.trim()
    try {
      const response = await fetch(
        `${config!.baseUrl!.replace(/\/+$/, '')}/api/factory/workflows/${encodeURIComponent(value.workflowId as string)}/evidence`,
        {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify({ evidence: { ...value, kind }, execution }),
        }
      )
      const payload: unknown = await response.json().catch(() => undefined)
      if (!response.ok) {
        const factoryError = readFactoryError(payload)
        return factoryError
          ? errorResult(factoryError.code, factoryError.message)
          : errorResult('FACTORY_UNAVAILABLE', 'Factory rejected evidence.')
      }
      const data = (payload as { data?: Record<string, unknown> } | undefined)?.data
      return data?.evidence && typeof data.created === 'boolean' && typeof data.idempotent === 'boolean'
        ? JSON.stringify(data)
        : errorResult('MALFORMED_FACTORY_RESPONSE', 'Factory returned malformed evidence.')
    } catch {
      return errorResult('FACTORY_UNAVAILABLE', 'Factory is unavailable.')
    }
  }

  private async requestTransition(context: CommandContext, agentName: string, input: unknown): Promise<string> {
    const config = context.project.factory,
      configError = validateConfig(config)
    if (configError) return errorResult('FACTORY_UNAVAILABLE', configError)
    const validationError = validateTransitionToolInput(input)
    if (validationError) return errorResult('INVALID_TRANSITION_REQUEST', validationError)
    const value = input as Record<string, unknown>
    const threadId = context.aiThread?.id?.trim()
    if (!threadId) return errorResult('FACTORY_UNAVAILABLE', 'A controlling Coday thread identity is required.')
    const execution: CodayExpressExecution = {
      namespaceId: config!.namespaceId!,
      runtimeId: config!.runtimeId?.trim() || 'coday-express-transitional',
      kind: 'coday-express',
      agentId: agentName?.trim() || 'default',
      threadId,
    }
    if (context.username?.trim()) execution.actorId = context.username.trim()
    try {
      const response = await fetch(
        `${config!.baseUrl!.replace(/\/+$/, '')}/api/factory/workflows/${encodeURIComponent(value.workflowId)}/transitions`,
        {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify({ transition: value, execution }),
        }
      )
      const payload: unknown = await response.json().catch(() => undefined)
      if (!response.ok) {
        const error = readFactoryError(payload)
        return error
          ? errorResult(error.code, error.message)
          : errorResult('FACTORY_UNAVAILABLE', 'Factory rejected transition.')
      }
      const data = (payload as any)?.data
      return data && Number.isInteger(data.revision) && typeof data.changed === 'boolean'
        ? JSON.stringify(data)
        : errorResult('MALFORMED_FACTORY_RESPONSE', 'Factory returned malformed transition.')
    } catch {
      return errorResult('FACTORY_UNAVAILABLE', 'Factory is unavailable.')
    }
  }

  private async publish(context: CommandContext, agentName: string, input: unknown): Promise<string> {
    const config = context.project.factory
    const configError = validateConfig(config)
    if (configError) return errorResult('FACTORY_UNAVAILABLE', configError)

    const validationError = validateWorkflowProjection(input)
    if (validationError) return errorResult('INVALID_PROJECTION', validationError)
    const projection = input as WorkflowProjection

    const threadId = context.aiThread?.id?.trim()
    if (!threadId) return errorResult('FACTORY_UNAVAILABLE', 'A controlling Coday thread identity is required.')
    const controllingAgent = agentName?.trim() || 'default'
    const execution: CodayExpressExecution = {
      namespaceId: config!.namespaceId!,
      runtimeId: config!.runtimeId?.trim() || 'coday-express-transitional',
      kind: 'coday-express',
      agentId: controllingAgent,
      threadId,
    }
    if (context.username?.trim()) execution.actorId = context.username.trim()

    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), FactoryTools.TIMEOUT_MS)
    try {
      const baseUrl = config!.baseUrl!.replace(/\/+$/, '')
      const response = await fetch(
        `${baseUrl}/api/factory/workflows/${encodeURIComponent(projection.workflowId)}/projection`,
        {
          method: 'PUT',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify({ projection, execution }),
          signal: controller.signal,
        }
      )
      const payload: unknown = await response.json().catch(() => undefined)
      if (!response.ok) {
        const factoryError = readFactoryError(payload)
        return factoryError
          ? errorResult(factoryError.code, factoryError.message)
          : errorResult('FACTORY_UNAVAILABLE', 'Factory rejected the publication without a valid error response.')
      }
      const data = readSuccess(payload)
      if (!data) return errorResult('MALFORMED_FACTORY_RESPONSE', 'Factory returned a malformed publication response.')
      return JSON.stringify({ ...data, updatedAt: new Date().toISOString() })
    } catch (error) {
      if (error instanceof Error && error.name === 'AbortError') {
        return errorResult('FACTORY_TIMEOUT', 'Factory publication timed out.')
      }
      return errorResult('FACTORY_UNAVAILABLE', 'Factory is unavailable.')
    } finally {
      clearTimeout(timer)
    }
  }
}

type CodayExpressExecution = {
  namespaceId: string
  runtimeId: string
  kind: 'coday-express'
  agentId: string
  threadId: string
  actorId?: string
}
type Responsibility = { kind: 'human' | 'agent' | 'code'; name?: string }
type Step = {
  id: string
  name: string
  status: string
  description?: string
  dependsOn?: string[]
  responsibility?: Responsibility
}
type WorkflowProjection = {
  schemaVersion: '1' | '2'
  workflowId: string
  workflowType: string
  title: string
  status: string
  expectedRevision?: number
  steps: Step[]
}

const statuses = [
  'pending',
  'ready',
  'running',
  'waiting_human',
  'blocked',
  'completed',
  'failed',
  'cancelled',
] as const
const safeId = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/
const safeRuntimeId = /^[A-Za-z0-9][A-Za-z0-9._:@-]{0,255}$/
const workflowLookupSchema = {
  type: 'object',
  additionalProperties: false,
  properties: { workflowId: { type: 'string', maxLength: 128, pattern: safeId.source } },
  required: ['workflowId'],
}
const startSchema = {
  type: 'object',
  additionalProperties: false,
  properties: {
    workflowId: { type: 'string', maxLength: 128, pattern: safeId.source },
    workflowType: { type: 'string', maxLength: 128 },
    title: { type: 'string', maxLength: 256 },
  },
  required: ['workflowId', 'workflowType', 'title'],
}
const projectionSchema = {
  type: 'object',
  additionalProperties: false,
  properties: {
    schemaVersion: { type: 'string', enum: ['1', '2'] },
    workflowId: { type: 'string', maxLength: 128 },
    workflowType: { type: 'string', maxLength: 256 },
    title: { type: 'string', maxLength: 256 },
    status: { type: 'string', enum: statuses },
    expectedRevision: { type: 'integer', minimum: 0 },
    steps: {
      type: 'array',
      maxItems: 500,
      items: {
        type: 'object',
        additionalProperties: false,
        properties: {
          id: { type: 'string', maxLength: 128 },
          name: { type: 'string', maxLength: 256 },
          status: { type: 'string', enum: statuses },
          description: { type: 'string', maxLength: 4096 },
          dependsOn: { type: 'array', maxItems: 100, items: { type: 'string', maxLength: 128 } },
          responsibility: {
            type: 'object',
            additionalProperties: false,
            properties: {
              kind: { type: 'string', enum: ['human', 'agent', 'code'] },
              name: { type: 'string', maxLength: 256 },
            },
            required: ['kind'],
          },
        },
        required: ['id', 'name', 'status'],
      },
    },
  },
  required: ['schemaVersion', 'workflowId', 'workflowType', 'title', 'status', 'steps'],
}

function validateConfig(config: CommandContext['project']['factory']): string | undefined {
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
  return undefined
}

export function validateWorkflowProjection(input: unknown): string | undefined {
  if (!input || typeof input !== 'object' || Array.isArray(input)) return 'Projection must be an object.'
  const p = input as Record<string, unknown>
  const allowed = new Set([
    'schemaVersion',
    'workflowId',
    'workflowType',
    'title',
    'status',
    'expectedRevision',
    'steps',
  ])
  if (Object.keys(p).some((key) => !allowed.has(key))) return 'Projection contains unsupported fields.'
  if (p.schemaVersion !== '1' && p.schemaVersion !== '2') return 'schemaVersion must be "1" or "2".'
  if (typeof p.workflowId !== 'string' || !safeId.test(p.workflowId)) return 'workflowId is invalid.'
  for (const [key, limit] of [
    ['workflowType', 256],
    ['title', 256],
  ] as const) {
    const value = p[key]
    if (typeof value !== 'string' || !value.trim() || value.length > limit) return `${key} is invalid.`
  }
  if (typeof p.status !== 'string' || !statuses.includes(p.status as (typeof statuses)[number]))
    return 'Workflow status is invalid.'
  if (p.expectedRevision !== undefined && (!Number.isInteger(p.expectedRevision) || (p.expectedRevision as number) < 0))
    return 'expectedRevision must be a non-negative integer.'
  if (!Array.isArray(p.steps) || p.steps.length > 500) return 'steps must contain at most 500 items.'
  const ids = new Set<string>()
  const dependencies = new Map<string, string[]>()
  for (const raw of p.steps) {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return 'Each step must be an object.'
    const step = raw as Record<string, unknown>
    const stepAllowed = new Set([
      'id',
      'name',
      'status',
      'description',
      'dependsOn',
      ...(p.schemaVersion === '2' ? ['responsibility'] : []),
    ])
    if (Object.keys(step).some((key) => !stepAllowed.has(key))) return 'A step contains unsupported fields.'
    if (typeof step.id !== 'string' || !safeId.test(step.id) || ids.has(step.id))
      return 'Step IDs must be safe and unique.'
    if (typeof step.name !== 'string' || !step.name.trim() || step.name.length > 256)
      return `Step ${step.id} has an invalid name.`
    if (typeof step.status !== 'string' || !statuses.includes(step.status as (typeof statuses)[number]))
      return `Step ${step.id} has an invalid status.`
    if (step.description !== undefined && (typeof step.description !== 'string' || step.description.length > 4096))
      return `Step ${step.id} has an invalid description.`
    if (
      step.dependsOn !== undefined &&
      (!Array.isArray(step.dependsOn) ||
        step.dependsOn.length > 100 ||
        step.dependsOn.some((id) => typeof id !== 'string'))
    )
      return `Step ${step.id} has invalid dependencies.`
    if (p.schemaVersion === '2') {
      const responsibility = step.responsibility as Record<string, unknown> | undefined
      if (!responsibility || Array.isArray(responsibility) || typeof responsibility !== 'object')
        return `Step ${step.id} requires responsibility.`
      if (Object.keys(responsibility).some((key) => !['kind', 'name'].includes(key)))
        return `Step ${step.id} responsibility contains unsupported fields.`
      if (!['human', 'agent', 'code'].includes(responsibility.kind as string))
        return `Step ${step.id} has an invalid responsibility kind.`
      if (
        responsibility.name !== undefined &&
        (typeof responsibility.name !== 'string' || !responsibility.name.trim() || responsibility.name.length > 256)
      )
        return `Step ${step.id} has an invalid responsibility name.`
    }
    ids.add(step.id)
    dependencies.set(step.id, (step.dependsOn as string[] | undefined) ?? [])
  }
  for (const [id, deps] of dependencies)
    if (deps.some((dep) => dep === id || !ids.has(dep))) return `Step ${id} has an invalid dependency.`
  const visiting = new Set<string>()
  const visited = new Set<string>()
  const cyclic = (id: string): boolean => {
    if (visiting.has(id)) return true
    if (visited.has(id)) return false
    visiting.add(id)
    for (const dep of dependencies.get(id) ?? []) if (cyclic(dep)) return true
    visiting.delete(id)
    visited.add(id)
    return false
  }
  if ([...ids].some(cyclic)) return 'Step dependencies contain a cycle.'
  return undefined
}

function readFactoryError(payload: unknown): { code: string; message: string } | undefined {
  const error = (payload as { error?: unknown } | undefined)?.error as { code?: unknown; message?: unknown } | undefined
  return error && typeof error.code === 'string' && typeof error.message === 'string'
    ? { code: error.code, message: error.message }
    : undefined
}
function readLookupSuccess(payload: unknown, requestedWorkflowId: string): Record<string, unknown> | undefined {
  const data = (payload as { data?: unknown } | undefined)?.data as Record<string, unknown> | undefined
  if (
    !data ||
    data.workflowId !== requestedWorkflowId ||
    !['absent', 'existing', 'removed', 'purged'].includes(data.state as string)
  )
    return undefined
  if (data.state !== 'existing') return { state: data.state, workflowId: requestedWorkflowId }
  const projection = data.projection as Record<string, unknown> | undefined
  if (
    !Number.isInteger(data.revision) ||
    !projection ||
    projection.workflowId !== requestedWorkflowId ||
    typeof projection.workflowType !== 'string'
  )
    return undefined
  return {
    state: 'existing',
    workflowId: requestedWorkflowId,
    revision: data.revision,
    workflowType: projection.workflowType,
    status: projection.status,
    projection,
  }
}
function readSuccess(payload: unknown): { workflowId: string; revision: number; changed: boolean } | undefined {
  const data = (payload as { data?: unknown } | undefined)?.data as Record<string, unknown> | undefined
  return data &&
    typeof data.workflowId === 'string' &&
    Number.isInteger(data.revision) &&
    typeof data.changed === 'boolean'
    ? { workflowId: data.workflowId, revision: data.revision as number, changed: data.changed }
    : undefined
}
function errorResult(code: string, message: string): string {
  return JSON.stringify({ error: { code, message } })
}
