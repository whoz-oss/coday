import type {
  DeliveryJournalRecord,
  DeliveryOperationProjection,
  DeliveryOperationTransitionInput,
  DeliverySnapshot,
  DeliveryStoreOperationInput,
  DeliveryStorePromoteInput,
  DeliveryStoreRollbackApprovalInput,
  DeliveryStoreRollbackRequestInput,
  DeliveryStoreWriteResult,
} from '../../adapters/persistence/delivery-store.js'
import type { DeliveryOperationObservation } from '../../domain/delivery/delivery-operation-definition.js'

/**
 * Persistence port for the delivery context.
 *
 * A delivery is a durable snapshot (`delivery.json`) plus an append-only
 * operations journal (`operations.jsonl`) projected into live delivery
 * operations and rollback requests. The port exposes creation/promotion,
 * journal-backed operation lifecycles (including indeterminate-operation
 * reconciliation) and rollback requests, never the file layout or hashing
 * details.
 *
 * The concrete `DeliveryStore` owns locking, write-ahead recovery, idempotency
 * and the on-disk format; the port is the boundary the application depends on.
 */
export interface DeliveryRepository {
  /** The current durable snapshot of one delivery, or `null` when absent. */
  read(namespaceId: string, deliveryId: string): Promise<DeliverySnapshot | null>
  /** Creates a delivery snapshot; an identical replay is a no-op. */
  create(input: Record<string, unknown>): Promise<DeliveryStoreWriteResult>
  /** Promotes a delivery against its policy, definition, evidence and execution context. */
  promote(input: DeliveryStorePromoteInput): Promise<DeliveryStoreWriteResult>
  /** The snapshot plus its projected delivery operations and rollback requests. */
  readWithOperations(
    namespaceId: string,
    deliveryId: string
  ): Promise<
    | (DeliverySnapshot & { deliveryOperations: DeliveryJournalRecord[]; rollbackRequests: DeliveryJournalRecord[] })
    | null
  >
  /** The full projection of the operations journal (history, current, rollbacks). */
  inspectDeliveryOperations(namespaceId: string, deliveryId: string): Promise<DeliveryOperationProjection>
  /** Records a rollback request; replays are idempotent on the scope/semantic pair. */
  createRollbackRequest(input: DeliveryStoreRollbackRequestInput): Promise<DeliveryStoreWriteResult>
  /** Approves a pending rollback request at the expected revision. */
  approveRollbackRequest(
    namespaceId: string,
    deliveryId: string,
    rollbackRequestId: string,
    approval: DeliveryStoreRollbackApprovalInput
  ): Promise<DeliveryStoreWriteResult>
  /** Creates a durable delivery operation from a normalized request. */
  createDeliveryOperation(input: DeliveryStoreOperationInput): Promise<DeliveryStoreWriteResult>
  /** Records a state transition of a persisted delivery operation. */
  recordDeliveryOperation(
    namespaceId: string,
    deliveryId: string,
    operationId: string,
    transition: DeliveryOperationTransitionInput,
    options?: { inspectedObservation?: DeliveryOperationObservation }
  ): Promise<DeliveryStoreWriteResult>
  /** Marks a delivery operation as running with the adapter correlation token. */
  startDeliveryOperation(
    namespaceId: string,
    deliveryId: string,
    operationId: string,
    adapterCorrelation: unknown
  ): Promise<DeliveryStoreWriteResult>
  /** Reconciles a delivery operation against an observed adapter outcome. */
  reconcileDeliveryOperation(
    namespaceId: string,
    deliveryId: string,
    operationId: string,
    observation: DeliveryOperationObservation
  ): Promise<DeliveryStoreWriteResult>
  /** Whether the delivery has an unresolved indeterminate operation blocking progress. */
  hasIndeterminateOperation(namespaceId: string, deliveryId: string): Promise<boolean>
  /** Atomically patches snapshot fields and appends the matching journal record. */
  updateSnapshot(
    namespaceId: string,
    deliveryId: string,
    patch: Record<string, unknown>,
    operationInput: { kind: string; idempotencyKey: string; facts?: unknown }
  ): Promise<DeliveryStoreWriteResult>
}
