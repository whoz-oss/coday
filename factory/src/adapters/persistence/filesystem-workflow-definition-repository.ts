import type {
  WorkflowDefinitionRepository,
  WorkflowDefinitionWithHash,
} from '../../ports/persistence/workflow-definition-repository.js'

/**
 * Filesystem definition repository adapter.
 *
 * Delegates to the in-process definition registry that owns directory scanning,
 * path/identity validation and content hashing. The registry is injected as a
 * structural dependency so the adapter stays free of any `.mjs` import and can
 * be bundled into the operational artifact; `factory/lib/workflow-definition-registry.mjs`
 * wires the concrete registry at the composition edge.
 */

export interface WorkflowDefinitionRegistryLike {
  list(): Promise<WorkflowDefinitionWithHash[]>
  get(workflowType: string, version: string): Promise<WorkflowDefinitionWithHash | null>
  resolveUnique(workflowType: string): Promise<WorkflowDefinitionWithHash>
}

export class WorkflowDefinitionRepositoryError extends Error {
  readonly code: string
  readonly details: Record<string, unknown>

  constructor(code: string, details: Record<string, unknown> = {}, cause?: unknown) {
    super(code, cause === undefined ? undefined : { cause: cause as Error })
    this.name = 'WorkflowDefinitionRepositoryError'
    this.code = code
    this.details = details
  }
}

export class FilesystemWorkflowDefinitionRepository implements WorkflowDefinitionRepository {
  constructor(private readonly registry: WorkflowDefinitionRegistryLike) {}

  list(): Promise<WorkflowDefinitionWithHash[]> {
    return this.registry.list()
  }

  get(workflowType: string, version: string): Promise<WorkflowDefinitionWithHash | null> {
    return this.registry.get(workflowType, version)
  }

  resolveUnique(workflowType: string): Promise<WorkflowDefinitionWithHash> {
    return this.registry.resolveUnique(workflowType)
  }
}

/** Wires a filesystem definition repository around a concrete registry. */
export function createFilesystemWorkflowDefinitionRepository(
  registry: WorkflowDefinitionRegistryLike
): FilesystemWorkflowDefinitionRepository {
  return new FilesystemWorkflowDefinitionRepository(registry)
}
