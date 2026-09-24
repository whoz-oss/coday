import { workflowProjectionStorageId } from './workflow-projection-store.mjs'
import { projectFactoryOperationalMetrics } from './factory-operational-metrics-projector.mjs'
import {
  selectWorkflows,
  applyNamespaceMetricsLimit,
  projectNamespaceOperationalMetrics,
  NAMESPACE_METRICS_DEFAULT_LIMIT,
} from './factory-operational-metrics-namespace-projector.mjs'

/** Read-only store boundary for deterministic Factory operational metrics projection. */
export class FactoryOperationalMetricsService {
  constructor({ workflowStore, humanInteractionStore, deliveryStore, deliveryEvidenceStore }) {
    this.workflowStore = workflowStore
    this.humanInteractionStore = humanInteractionStore
    this.deliveryStore = deliveryStore
    this.deliveryEvidenceStore = deliveryEvidenceStore
  }

  /**
   * Project namespace-scoped operational metrics rollup (Phase 10D).
   *
   * Selects active workflows by scope (namespace/group/root), applies a
   * deterministic safety limit, then delegates metric computation to the
   * existing per-workflow projector. Returns an honest rollup with
   * truncation metadata and completeness propagation.
   *
   * @param {{ namespaceId: string, scope: 'namespace'|'group'|'root', groupId?: string, rootWorkflowId?: string, limit?: number, observedAt: string }} options
   */
  async projectNamespace({
    namespaceId,
    scope,
    groupId,
    rootWorkflowId,
    limit = NAMESPACE_METRICS_DEFAULT_LIMIT,
    observedAt,
  }) {
    if (observedAt === undefined) throw new TypeError('observedAt is required')

    const allActive = await this.workflowStore.list(namespaceId)

    const selection = selectWorkflows(allActive, scope, { groupId, rootWorkflowId })
    if (!selection.ok) return { ok: false, code: selection.code, message: selection.message }

    const { workflows, matchedWorkflowCount, includedWorkflowCount, truncated } = applyNamespaceMetricsLimit(
      selection.selected,
      limit
    )

    const timingsByWorkflowId = {},
      interactionsById = new Map(),
      deliveriesById = new Map(),
      evidenceById = new Map()
    for (const snapshot of workflows) {
      const id = snapshot.projection.workflowId
      timingsByWorkflowId[id] = await this.workflowStore.timing(namespaceId, id, observedAt)
      for (const interaction of await this.humanInteractionStore.list(
        namespaceId,
        workflowProjectionStorageId(namespaceId, id)
      )) {
        if (interaction?.interactionId && !interactionsById.has(interaction.interactionId))
          interactionsById.set(interaction.interactionId, interaction)
      }
      const deliveryId = snapshot?.instance?.deliveryRef?.deliveryId
      if (!deliveryId || deliveriesById.has(deliveryId)) continue
      const deliverySnapshot = await this.deliveryStore.read(namespaceId, deliveryId)
      if (!deliverySnapshot) continue
      const operationById = new Map()
      for (const operation of await this.deliveryStore.journal(namespaceId, deliveryId)) {
        if (!operation?.operationId) continue
        const records = operationById.get(operation.operationId) ?? []
        records.push(operation)
        operationById.set(operation.operationId, records)
      }
      deliveriesById.set(deliveryId, { snapshot: deliverySnapshot, operations: [...operationById.values()].flat() })
      for (const evidence of await this.deliveryEvidenceStore.list(namespaceId, deliveryId)) {
        if (evidence?.evidenceId && !evidenceById.has(evidence.evidenceId))
          evidenceById.set(evidence.evidenceId, evidence)
      }
    }

    const result = projectNamespaceOperationalMetrics({
      namespaceId,
      scope,
      selector: { groupId, rootWorkflowId },
      observedAt,
      workflows,
      timingsByWorkflowId,
      interactions: [...interactionsById.values()],
      deliveries: [...deliveriesById.values()],
      deliveryEvidence: [...evidenceById.values()],
      matchedWorkflowCount,
      includedWorkflowCount,
      truncated,
    })

    return { ok: true, data: result }
  }

  async project({ namespaceId, workflowId, scope = 'self', observedAt }) {
    if (observedAt === undefined) throw new TypeError('observedAt is required')
    const workflows = await this.workflowStore.list(namespaceId)
    const root = workflows.find((snapshot) => snapshot?.projection?.workflowId === workflowId)
    const included = root
      ? scope === 'descendants'
        ? [root, ...(await this.workflowStore.descendants(namespaceId, workflowId))]
        : [root]
      : []
    const unique = [...new Map(included.map((snapshot) => [snapshot.projection.workflowId, snapshot])).values()]
    const timingsByWorkflowId = {},
      interactions = [],
      deliveries = [],
      deliveryEvidence = []
    for (const snapshot of unique) {
      const id = snapshot.projection.workflowId
      timingsByWorkflowId[id] = await this.workflowStore.timing(namespaceId, id, observedAt)
      interactions.push(
        ...(await this.humanInteractionStore.list(namespaceId, workflowProjectionStorageId(namespaceId, id)))
      )
      const deliveryId = snapshot?.instance?.deliveryRef?.deliveryId
      if (!deliveryId) continue
      const deliverySnapshot = await this.deliveryStore.read(namespaceId, deliveryId)
      if (!deliverySnapshot) continue
      deliveries.push({
        snapshot: deliverySnapshot,
        operations: await this.deliveryStore.journal(namespaceId, deliveryId),
      })
      deliveryEvidence.push(...(await this.deliveryEvidenceStore.list(namespaceId, deliveryId)))
    }
    return projectFactoryOperationalMetrics({
      workflowId,
      scope,
      observedAt,
      workflows,
      timingsByWorkflowId,
      interactions,
      deliveries,
      deliveryEvidence,
    })
  }
}
