export const WORKFLOW_PROJECTION_EVENT = 'workflow-projection-updated'
export const WORKFLOW_PROJECTION_REMOVED_EVENT = 'workflow-projection-removed'
export const WORKFLOW_PROJECTION_RESTORED_EVENT = 'workflow-projection-restored'
export const WORKFLOW_PROJECTION_PURGED_EVENT = 'workflow-projection-purged'

/** Namespace-scoped, best-effort in-memory SSE hub for projection invalidation hints. */
export class WorkflowProjectionSseHub {
  constructor({ setIntervalFn = setInterval, clearIntervalFn = clearInterval, heartbeatMs = 30_000 } = {}) {
    this.clients = new Map()
    this.setIntervalFn = setIntervalFn
    this.clearIntervalFn = clearIntervalFn
    this.heartbeatMs = heartbeatMs
  }

  subscribe(namespaceId, writer, onClose) {
    if (!this.clients.has(namespaceId)) this.clients.set(namespaceId, new Set())
    const client = { writer, timer: null, closed: false }
    const remove = () => {
      if (client.closed) return
      client.closed = true
      if (client.timer !== null) this.clearIntervalFn(client.timer)
      const namespaceClients = this.clients.get(namespaceId)
      namespaceClients?.delete(client)
      if (namespaceClients?.size === 0) this.clients.delete(namespaceId)
    }
    this.clients.get(namespaceId).add(client)
    client.timer = this.setIntervalFn(() => {
      try { writer(': heartbeat\n\n') } catch { remove() }
    }, this.heartbeatMs)
    onClose(remove)
    return remove
  }

  publish(namespaceId, payload, event = WORKFLOW_PROJECTION_EVENT) {
    const frame = `event: ${event}\ndata: ${JSON.stringify(payload)}\n\n`
    for (const client of [...(this.clients.get(namespaceId) ?? [])]) {
      try { client.writer(frame) } catch {
        client.closed = true
        if (client.timer !== null) this.clearIntervalFn(client.timer)
        this.clients.get(namespaceId)?.delete(client)
      }
    }
    if (this.clients.get(namespaceId)?.size === 0) this.clients.delete(namespaceId)
  }

  size(namespaceId) { return this.clients.get(namespaceId)?.size ?? 0 }
}
