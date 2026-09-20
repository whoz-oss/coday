import { workflowProjectionStorageId } from './workflow-projection-store.mjs'
import { projectFactoryOperationalMetrics } from './factory-operational-metrics-projector.mjs'

/** Read-only store boundary for deterministic Factory operational metrics projection. */
export class FactoryOperationalMetricsService {
  constructor({ workflowStore, humanInteractionStore, deliveryStore, deliveryEvidenceStore }) {
    this.workflowStore = workflowStore
    this.humanInteractionStore = humanInteractionStore
    this.deliveryStore = deliveryStore
    this.deliveryEvidenceStore = deliveryEvidenceStore
  }

  async project({ namespaceId, workflowId, scope = 'self', observedAt }) {
    if (observedAt === undefined) throw new TypeError('observedAt is required')
    const workflows = await this.workflowStore.list(namespaceId)
    const root = workflows.find((snapshot) => snapshot?.projection?.workflowId === workflowId)
    const included = root ? (scope === 'descendants' ? [root, ...await this.workflowStore.descendants(namespaceId, workflowId)] : [root]) : []
    const unique = [...new Map(included.map((snapshot) => [snapshot.projection.workflowId, snapshot])).values()]
    const timingsByWorkflowId = {}, interactions = [], deliveries = [], deliveryEvidence = []
    for (const snapshot of unique) {
      const id = snapshot.projection.workflowId
      timingsByWorkflowId[id] = await this.workflowStore.timing(namespaceId, id, observedAt)
      interactions.push(...await this.humanInteractionStore.list(namespaceId, workflowProjectionStorageId(namespaceId, id)))
      const deliveryId = snapshot?.instance?.deliveryRef?.deliveryId
      if (!deliveryId) continue
      const deliverySnapshot = await this.deliveryStore.read(namespaceId, deliveryId)
      if (!deliverySnapshot) continue
      deliveries.push({ snapshot: deliverySnapshot, operations: await this.deliveryStore.journal(namespaceId, deliveryId) })
      deliveryEvidence.push(...await this.deliveryEvidenceStore.list(namespaceId, deliveryId))
    }
    return projectFactoryOperationalMetrics({ workflowId, scope, observedAt, workflows, timingsByWorkflowId, interactions, deliveries, deliveryEvidence })
  }
}
