import type {
  WorkflowEvidence,
  WorkflowEvidenceInput,
  WorkflowEvidenceSource,
} from '../../domain/evidence/workflow-evidence.js'

/**
 * Persistence port for the workflow-evidence domain context.
 *
 * The journal is append-only and idempotency-aware; the port exposes exactly
 * those two capabilities and no filesystem detail.
 */

export interface WorkflowEvidenceListFilter {
  stepId?: string
}

export interface WorkflowEvidenceRecordResult {
  created: boolean
  idempotent: boolean
  evidence: WorkflowEvidence
}

export interface WorkflowEvidenceRepository {
  /** Evidence of a workflow scope, optionally narrowed to one step, chronologically sorted. */
  list(namespaceId: string, storageId: string, filter?: WorkflowEvidenceListFilter): Promise<WorkflowEvidence[]>
  /** Durably records validated evidence; replays are idempotent on the key/scope pair. */
  record(
    namespaceId: string,
    storageId: string,
    input: WorkflowEvidenceInput,
    source: WorkflowEvidenceSource
  ): Promise<WorkflowEvidenceRecordResult>
}
