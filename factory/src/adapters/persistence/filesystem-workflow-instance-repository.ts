import type {
  WorkflowInstanceRemoveActor,
  WorkflowInstanceRepository,
  WorkflowInstanceSnapshot,
} from '../../ports/persistence/workflow-instance-repository.js'
import type {
  ControllerExecutionInput,
  WorkflowDefinitionInput,
  WorkflowProjection,
  WorkflowStartCommand,
} from '../../domain/workflow/workflow-instance.js'

/**
 * Filesystem workflow-instance repository adapter.
 *
 * The projection store owns the on-disk layout (`projection.json`,
 * `events.jsonl`, `pending.json`, `trash/`, `tombstones/`), the pending/
 * recovery protocol and the lifecycle state machine. This adapter exposes that
 * behaviour through the instance port and normalizes its result objects into
 * domain snapshots or thrown errors.
 *
 * The store is injected as a structural dependency so the adapter carries no
 * `.mjs` import; `factory/lib/workflow-projection-store.mjs` wires the concrete
 * store.
 */

export interface WorkflowStoreResult {
  ok: boolean
  created?: boolean
  changed?: boolean
  idempotent?: boolean
  alreadyPurged?: boolean
  snapshot?: WorkflowInstanceSnapshot
  decision?: unknown
  requestId?: string
  error?: { code: string; details?: Record<string, unknown> }
}

export interface WorkflowProjectionStoreLike {
  initialize?(): Promise<unknown>
  list(namespaceId: string): Promise<WorkflowInstanceSnapshot[]>
  read(namespaceId: string, workflowId: string): Promise<WorkflowInstanceSnapshot | null>
  start(
    namespaceId: string,
    command: WorkflowStartCommand,
    definition: WorkflowDefinitionInput,
    controllerExecution: ControllerExecutionInput
  ): Promise<WorkflowStoreResult>
  transition(
    namespaceId: string,
    request: unknown,
    definition: unknown,
    evidence: unknown,
    controllerExecution: unknown,
    options?: unknown
  ): Promise<WorkflowStoreResult>
  remove(namespaceId: string, workflowId: string, actor?: WorkflowInstanceRemoveActor): Promise<WorkflowStoreResult>
  restore(namespaceId: string, workflowId: string, actor?: WorkflowInstanceRemoveActor): Promise<WorkflowStoreResult>
  purge(namespaceId: string, workflowId: string, actor?: WorkflowInstanceRemoveActor): Promise<WorkflowStoreResult>
}

export class WorkflowInstanceRepositoryError extends Error {
  readonly code: string
  readonly details: Record<string, unknown>
  readonly decision: unknown

  constructor(code: string, details: Record<string, unknown> = {}, decision?: unknown) {
    super(code)
    this.name = 'WorkflowInstanceRepositoryError'
    this.code = code
    this.details = details
    this.decision = decision
  }
}

/** Shape of the transition payload accepted by `transition`. */
export interface WorkflowInstanceTransitionInput {
  request: unknown
  definition: unknown
  evidence?: unknown[]
  execution: unknown
  idempotencyKey?: string
  fault?: (point: string, details?: unknown) => Promise<void> | void
  policy?: (input: unknown) => unknown
}

export class FilesystemWorkflowInstanceRepository implements WorkflowInstanceRepository {
  #initialized: Promise<void> | null = null

  constructor(private readonly store: WorkflowProjectionStoreLike) {}

  /** Ensures the store's root directories exist before a mutating operation. */
  async #ensureInitialized(): Promise<void> {
    if (!this.store.initialize) return
    this.#initialized ??= Promise.resolve(this.store.initialize()).then(() => undefined)
    await this.#initialized
  }

  async list(namespaceId: string): Promise<WorkflowProjection[]> {
    const snapshots = await this.store.list(namespaceId)
    return snapshots.map((snapshot) => snapshot.projection)
  }

  async get(namespaceId: string, workflowId: string): Promise<WorkflowInstanceSnapshot | null> {
    const snapshot = await this.store.read(namespaceId, workflowId)
    return snapshot ? { instance: snapshot.instance, projection: snapshot.projection } : null
  }

  async create(
    namespaceId: string,
    command: WorkflowStartCommand,
    definition: WorkflowDefinitionInput,
    controllerExecution: ControllerExecutionInput
  ): Promise<WorkflowInstanceSnapshot> {
    await this.#ensureInitialized()
    const result = await this.store.start(namespaceId, command, definition, controllerExecution)
    return this.#requireSnapshot(result, 'WORKFLOW_INSTANCE_CREATE_FAILED')
  }

  async transition(namespaceId: string, workflowId: string, transition: unknown): Promise<WorkflowInstanceSnapshot> {
    await this.#ensureInitialized()
    const input = (transition ?? {}) as WorkflowInstanceTransitionInput
    const options = input.fault || input.policy ? { fault: input.fault, policy: input.policy } : undefined
    const result = await this.store.transition(
      namespaceId,
      input.request,
      input.definition,
      input.evidence ?? [],
      input.execution,
      options
    )
    const snapshot = this.#requireSnapshot(result, 'WORKFLOW_INSTANCE_TRANSITION_FAILED')
    void workflowId
    return snapshot
  }

  async remove(namespaceId: string, workflowId: string, actor?: WorkflowInstanceRemoveActor): Promise<void> {
    await this.#ensureInitialized()
    this.#requireOk(await this.store.remove(namespaceId, workflowId, actor), 'WORKFLOW_INSTANCE_REMOVE_FAILED')
  }

  async restore(namespaceId: string, workflowId: string, actor?: WorkflowInstanceRemoveActor): Promise<void> {
    await this.#ensureInitialized()
    this.#requireOk(await this.store.restore(namespaceId, workflowId, actor), 'WORKFLOW_INSTANCE_RESTORE_FAILED')
  }

  async purge(namespaceId: string, workflowId: string, actor?: WorkflowInstanceRemoveActor): Promise<void> {
    await this.#ensureInitialized()
    this.#requireOk(await this.store.purge(namespaceId, workflowId, actor), 'WORKFLOW_INSTANCE_PURGE_FAILED')
  }

  #requireSnapshot(result: WorkflowStoreResult, fallbackCode: string): WorkflowInstanceSnapshot {
    this.#requireOk(result, fallbackCode)
    const snapshot = result.snapshot
    if (!snapshot) throw new WorkflowInstanceRepositoryError(fallbackCode, {}, result.decision)
    return { instance: snapshot.instance, projection: snapshot.projection }
  }

  #requireOk(result: WorkflowStoreResult, fallbackCode: string): void {
    if (result?.ok) return
    const code = result?.error?.code ?? fallbackCode
    throw new WorkflowInstanceRepositoryError(code, result?.error?.details ?? {}, result?.decision)
  }
}

/** Wires a filesystem instance repository around a concrete projection store. */
export function createFilesystemWorkflowInstanceRepository(
  store: WorkflowProjectionStoreLike
): FilesystemWorkflowInstanceRepository {
  return new FilesystemWorkflowInstanceRepository(store)
}
