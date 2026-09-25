import type { DeliveryRepository } from '../../ports/persistence/delivery-repository.js'
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
} from './delivery-store.js'
import type { DeliveryOperationObservation } from '../../domain/delivery/delivery-operation-definition.js'

/**
 * Filesystem delivery repository adapter.
 *
 * Delivery locking, write-ahead recovery, idempotency and the on-disk format
 * live in the injected delivery store; this adapter expresses them through the
 * port. The store is injected as a structural dependency so the adapter stays
 * free of any `.mjs` import and can be bundled into the operational artifact;
 * `factory/lib/delivery-store.mjs` wires the concrete store at the composition
 * edge.
 */
export interface DeliveryStoreLike {
  read(namespaceId: string, deliveryId: string): Promise<DeliverySnapshot | null>
  create(input: Record<string, unknown>): Promise<DeliveryStoreWriteResult>
  promote(input: DeliveryStorePromoteInput): Promise<DeliveryStoreWriteResult>
  readWithOperations(
    namespaceId: string,
    deliveryId: string
  ): Promise<
    | (DeliverySnapshot & { deliveryOperations: DeliveryJournalRecord[]; rollbackRequests: DeliveryJournalRecord[] })
    | null
  >
  inspectDeliveryOperations(namespaceId: string, deliveryId: string): Promise<DeliveryOperationProjection>
  createRollbackRequest(input: DeliveryStoreRollbackRequestInput): Promise<DeliveryStoreWriteResult>
  approveRollbackRequest(
    namespaceId: string,
    deliveryId: string,
    rollbackRequestId: string,
    approval: DeliveryStoreRollbackApprovalInput
  ): Promise<DeliveryStoreWriteResult>
  createDeliveryOperation(input: DeliveryStoreOperationInput): Promise<DeliveryStoreWriteResult>
  recordDeliveryOperation(
    namespaceId: string,
    deliveryId: string,
    operationId: string,
    transition: DeliveryOperationTransitionInput,
    options?: { inspectedObservation?: DeliveryOperationObservation }
  ): Promise<DeliveryStoreWriteResult>
  startDeliveryOperation(
    namespaceId: string,
    deliveryId: string,
    operationId: string,
    adapterCorrelation: unknown
  ): Promise<DeliveryStoreWriteResult>
  reconcileDeliveryOperation(
    namespaceId: string,
    deliveryId: string,
    operationId: string,
    observation: DeliveryOperationObservation
  ): Promise<DeliveryStoreWriteResult>
  hasIndeterminateOperation(namespaceId: string, deliveryId: string): Promise<boolean>
  updateSnapshot(
    namespaceId: string,
    deliveryId: string,
    patch: Record<string, unknown>,
    operationInput: { kind: string; idempotencyKey: string; facts?: unknown }
  ): Promise<DeliveryStoreWriteResult>
}

export class FilesystemDeliveryRepository implements DeliveryRepository {
  constructor(private readonly store: DeliveryStoreLike) {}

  read(namespaceId: string, deliveryId: string): Promise<DeliverySnapshot | null> {
    return this.store.read(namespaceId, deliveryId)
  }

  create(input: Record<string, unknown>): Promise<DeliveryStoreWriteResult> {
    return this.store.create(input)
  }

  promote(input: DeliveryStorePromoteInput): Promise<DeliveryStoreWriteResult> {
    return this.store.promote(input)
  }

  readWithOperations(
    namespaceId: string,
    deliveryId: string
  ): Promise<
    | (DeliverySnapshot & { deliveryOperations: DeliveryJournalRecord[]; rollbackRequests: DeliveryJournalRecord[] })
    | null
  > {
    return this.store.readWithOperations(namespaceId, deliveryId)
  }

  inspectDeliveryOperations(namespaceId: string, deliveryId: string): Promise<DeliveryOperationProjection> {
    return this.store.inspectDeliveryOperations(namespaceId, deliveryId)
  }

  createRollbackRequest(input: DeliveryStoreRollbackRequestInput): Promise<DeliveryStoreWriteResult> {
    return this.store.createRollbackRequest(input)
  }

  approveRollbackRequest(
    namespaceId: string,
    deliveryId: string,
    rollbackRequestId: string,
    approval: DeliveryStoreRollbackApprovalInput
  ): Promise<DeliveryStoreWriteResult> {
    return this.store.approveRollbackRequest(namespaceId, deliveryId, rollbackRequestId, approval)
  }

  createDeliveryOperation(input: DeliveryStoreOperationInput): Promise<DeliveryStoreWriteResult> {
    return this.store.createDeliveryOperation(input)
  }

  recordDeliveryOperation(
    namespaceId: string,
    deliveryId: string,
    operationId: string,
    transition: DeliveryOperationTransitionInput,
    options?: { inspectedObservation?: DeliveryOperationObservation }
  ): Promise<DeliveryStoreWriteResult> {
    return this.store.recordDeliveryOperation(namespaceId, deliveryId, operationId, transition, options)
  }

  startDeliveryOperation(
    namespaceId: string,
    deliveryId: string,
    operationId: string,
    adapterCorrelation: unknown
  ): Promise<DeliveryStoreWriteResult> {
    return this.store.startDeliveryOperation(namespaceId, deliveryId, operationId, adapterCorrelation)
  }

  reconcileDeliveryOperation(
    namespaceId: string,
    deliveryId: string,
    operationId: string,
    observation: DeliveryOperationObservation
  ): Promise<DeliveryStoreWriteResult> {
    return this.store.reconcileDeliveryOperation(namespaceId, deliveryId, operationId, observation)
  }

  hasIndeterminateOperation(namespaceId: string, deliveryId: string): Promise<boolean> {
    return this.store.hasIndeterminateOperation(namespaceId, deliveryId)
  }

  updateSnapshot(
    namespaceId: string,
    deliveryId: string,
    patch: Record<string, unknown>,
    operationInput: { kind: string; idempotencyKey: string; facts?: unknown }
  ): Promise<DeliveryStoreWriteResult> {
    return this.store.updateSnapshot(namespaceId, deliveryId, patch, operationInput)
  }
}

/** Wires a filesystem delivery repository around a concrete store. */
export function createFilesystemDeliveryRepository(store: DeliveryStoreLike): FilesystemDeliveryRepository {
  return new FilesystemDeliveryRepository(store)
}
