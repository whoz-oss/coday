import { createHash, randomBytes } from 'node:crypto'
import { appendFile, mkdir, open, readFile, readdir, rename, rm } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import {
  hashWorkflowProjection,
  validateWorkflowProjection,
  validateWorkflowProjectionId,
} from './workflow-projection.mjs'
import { projectWorkflowTiming } from './workflow-timing-projector.mjs'
import { createWorkflowInstance, workflowStartCommandHash } from './workflow-instance.mjs'
import {
  collectWorkflowDescendants,
  deriveWorkflowRelations,
  validateWorkflowRelationsInput,
  WORKFLOW_RELATION_ERROR_CODES,
} from './workflow-relations.mjs'
import {
  applyWorkflowTransition,
  evaluateHumanCheckpointOpen,
  evaluateHumanResolutionTransition,
  evaluateWorkflowTransition,
  transitionScopeHash,
  transitionSemanticHash,
} from './workflow-transition-policy.mjs'

export const WORKFLOW_STORE_ERROR_CODES = Object.freeze({
  INVALID_DATA_ROOT: 'INVALID_DATA_ROOT',
  INVALID_NAMESPACE_ID: 'INVALID_NAMESPACE_ID',
  REVISION_CONFLICT: 'REVISION_CONFLICT',
  WORKFLOW_REMOVED: 'WORKFLOW_REMOVED',
  WORKFLOW_NOT_FOUND: 'WORKFLOW_NOT_FOUND',
  INVALID_LIFECYCLE_TRANSITION: 'INVALID_LIFECYCLE_TRANSITION',
  WORKFLOW_ALREADY_EXISTS: 'WORKFLOW_ALREADY_EXISTS',
  WORKFLOW_IDENTITY_CONFLICT: 'WORKFLOW_IDENTITY_CONFLICT',
  CORRUPT_STORAGE: 'CORRUPT_STORAGE',
  STORAGE_FAILURE: 'STORAGE_FAILURE',
})

export class WorkflowProjectionStoreError extends Error {
  constructor(code, details = {}, cause) {
    super(code, cause ? { cause } : undefined)
    this.name = 'WorkflowProjectionStoreError'
    this.code = code
    this.details = details
  }
}

function storageId(namespaceId, workflowId) {
  return createHash('sha256').update(`${namespaceId}:${workflowId}`, 'utf8').digest('hex')
}
async function syncDirectory(path) {
  const directory = await open(path, 'r')
  try {
    await directory.sync()
  } finally {
    await directory.close()
  }
}
async function atomicJsonWrite(filePath, value) {
  await mkdir(dirname(filePath), { recursive: true })
  const temporary = `${filePath}.tmp-${process.pid}-${randomBytes(6).toString('hex')}`
  const handle = await open(temporary, 'wx', 0o600)
  try {
    await handle.writeFile(`${JSON.stringify(value)}\n`, 'utf8')
    await handle.sync()
  } finally {
    await handle.close()
  }
  await rename(temporary, filePath)
  await syncDirectory(dirname(filePath))
}
async function appendDurable(filePath, fact) {
  await appendFile(filePath, `${JSON.stringify(fact)}\n`, { encoding: 'utf8', mode: 0o600 })
  const handle = await open(filePath, 'r')
  try {
    await handle.sync()
  } finally {
    await handle.close()
  }
}
function changedStepIds(previous, next) {
  const before = new Map((previous?.steps ?? []).map((step) => [step.id, JSON.stringify(step)]))
  const after = new Map(next.steps.map((step) => [step.id, JSON.stringify(step)]))
  return [...new Set([...before.keys(), ...after.keys()])].filter((id) => before.get(id) !== after.get(id)).sort()
}
function transitionDelta(previous, next) {
  const before = new Map((previous?.steps ?? []).map((step) => [step.id, step]))
  const after = new Map(next.steps.map((step) => [step.id, step]))
  const steps = []
  for (const stepId of [...new Set([...before.keys(), ...after.keys()])].sort()) {
    const prior = before.get(stepId)
    const current = after.get(stepId)
    if (!current) steps.push({ kind: 'removed', stepId, status: { from: prior.status, to: null } })
    else if (!prior) steps.push({ kind: 'added', stepId, status: { from: null, to: current.status } })
    else if (prior.status !== current.status)
      steps.push({ kind: 'status_changed', stepId, status: { from: prior.status, to: current.status } })
  }
  return {
    workflow: !previous || previous.status !== next.status ? { from: previous?.status ?? null, to: next.status } : null,
    steps,
  }
}
function attribution(input, fields) {
  const result = {}
  for (const field of fields)
    if (typeof input?.[field] === 'string' && input[field].length > 0) result[field] = input[field]
  return result
}
function exists(path) {
  return readFile(path).then(
    () => true,
    (error) => {
      if (error?.code === 'ENOENT') return false
      throw error
    }
  )
}

/** Dependency-free, namespace-scoped, mono-process WorkflowProjection v1 store. */
export class WorkflowProjectionStore {
  constructor(dataRoot, { lifecycleFault = async () => {} } = {}) {
    if (typeof dataRoot !== 'string' || dataRoot.length === 0)
      throw new WorkflowProjectionStoreError(WORKFLOW_STORE_ERROR_CODES.INVALID_DATA_ROOT)
    this.dataRoot = dataRoot
    this.locks = new Map()
    this.lifecycleFault = lifecycleFault
  }

  paths(namespaceId, workflowId) {
    const namespace = validateWorkflowProjectionId(namespaceId, 'namespaceId')
    if (!namespace.ok)
      throw new WorkflowProjectionStoreError(WORKFLOW_STORE_ERROR_CODES.INVALID_NAMESPACE_ID, namespace.error)
    const workflow = validateWorkflowProjectionId(workflowId, 'workflowId')
    if (!workflow.ok) throw new WorkflowProjectionStoreError(WORKFLOW_STORE_ERROR_CODES.CORRUPT_STORAGE, workflow.error)
    const id = storageId(namespaceId, workflowId)
    const directory = join(this.dataRoot, 'workflows', namespaceId, id)
    const trashDirectory = join(this.dataRoot, 'trash', id)
    return {
      storageId: id,
      directory,
      snapshot: join(directory, 'projection.json'),
      events: join(directory, 'events.jsonl'),
      pending: join(directory, 'pending.json'),
      trashDirectory,
      trashSnapshot: join(trashDirectory, 'projection.json'),
      trashEvents: join(trashDirectory, 'events.jsonl'),
      tombstone: join(this.dataRoot, 'tombstones', `${id}.json`),
    }
  }

  async initialize() {
    await mkdir(join(this.dataRoot, 'workflows'), { recursive: true })
    await mkdir(join(this.dataRoot, 'trash'), { recursive: true })
    await mkdir(join(this.dataRoot, 'tombstones'), { recursive: true })
  }
  _locked(namespaceId, workflowId, action) {
    const key = `${namespaceId}\u0000${workflowId}`
    const prior = this.locks.get(key) ?? Promise.resolve()
    const operation = prior.then(action)
    const tail = operation.catch(() => {})
    this.locks.set(key, tail)
    return operation.finally(() => {
      if (this.locks.get(key) === tail) this.locks.delete(key)
    })
  }
  async _readJson(path, missing = null) {
    try {
      return JSON.parse(await readFile(path, 'utf8'))
    } catch (error) {
      if (error?.code === 'ENOENT') return missing
      throw new WorkflowProjectionStoreError(WORKFLOW_STORE_ERROR_CODES.CORRUPT_STORAGE, {}, error)
    }
  }
  _validateTombstone(value, paths) {
    if (
      !value ||
      typeof value !== 'object' ||
      Array.isArray(value) ||
      Object.keys(value).some(
        (key) =>
          ![
            'namespaceId',
            'workflowId',
            'storageId',
            'removedAt',
            'removedBy',
            'actorId',
            'generation',
            'lifecycleState',
            'purgedAt',
            'purgedBy',
          ].includes(key)
      ) ||
      value.namespaceId === undefined ||
      value.workflowId === undefined ||
      value.storageId !== paths.storageId ||
      value.storageId !== storageId(value.namespaceId, value.workflowId) ||
      !Number.isSafeInteger(value.generation) ||
      value.generation < 1 ||
      !['removing', 'removed', 'purged'].includes(value.lifecycleState) ||
      (value.lifecycleState === 'purged') !== (value.purgedAt !== undefined) ||
      Number.isNaN(Date.parse(value.removedAt)) ||
      (value.purgedAt !== undefined && Number.isNaN(Date.parse(value.purgedAt)))
    )
      throw new WorkflowProjectionStoreError(WORKFLOW_STORE_ERROR_CODES.CORRUPT_STORAGE)
    return value
  }
  async _tombstone(paths) {
    const value = await this._readJson(paths.tombstone)
    return value ? this._validateTombstone(value, paths) : null
  }
  async _readSnapshot(paths) {
    try {
      const snapshot = JSON.parse(await readFile(paths.snapshot, 'utf8'))
      if (
        !Number.isSafeInteger(snapshot.revision) ||
        snapshot.revision < 1 ||
        hashWorkflowProjection(snapshot.projection) !== snapshot.projectionHash
      )
        throw new Error('invalid snapshot')
      return snapshot
    } catch (error) {
      if (error?.code === 'ENOENT') return null
      throw new WorkflowProjectionStoreError(WORKFLOW_STORE_ERROR_CODES.CORRUPT_STORAGE, {}, error)
    }
  }
  async _recover(paths) {
    let pending
    try {
      pending = JSON.parse(await readFile(paths.pending, 'utf8'))
    } catch (error) {
      if (error?.code === 'ENOENT') return
      throw new WorkflowProjectionStoreError(WORKFLOW_STORE_ERROR_CODES.CORRUPT_STORAGE, {}, error)
    }
    let journal = ''
    try {
      journal = await readFile(paths.events, 'utf8')
    } catch (error) {
      if (error?.code !== 'ENOENT') throw error
    }
    const committed = journal
      .trim()
      .split('\n')
      .filter(Boolean)
      .some((line) => {
        const fact = JSON.parse(line)
        return fact.revision === pending.revision && fact.projectionHash === pending.projectionHash
      })
    if (committed) await atomicJsonWrite(paths.snapshot, pending)
    await rm(paths.pending, { force: true })
  }

  async read(namespaceId, workflowId) {
    const paths = this.paths(namespaceId, workflowId)
    await this._recover(paths)
    return this._readSnapshot(paths)
  }
  async lookup(namespaceId, workflowId) {
    const paths = this.paths(namespaceId, workflowId)
    const tombstone = await this._tombstone(paths)
    if (tombstone) {
      return { state: tombstone.lifecycleState === 'purged' ? 'purged' : 'removed', workflowId }
    }
    await this._recover(paths)
    const snapshot = await this._readSnapshot(paths)
    return snapshot ? { state: 'existing', workflowId, snapshot } : { state: 'absent', workflowId }
  }
  async timing(namespaceId, workflowId, now = new Date()) {
    const paths = this.paths(namespaceId, workflowId)
    await this._recover(paths)
    const snapshot = await this._readSnapshot(paths)
    if (!snapshot) return null
    let journal = ''
    try {
      journal = await readFile(paths.events, 'utf8')
    } catch (error) {
      if (error?.code !== 'ENOENT') throw error
    }
    const facts = journal
      .split('\n')
      .filter(Boolean)
      .map((line) => {
        try {
          return JSON.parse(line)
        } catch {
          return { kind: 'projection_published' }
        }
      })
    return projectWorkflowTiming(facts, now, { snapshot })
  }
  async list(namespaceId) {
    return this._listRoot(namespaceId, false)
  }
  async descendants(namespaceId, workflowId) {
    return collectWorkflowDescendants(await this.list(namespaceId), workflowId)
  }
  async listRemoved(namespaceId) {
    return this._listRoot(namespaceId, true)
  }
  async _listRoot(namespaceId, removed) {
    const namespace = validateWorkflowProjectionId(namespaceId, 'namespaceId')
    if (!namespace.ok)
      throw new WorkflowProjectionStoreError(WORKFLOW_STORE_ERROR_CODES.INVALID_NAMESPACE_ID, namespace.error)
    if (!removed) {
      const root = join(this.dataRoot, 'workflows', namespaceId)
      let entries
      try {
        entries = await readdir(root, { withFileTypes: true })
      } catch (error) {
        if (error?.code === 'ENOENT') return []
        throw error
      }
      const snapshots = []
      for (const entry of entries.filter((item) => item.isDirectory()).sort((a, b) => a.name.localeCompare(b.name))) {
        const p = {
          directory: join(root, entry.name),
          snapshot: join(root, entry.name, 'projection.json'),
          events: join(root, entry.name, 'events.jsonl'),
          pending: join(root, entry.name, 'pending.json'),
        }
        await this._recover(p)
        const snapshot = await this._readSnapshot(p)
        if (snapshot) snapshots.push(snapshot)
      }
      return snapshots.sort((a, b) => a.projection.workflowId.localeCompare(b.projection.workflowId))
    }
    let entries
    try {
      entries = await readdir(join(this.dataRoot, 'tombstones'), { withFileTypes: true })
    } catch (error) {
      if (error?.code === 'ENOENT') return []
      throw error
    }
    const snapshots = []
    for (const entry of entries.filter((item) => item.isFile() && item.name.endsWith('.json'))) {
      const tombstone = await this._readJson(join(this.dataRoot, 'tombstones', entry.name))
      if (!tombstone || typeof tombstone.namespaceId !== 'string' || typeof tombstone.workflowId !== 'string')
        throw new WorkflowProjectionStoreError(WORKFLOW_STORE_ERROR_CODES.CORRUPT_STORAGE)
      const tombstonePaths = this.paths(tombstone.namespaceId, tombstone.workflowId)
      this._validateTombstone(tombstone, tombstonePaths)
      if (tombstone.namespaceId !== namespaceId || tombstone.lifecycleState === 'purged') continue
      if (tombstone.lifecycleState !== 'removed')
        throw new WorkflowProjectionStoreError(WORKFLOW_STORE_ERROR_CODES.CORRUPT_STORAGE)
      const paths = tombstonePaths
      const snapshot = await this._readJson(paths.trashSnapshot)
      if (!snapshot || hashWorkflowProjection(snapshot.projection) !== snapshot.projectionHash)
        throw new WorkflowProjectionStoreError(WORKFLOW_STORE_ERROR_CODES.CORRUPT_STORAGE)
      snapshots.push(snapshot)
    }
    return snapshots.sort((a, b) => a.projection.workflowId.localeCompare(b.projection.workflowId))
  }

  async start(namespaceId, command, definition, controllerExecution) {
    return this._locked(namespaceId, command.workflowId, () =>
      this._start(namespaceId, command, definition, controllerExecution)
    )
  }
  async _start(namespaceId, command, definition, controllerExecution) {
    const paths = this.paths(namespaceId, command.workflowId)
    try {
      if (await this._tombstone(paths))
        return { ok: false, error: { code: WORKFLOW_STORE_ERROR_CODES.WORKFLOW_REMOVED } }
      await this._recover(paths)
      const current = await this._readSnapshot(paths)
      const validatedRelations = validateWorkflowRelationsInput(command.relations)
      if (!validatedRelations.ok) return validatedRelations
      if (validatedRelations.relations.parentWorkflowId === command.workflowId)
        return { ok: false, error: { code: WORKFLOW_RELATION_ERROR_CODES.WORKFLOW_RELATION_CYCLE } }
      let parent = null
      if (validatedRelations.relations.parentWorkflowId) {
        parent = await this.lookup(namespaceId, validatedRelations.relations.parentWorkflowId)
        if (parent.state !== 'existing')
          return { ok: false, error: { code: WORKFLOW_RELATION_ERROR_CODES.PARENT_WORKFLOW_NOT_FOUND } }
        if (
          collectWorkflowDescendants(await this.list(namespaceId), command.workflowId).some(
            (item) => item.projection.workflowId === validatedRelations.relations.parentWorkflowId
          )
        )
          return { ok: false, error: { code: WORKFLOW_RELATION_ERROR_CODES.WORKFLOW_RELATION_CYCLE } }
      }
      const commandWithRelations = {
        ...command,
        relations: deriveWorkflowRelations(command.workflowId, validatedRelations.relations, parent?.snapshot),
      }
      const commandHash = workflowStartCommandHash(commandWithRelations, definition)
      if (current) {
        if (current.governanceMode !== 'governed')
          return {
            ok: false,
            error: {
              code: WORKFLOW_STORE_ERROR_CODES.WORKFLOW_ALREADY_EXISTS,
              details: { governanceMode: 'declarative' },
            },
          }
        if (current.creationCommandHash === commandHash)
          return { ok: true, created: false, idempotent: true, snapshot: current }
        return { ok: false, error: { code: WORKFLOW_STORE_ERROR_CODES.WORKFLOW_IDENTITY_CONFLICT } }
      }
      const observedAt = new Date().toISOString()
      const created = createWorkflowInstance(commandWithRelations, definition, controllerExecution, observedAt)
      const projectionHash = hashWorkflowProjection(created.projection)
      const snapshot = {
        revision: 1,
        projectionHash,
        governanceMode: 'governed',
        definitionVersion: definition.version,
        definitionHash: definition.definitionHash,
        creationCommandHash: created.creationCommandHash,
        controllerExecution: created.instance.controllerExecution,
        instance: created.instance,
        projection: created.projection,
      }
      await mkdir(paths.directory, { recursive: true })
      await atomicJsonWrite(paths.pending, snapshot)
      await appendDurable(paths.events, {
        kind: 'workflow_instance_created',
        revision: 1,
        projectionHash,
        definitionVersion: definition.version,
        definitionHash: definition.definitionHash,
        observedAt,
        timestamp: observedAt,
        transitionDelta: transitionDelta(null, created.projection),
        controllerExecution: created.instance.controllerExecution,
        ...attribution(controllerExecution, ['actorId', 'agentId', 'caseId', 'threadId']),
      })
      await atomicJsonWrite(paths.snapshot, snapshot)
      await rm(paths.pending, { force: true })
      return { ok: true, created: true, idempotent: false, snapshot }
    } catch (error) {
      if (error instanceof WorkflowProjectionStoreError) throw error
      throw new WorkflowProjectionStoreError(WORKFLOW_STORE_ERROR_CODES.STORAGE_FAILURE, {}, error)
    }
  }

  async transition(
    namespaceId,
    request,
    definition,
    evidence,
    controllerExecution,
    { fault = async () => {}, policy = evaluateWorkflowTransition } = {}
  ) {
    return this._locked(namespaceId, request.workflowId, () =>
      this._transition(namespaceId, request, definition, evidence, controllerExecution, fault, policy)
    )
  }
  async openHumanCheckpoint(namespaceId, request, definition, controllerExecution, options = {}) {
    return this.transition(namespaceId, request, definition, [], controllerExecution, {
      ...options,
      policy: evaluateHumanCheckpointOpen,
    })
  }
  async resolveHumanCheckpoint(namespaceId, request, definition, evidence, humanExecution, options = {}) {
    return this.transition(namespaceId, request, definition, evidence, humanExecution, {
      ...options,
      policy: evaluateHumanResolutionTransition,
    })
  }
  async _transition(namespaceId, request, definition, evidence, controllerExecution, fault, policy) {
    const paths = this.paths(namespaceId, request.workflowId),
      observedAt = new Date().toISOString(),
      execution = { ...controllerExecution, namespaceId }
    try {
      const tombstone = await this._tombstone(paths)
      if (tombstone)
        return {
          ok: false,
          error: { code: tombstone.lifecycleState === 'purged' ? 'WORKFLOW_PURGED' : 'WORKFLOW_REMOVED' },
        }
      await this._recover(paths)
      const current = await this._readSnapshot(paths)
      let journal = ''
      try {
        journal = await readFile(paths.events, 'utf8')
      } catch (error) {
        if (error?.code !== 'ENOENT') throw error
      }
      const facts = journal.split('\n').filter(Boolean).map(JSON.parse)
      if (request.idempotencyKey) {
        const scopeHash = transitionScopeHash(namespaceId, request, execution),
          semanticHash = transitionSemanticHash(request)
        const prior = facts.find((f) => f.kind === 'transition_requested' && f.idempotency?.scopeHash === scopeHash)
        if (prior) {
          if (prior.idempotency.semanticHash !== semanticHash)
            return { ok: false, error: { code: 'IDEMPOTENCY_KEY_COLLISION' } }
          const accepted = facts.find((f) => f.kind === 'transition_accepted' && f.requestId === prior.requestId)
          const rejected = facts.find((f) => f.kind === 'transition_rejected' && f.requestId === prior.requestId)
          return accepted
            ? { ok: true, changed: false, idempotent: true, snapshot: current, requestId: prior.requestId }
            : {
                ok: false,
                idempotent: true,
                requestId: prior.requestId,
                error: { code: rejected?.policyCode ?? 'TRANSITION_REJECTED' },
              }
        }
      }
      const from = current?.instance?.steps?.find((s) => s.id === request.stepId)?.status ?? null
      const requestFact = {
        kind: 'transition_requested',
        requestId: request.requestId,
        stepId: request.stepId,
        from,
        to: request.requestedStatus,
        evidenceIds: [...request.evidenceIds],
        observedAt,
        timestamp: observedAt,
        ...attribution(controllerExecution, ['actorId', 'agentId', 'caseId', 'threadId']),
        ...(request.idempotencyKey
          ? {
              idempotency: {
                scopeHash: transitionScopeHash(namespaceId, request, execution),
                semanticHash: transitionSemanticHash(request),
              },
            }
          : {}),
      }
      await mkdir(paths.directory, { recursive: true })
      await appendDurable(paths.events, requestFact)
      const decision = policy({ request, snapshot: current, definition, evidence, execution })
      if (!decision.allowed) {
        await appendDurable(paths.events, {
          kind: 'transition_rejected',
          requestId: request.requestId,
          policyCode: decision.code,
          observedRevision: current?.revision ?? 0,
          observedAt,
          timestamp: observedAt,
          ...attribution(controllerExecution, ['actorId', 'agentId', 'caseId', 'threadId']),
        })
        return { ok: false, decision, error: { code: decision.code }, requestId: request.requestId }
      }
      const applied = applyWorkflowTransition(current, definition, request, observedAt),
        projectionHash = hashWorkflowProjection(applied.projection)
      const snapshot = {
        ...current,
        revision: applied.revision,
        projectionHash,
        instance: applied.instance,
        projection: applied.projection,
        controllerExecution: { ...controllerExecution, observedAt },
      }
      await atomicJsonWrite(paths.pending, snapshot)
      await fault('after-pending', { requestId: request.requestId })
      await appendDurable(paths.events, {
        kind: 'transition_accepted',
        requestId: request.requestId,
        revision: snapshot.revision,
        projectionHash,
        observedAt,
        timestamp: observedAt,
        transitionDelta: transitionDelta(current.projection, snapshot.projection),
        ...attribution(controllerExecution, ['actorId', 'agentId', 'caseId', 'threadId']),
      })
      await fault('after-accepted', { requestId: request.requestId })
      await atomicJsonWrite(paths.snapshot, snapshot)
      await rm(paths.pending, { force: true })
      return { ok: true, changed: true, idempotent: false, snapshot, requestId: request.requestId, decision }
    } catch (error) {
      if (error instanceof WorkflowProjectionStoreError) throw error
      throw new WorkflowProjectionStoreError(WORKFLOW_STORE_ERROR_CODES.STORAGE_FAILURE, {}, error)
    }
  }

  async bindEnvironment(namespaceId, workflowId, environmentRef) {
    return this._locked(namespaceId, workflowId, async () => {
      if (
        !environmentRef ||
        typeof environmentRef.environmentId !== 'string' ||
        !/^([0-9a-f]{64})$/i.test(environmentRef.environmentHash ?? '')
      )
        return { ok: false, error: { code: 'INVALID_ENVIRONMENT_REFERENCE' } }
      const paths = this.paths(namespaceId, workflowId)
      await this._recover(paths)
      const current = await this._readSnapshot(paths)
      if (!current?.instance) return { ok: false, error: { code: WORKFLOW_STORE_ERROR_CODES.WORKFLOW_NOT_FOUND } }
      const existing = current.instance.environmentRef
      if (existing) {
        return JSON.stringify(existing) === JSON.stringify(environmentRef)
          ? { ok: true, changed: false, snapshot: current }
          : { ok: false, error: { code: 'ENVIRONMENT_ALREADY_BOUND' } }
      }
      const observedAt = new Date().toISOString(),
        instance = { ...current.instance, environmentRef, updatedAt: observedAt },
        snapshot = { ...current, instance }
      await atomicJsonWrite(paths.pending, snapshot)
      await appendDurable(paths.events, {
        kind: 'workflow_environment_bound',
        revision: current.revision,
        environmentId: environmentRef.environmentId,
        environmentHash: environmentRef.environmentHash,
        observedAt,
        timestamp: observedAt,
      })
      await atomicJsonWrite(paths.snapshot, snapshot)
      await rm(paths.pending, { force: true })
      return { ok: true, changed: true, snapshot }
    })
  }

  async bindDelivery(namespaceId, workflowId, deliveryRef) {
    return this._locked(namespaceId, workflowId, async () => {
      if (
        !deliveryRef ||
        typeof deliveryRef.deliveryId !== 'string' ||
        typeof deliveryRef.definitionHash !== 'string' ||
        !/^([0-9a-f]{64})$/i.test(deliveryRef.definitionHash)
      )
        return { ok: false, error: { code: 'INVALID_DELIVERY_REFERENCE' } }
      const paths = this.paths(namespaceId, workflowId)
      await this._recover(paths)
      const current = await this._readSnapshot(paths)
      if (!current?.instance) return { ok: false, error: { code: WORKFLOW_STORE_ERROR_CODES.WORKFLOW_NOT_FOUND } }
      const existing = current.instance.deliveryRef
      if (existing) {
        return JSON.stringify(existing) === JSON.stringify(deliveryRef)
          ? { ok: true, changed: false, snapshot: current }
          : { ok: false, error: { code: 'DELIVERY_ALREADY_BOUND' } }
      }
      const observedAt = new Date().toISOString(),
        instance = { ...current.instance, deliveryRef, updatedAt: observedAt },
        snapshot = { ...current, instance }
      await atomicJsonWrite(paths.pending, snapshot)
      await appendDurable(paths.events, {
        kind: 'workflow_delivery_bound',
        revision: current.revision,
        deliveryId: deliveryRef.deliveryId,
        definitionHash: deliveryRef.definitionHash,
        observedAt,
        timestamp: observedAt,
      })
      await atomicJsonWrite(paths.snapshot, snapshot)
      await rm(paths.pending, { force: true })
      return { ok: true, changed: true, snapshot }
    })
  }

  async publish(namespaceId, command, controllerExecution) {
    const validated = validateWorkflowProjection(command)
    if (!validated.ok) return validated
    return this._locked(namespaceId, validated.projection.workflowId, () =>
      this._publish(namespaceId, validated, controllerExecution)
    )
  }
  async _publish(namespaceId, validated, controllerExecution) {
    const paths = this.paths(namespaceId, validated.projection.workflowId)
    try {
      if (await this._tombstone(paths))
        return { ok: false, error: { code: WORKFLOW_STORE_ERROR_CODES.WORKFLOW_REMOVED } }
      await mkdir(paths.directory, { recursive: true })
      await this._recover(paths)
      const current = await this._readSnapshot(paths)
      if (current?.governanceMode === 'governed')
        return { ok: false, error: { code: 'GOVERNED_WORKFLOW_REQUIRES_TRANSITION' } }
      const currentRevision = current?.revision ?? 0
      if (validated.expectedRevision !== undefined && validated.expectedRevision !== currentRevision)
        return {
          ok: false,
          error: {
            code: WORKFLOW_STORE_ERROR_CODES.REVISION_CONFLICT,
            details: { expectedRevision: validated.expectedRevision, actualRevision: currentRevision },
          },
        }
      const projectionHash = hashWorkflowProjection(validated.projection)
      if (current?.projectionHash === projectionHash) return { ok: true, changed: false, snapshot: current }
      const revision = currentRevision + 1
      const observedAt = new Date().toISOString()
      const trustedControllerExecution = { ...controllerExecution, observedAt }
      const snapshot = {
        revision,
        projectionHash,
        controllerExecution: trustedControllerExecution,
        projection: validated.projection,
      }
      await atomicJsonWrite(paths.pending, snapshot)
      await appendDurable(paths.events, {
        kind: current ? 'projection_published' : 'projection_created',
        revision,
        projectionHash,
        changedStepIds: changedStepIds(current?.projection, validated.projection),
        observedAt,
        timestamp: observedAt,
        transitionDelta: transitionDelta(current?.projection, validated.projection),
        controllerExecution: trustedControllerExecution,
        ...attribution(controllerExecution, ['actorId', 'agentId', 'caseId', 'threadId']),
      })
      await atomicJsonWrite(paths.snapshot, snapshot)
      await rm(paths.pending, { force: true })
      return { ok: true, changed: true, snapshot }
    } catch (error) {
      if (error instanceof WorkflowProjectionStoreError) throw error
      throw new WorkflowProjectionStoreError(WORKFLOW_STORE_ERROR_CODES.STORAGE_FAILURE, {}, error)
    }
  }

  async remove(namespaceId, workflowId, actor = {}) {
    return this._locked(namespaceId, workflowId, () => this._remove(namespaceId, workflowId, actor))
  }
  async _hasLifecycleFact(eventsPath, kind, generation) {
    let journal
    try {
      journal = await readFile(eventsPath, 'utf8')
    } catch (error) {
      if (error?.code === 'ENOENT') return false
      throw error
    }
    try {
      return journal
        .split('\n')
        .filter(Boolean)
        .map(JSON.parse)
        .some((fact) => fact.kind === kind && fact.generation === generation)
    } catch (error) {
      throw new WorkflowProjectionStoreError(WORKFLOW_STORE_ERROR_CODES.CORRUPT_STORAGE, {}, error)
    }
  }
  async _remove(namespaceId, workflowId, actor) {
    const paths = this.paths(namespaceId, workflowId)
    try {
      let tombstone = await this._tombstone(paths)
      if (!tombstone) {
        await this._recover(paths)
        await this.lifecycleFault('after-recovery', { namespaceId, workflowId })
      }
      let active = await this._readSnapshot(paths)
      let trashed = await this._readJson(paths.trashSnapshot)
      if (!tombstone && !active) return { ok: false, error: { code: WORKFLOW_STORE_ERROR_CODES.WORKFLOW_NOT_FOUND } }
      if (tombstone && tombstone.lifecycleState !== 'removing')
        return { ok: false, error: { code: WORKFLOW_STORE_ERROR_CODES.INVALID_LIFECYCLE_TRANSITION } }
      if (!tombstone) {
        tombstone = {
          namespaceId,
          workflowId,
          storageId: paths.storageId,
          removedAt: new Date().toISOString(),
          generation: 1,
          lifecycleState: 'removing',
          ...attribution(actor, ['removedBy', 'actorId']),
        }
        await atomicJsonWrite(paths.tombstone, tombstone)
        await this.lifecycleFault('after-tombstone', { namespaceId, workflowId })
      }
      if (active && trashed) throw new WorkflowProjectionStoreError(WORKFLOW_STORE_ERROR_CODES.CORRUPT_STORAGE)
      if (!active && !trashed) throw new WorkflowProjectionStoreError(WORKFLOW_STORE_ERROR_CODES.CORRUPT_STORAGE)
      if (active) {
        await rename(paths.directory, paths.trashDirectory)
        await syncDirectory(dirname(paths.directory))
        await syncDirectory(dirname(paths.trashDirectory))
        await this.lifecycleFault('after-rename', { namespaceId, workflowId })
        trashed = active
      }
      if (!(await this._hasLifecycleFact(paths.trashEvents, 'workflow_removed', tombstone.generation))) {
        await appendDurable(paths.trashEvents, {
          kind: 'workflow_removed',
          generation: tombstone.generation,
          timestamp: tombstone.removedAt,
          ...attribution(tombstone, ['removedBy', 'actorId']),
        })
      }
      await this.lifecycleFault('after-removal-fact', { namespaceId, workflowId })
      const completed = { ...tombstone, lifecycleState: 'removed' }
      await atomicJsonWrite(paths.tombstone, completed)
      await this.lifecycleFault('after-finalize', { namespaceId, workflowId })
      return { ok: true, snapshot: trashed }
    } catch (error) {
      if (error instanceof WorkflowProjectionStoreError) throw error
      throw new WorkflowProjectionStoreError(WORKFLOW_STORE_ERROR_CODES.STORAGE_FAILURE, {}, error)
    }
  }

  async restore(namespaceId, workflowId, actor = {}) {
    return this._locked(namespaceId, workflowId, () => this._restore(namespaceId, workflowId, actor))
  }
  async _restore(namespaceId, workflowId, actor) {
    const paths = this.paths(namespaceId, workflowId)
    try {
      const tombstone = await this._tombstone(paths)
      if (!tombstone || tombstone.lifecycleState !== 'removed')
        return {
          ok: false,
          error: {
            code: tombstone
              ? WORKFLOW_STORE_ERROR_CODES.INVALID_LIFECYCLE_TRANSITION
              : WORKFLOW_STORE_ERROR_CODES.WORKFLOW_NOT_FOUND,
          },
        }
      let active = await this._readSnapshot(paths)
      const trashed = await this._readJson(paths.trashSnapshot)
      if (!active && !trashed) throw new WorkflowProjectionStoreError(WORKFLOW_STORE_ERROR_CODES.CORRUPT_STORAGE)
      if (!active) {
        await rename(paths.trashDirectory, paths.directory)
        await syncDirectory(dirname(paths.trashDirectory))
        await syncDirectory(dirname(paths.directory))
        active = await this._readSnapshot(paths)
      }
      await appendDurable(paths.events, {
        kind: 'workflow_restored',
        timestamp: new Date().toISOString(),
        ...attribution(actor, ['restoredBy', 'actorId']),
      })
      await rm(paths.tombstone)
      await syncDirectory(dirname(paths.tombstone))
      return { ok: true, snapshot: active }
    } catch (error) {
      if (error instanceof WorkflowProjectionStoreError) throw error
      throw new WorkflowProjectionStoreError(WORKFLOW_STORE_ERROR_CODES.STORAGE_FAILURE, {}, error)
    }
  }

  async purge(namespaceId, workflowId, actor = {}) {
    return this._locked(namespaceId, workflowId, () => this._purge(namespaceId, workflowId, actor))
  }
  async _purge(namespaceId, workflowId, actor) {
    const paths = this.paths(namespaceId, workflowId)
    try {
      const tombstone = await this._tombstone(paths)
      if (!tombstone) return { ok: false, error: { code: WORKFLOW_STORE_ERROR_CODES.WORKFLOW_NOT_FOUND } }
      if (!['removed', 'purged'].includes(tombstone.lifecycleState) || (await this._readSnapshot(paths)))
        return { ok: false, error: { code: WORKFLOW_STORE_ERROR_CODES.INVALID_LIFECYCLE_TRANSITION } }
      if (tombstone.lifecycleState !== 'purged' && !(await exists(paths.trashSnapshot)))
        throw new WorkflowProjectionStoreError(WORKFLOW_STORE_ERROR_CODES.CORRUPT_STORAGE)
      const updated =
        tombstone.lifecycleState === 'purged'
          ? tombstone
          : {
              ...tombstone,
              lifecycleState: 'purged',
              purgedAt: new Date().toISOString(),
              ...attribution(actor, ['purgedBy']),
            }
      await atomicJsonWrite(paths.tombstone, updated)
      await rm(paths.trashDirectory, { recursive: true, force: true })
      await syncDirectory(dirname(paths.trashDirectory))
      return { ok: true, alreadyPurged: tombstone.lifecycleState === 'purged' }
    } catch (error) {
      if (error instanceof WorkflowProjectionStoreError) throw error
      throw new WorkflowProjectionStoreError(WORKFLOW_STORE_ERROR_CODES.STORAGE_FAILURE, {}, error)
    }
  }

  async clearPurgedTombstone(namespaceId, workflowId) {
    return this._locked(namespaceId, workflowId, async () => {
      const paths = this.paths(namespaceId, workflowId)
      const tombstone = await this._tombstone(paths)
      if (!tombstone) return { ok: false, error: { code: WORKFLOW_STORE_ERROR_CODES.WORKFLOW_NOT_FOUND } }
      if (
        tombstone.lifecycleState !== 'purged' ||
        (await exists(paths.trashSnapshot)) ||
        (await this._readSnapshot(paths))
      )
        return { ok: false, error: { code: WORKFLOW_STORE_ERROR_CODES.INVALID_LIFECYCLE_TRANSITION } }
      await rm(paths.tombstone)
      await syncDirectory(dirname(paths.tombstone))
      return { ok: true }
    })
  }

  async purgeRemovedBefore(namespaceId, cutoff, actor = {}) {
    const instant = cutoff instanceof Date ? cutoff : new Date(cutoff)
    if (Number.isNaN(instant.getTime())) return { ok: false, error: { code: 'INVALID_CUTOFF' } }
    const result = { ok: true, examined: 0, eligible: 0, purged: 0, alreadyPurged: 0, results: [] }
    let entries
    try {
      entries = await readdir(join(this.dataRoot, 'tombstones'), { withFileTypes: true })
      for (const entry of entries.filter((item) => item.isFile() && item.name.endsWith('.json'))) {
        const raw = await this._readJson(join(this.dataRoot, 'tombstones', entry.name))
        if (!raw || typeof raw.namespaceId !== 'string' || typeof raw.workflowId !== 'string')
          throw new WorkflowProjectionStoreError(WORKFLOW_STORE_ERROR_CODES.CORRUPT_STORAGE)
        const paths = this.paths(raw.namespaceId, raw.workflowId)
        const tombstone = this._validateTombstone(raw, paths)
        if (raw.namespaceId !== namespaceId) continue
        result.examined++
        if (tombstone.lifecycleState === 'purged') {
          result.alreadyPurged++
          continue
        }
        if (new Date(tombstone.removedAt) >= instant) continue
        result.eligible++
        const purgeResult = await this.purge(namespaceId, tombstone.workflowId, actor)
        if (!purgeResult.ok) throw new WorkflowProjectionStoreError(WORKFLOW_STORE_ERROR_CODES.CORRUPT_STORAGE)
        result.purged++
        result.results.push({ workflowId: tombstone.workflowId, purged: true })
      }
      return result
    } catch (error) {
      if (error instanceof WorkflowProjectionStoreError) throw error
      throw new WorkflowProjectionStoreError(WORKFLOW_STORE_ERROR_CODES.STORAGE_FAILURE, {}, error)
    }
  }
}

export function workflowProjectionStorageId(namespaceId, workflowId) {
  return storageId(namespaceId, workflowId)
}
