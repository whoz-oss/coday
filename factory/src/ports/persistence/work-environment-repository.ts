import type {
  ValidationFailure,
  WorkUnitEnvironment,
  WorkUnitEnvironmentState,
} from '../../domain/environment/work-unit-environment.js'
import type {
  EnvironmentSnapshot,
  StoreWriteResult,
  WorkUnitEnvironmentPaths,
} from '../../adapters/persistence/work-unit-environment-store.js'

/**
 * Persistence port for the work-unit-environment context.
 *
 * An environment is a per-directory durable snapshot (`environment.json`) with
 * an append-only journal (`events.jsonl`) and a write-ahead `pending.json`
 * marker. The port exposes the descriptor lifecycle — reserve then transition —
 * and the path algebra callers need to locate the persisted artifacts, never
 * the file layout details.
 *
 * The concrete `WorkUnitEnvironmentStore` owns locking, crash recovery and the
 * on-disk format; the port is the boundary the application depends on.
 */
export interface WorkEnvironmentRepository {
  /** The persisted artifact paths of one environment scope. */
  paths(namespaceId: string, environmentId: string): WorkUnitEnvironmentPaths
  /** The current durable snapshot of one environment, or `null` when absent. */
  read(namespaceId: string, environmentId: string): Promise<EnvironmentSnapshot | null>
  /** Every environment snapshot of a namespace, optionally narrowed by lifecycle state. */
  list(namespaceId: string, filter?: { states?: readonly WorkUnitEnvironmentState[] }): Promise<EnvironmentSnapshot[]>
  /** Reserves a fresh environment descriptor; an identical replay is a no-op. */
  reserve(environment: unknown): Promise<StoreWriteResult | ValidationFailure>
  /** Applies a validated lifecycle transition to an existing environment. */
  transition(
    namespaceId: string,
    environmentId: string,
    next: WorkUnitEnvironment,
    options?: { expectedRevision?: number; errorCode?: string }
  ): Promise<StoreWriteResult | ValidationFailure>
}
