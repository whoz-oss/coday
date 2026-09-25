import type {
  WorkflowEvidenceRecordResult,
  WorkflowEvidenceRepository,
} from '../../ports/persistence/workflow-evidence-repository.js'
import type {
  WorkflowEvidence,
  WorkflowEvidenceInput,
  WorkflowEvidenceSource,
} from '../../domain/evidence/workflow-evidence.js'

/**
 * Filesystem evidence repository adapter.
 *
 * The append-only JSONL journal, idempotency-scope hashing and error codes live
 * in the injected evidence store; this adapter expresses them through the port.
 * `factory/lib/workflow-evidence-store.mjs` wires the concrete store.
 */

export interface WorkflowEvidenceStoreLike {
  list(namespaceId: string, storageId: string, filter?: { stepId?: string }): Promise<WorkflowEvidence[]>
  record(
    namespaceId: string,
    storageId: string,
    input: WorkflowEvidenceInput,
    source: WorkflowEvidenceSource
  ): Promise<WorkflowEvidenceRecordResult>
}

export class FilesystemWorkflowEvidenceRepository implements WorkflowEvidenceRepository {
  constructor(private readonly store: WorkflowEvidenceStoreLike) {}

  list(namespaceId: string, storageId: string, filter?: { stepId?: string }): Promise<WorkflowEvidence[]> {
    return this.store.list(namespaceId, storageId, filter)
  }

  record(
    namespaceId: string,
    storageId: string,
    input: WorkflowEvidenceInput,
    source: WorkflowEvidenceSource
  ): Promise<WorkflowEvidenceRecordResult> {
    return this.store.record(namespaceId, storageId, input, source)
  }
}

/** Wires a filesystem evidence repository around a concrete store. */
export function createFilesystemWorkflowEvidenceRepository(
  store: WorkflowEvidenceStoreLike
): FilesystemWorkflowEvidenceRepository {
  return new FilesystemWorkflowEvidenceRepository(store)
}
