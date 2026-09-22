import { appendFile, mkdir, open, readFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'

async function appendDurable(path, value) {
  await mkdir(dirname(path), { recursive: true })
  await appendFile(path, `${JSON.stringify(value)}\n`, { encoding: 'utf8', mode: 0o600 })
  const handle = await open(path, 'r')
  try {
    await handle.sync()
  } finally {
    await handle.close()
  }
}

export class WorkflowResumeDispatchStore {
  constructor(dataRoot) {
    this.dataRoot = dataRoot
    this.locks = new Map()
  }
  path(namespaceId, storageId) {
    return join(this.dataRoot, 'workflows', namespaceId, storageId, 'resume-dispatches.jsonl')
  }
  _locked(key, action) {
    const prior = this.locks.get(key) ?? Promise.resolve()
    const operation = prior.then(action)
    const tail = operation.catch(() => {})
    this.locks.set(key, tail)
    return operation.finally(() => {
      if (this.locks.get(key) === tail) this.locks.delete(key)
    })
  }
  async events(namespaceId, storageId) {
    try {
      return (await readFile(this.path(namespaceId, storageId), 'utf8')).split('\n').filter(Boolean).map(JSON.parse)
    } catch (error) {
      if (error?.code === 'ENOENT') return []
      throw error
    }
  }
  async reserve(namespaceId, storageId, input) {
    return this._locked(`${namespaceId}\0${storageId}`, async () => {
      const events = await this.events(namespaceId, storageId),
        prior = events.find((event) => event.dispatchId === input.dispatchId)
      if (prior)
        return prior.state === 'delivered'
          ? { ok: true, idempotent: true, delivered: true }
          : { ok: false, code: 'DISPATCH_INDETERMINATE' }
      await appendDurable(this.path(namespaceId, storageId), {
        event: 'resume_dispatch_reserved',
        state: 'reserved',
        ...input,
        reservedAt: new Date().toISOString(),
      })
      return { ok: true, idempotent: false, delivered: false }
    })
  }
  async delivered(namespaceId, storageId, dispatchId) {
    return this._locked(`${namespaceId}\0${storageId}`, async () => {
      const events = await this.events(namespaceId, storageId)
      if (events.some((event) => event.dispatchId === dispatchId && event.state === 'delivered'))
        return { ok: true, idempotent: true }
      if (!events.some((event) => event.dispatchId === dispatchId && event.state === 'reserved'))
        return { ok: false, code: 'DISPATCH_NOT_RESERVED' }
      await appendDurable(this.path(namespaceId, storageId), {
        event: 'resume_dispatch_delivered',
        state: 'delivered',
        dispatchId,
        deliveredAt: new Date().toISOString(),
      })
      return { ok: true, idempotent: false }
    })
  }
}
