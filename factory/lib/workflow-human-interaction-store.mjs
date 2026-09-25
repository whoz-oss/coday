import { randomUUID } from 'node:crypto'
import { appendFile, mkdir, open, readFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import {
  humanInteractionSemanticHash,
  openedInteractionRevision,
  validateHumanInteractionOpenInput,
} from '../runtime/factory-operational.mjs'

async function append(path, value) {
  await mkdir(dirname(path), { recursive: true })
  await appendFile(path, `${JSON.stringify(value)}\n`, { encoding: 'utf8', mode: 0o600 })
  const handle = await open(path, 'r')
  try {
    await handle.sync()
  } finally {
    await handle.close()
  }
}
export class WorkflowHumanInteractionError extends Error {
  constructor(code, cause) {
    super(code, cause ? { cause } : undefined)
    this.code = code
  }
}
export class WorkflowHumanInteractionStore {
  constructor(dataRoot, { fault = async () => {} } = {}) {
    this.dataRoot = dataRoot
    this.locks = new Map()
    this.fault = fault
  }
  path(namespaceId, storageId) {
    return join(this.dataRoot, 'workflows', namespaceId, storageId, 'human-interactions.jsonl')
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
      throw new WorkflowHumanInteractionError('INTERACTION_STORAGE_FAILURE', error)
    }
  }
  async list(namespaceId, storageId, { openOnly = false } = {}) {
    const projected = new Map()
    for (const event of await this.events(namespaceId, storageId)) {
      if (event.event === 'interaction_opening') {
        if (projected.has(event.interaction?.interactionId))
          throw new WorkflowHumanInteractionError('CORRUPT_INTERACTION_STORAGE')
        projected.set(event.interaction.interactionId, { ...event.interaction, status: 'opening' })
      } else if (event.event === 'interaction_opened') {
        const current = projected.get(event.interaction?.interactionId)
        if (current?.status === 'opening') {
          const revision = openedInteractionRevision(event)
          const validRevision =
            current.interactionType === 'retry'
              ? revision === current.expectedRevision
              : revision > current.expectedRevision
          if (!Number.isSafeInteger(revision) || !validRevision)
            throw new WorkflowHumanInteractionError('CORRUPT_INTERACTION_STORAGE')
          projected.set(event.interaction.interactionId, { ...current, status: 'open', revision })
        } else if (!current) {
          const revision = openedInteractionRevision(event)
          if (!Number.isSafeInteger(revision) || revision < 1)
            throw new WorkflowHumanInteractionError('CORRUPT_INTERACTION_STORAGE')
          projected.set(event.interaction.interactionId, { ...event.interaction, status: 'open', revision })
        } else throw new WorkflowHumanInteractionError('CORRUPT_INTERACTION_STORAGE')
      } else if (event.event === 'interaction_open_aborted') {
        const current = projected.get(event.interactionId)
        if (!current || current.status !== 'opening')
          throw new WorkflowHumanInteractionError('CORRUPT_INTERACTION_STORAGE')
        projected.set(event.interactionId, { ...current, status: 'aborted', errorCode: event.errorCode })
      } else if (event.event === 'interaction_transitioned') {
        const current = projected.get(event.interactionId)
        if (!current || current.status !== 'open')
          throw new WorkflowHumanInteractionError('CORRUPT_INTERACTION_STORAGE')
        projected.set(event.interactionId, {
          ...current,
          status: 'replied',
          reply: event.reply,
          actorId: event.actorId,
          repliedAt: event.repliedAt,
          evidenceId: event.evidenceId,
          transitionRequestId: event.transitionRequestId,
          revision: event.revision,
        })
      } else throw new WorkflowHumanInteractionError('CORRUPT_INTERACTION_STORAGE')
    }
    return [...projected.values()]
      .filter((item) => !openOnly || item.status === 'open')
      .sort((a, b) => a.openedAt.localeCompare(b.openedAt) || a.interactionId.localeCompare(b.interactionId))
  }
  async reconcileOpen(namespaceId, storageId, input, snapshot, { workflowFacts = [] } = {}) {
    return this._locked(`${namespaceId}\0${storageId}`, async () => {
      const items = await this.list(namespaceId, storageId)
      const candidates = items.filter(
        (item) =>
          item.workflowId === input.workflowId &&
          item.stepId === input.stepId &&
          ['opening', 'aborted'].includes(item.status)
      )
      if (candidates.length === 0) throw new WorkflowHumanInteractionError('INTERACTION_RECOVERY_NOT_FOUND')
      if (candidates.length !== 1) throw new WorkflowHumanInteractionError('INTERACTION_RECOVERY_AMBIGUOUS')
      const opening = candidates[0]
      if (opening.semanticHash !== humanInteractionSemanticHash(input))
        throw new WorkflowHumanInteractionError('IDEMPOTENCY_KEY_COLLISION')
      const step = snapshot?.instance?.steps?.find((candidate) => candidate.id === opening.stepId)
      if (!Number.isSafeInteger(snapshot?.revision) || !step)
        throw new WorkflowHumanInteractionError('INTERACTION_RECOVERY_SNAPSHOT_INVALID')
      if (step.status === 'ready') {
        if (snapshot.revision !== opening.expectedRevision)
          throw new WorkflowHumanInteractionError('INTERACTION_RECOVERY_REVISION_DIVERGED')
        if (opening.status === 'opening') {
          await append(this.path(namespaceId, storageId), {
            event: 'interaction_open_aborted',
            interactionId: opening.interactionId,
            errorCode: 'RECOVERED_OPENING_WITH_READY_STEP',
            recoveredAt: new Date().toISOString(),
          })
        }
        return { status: 'reopen', abandonedInteraction: opening }
      }
      if (opening.status !== 'opening')
        throw new WorkflowHumanInteractionError('INTERACTION_RECOVERY_STATE_DIVERGED')
      if (step.status !== 'waiting_human')
        throw new WorkflowHumanInteractionError('INTERACTION_RECOVERY_STATE_DIVERGED')
      if (snapshot.revision <= opening.expectedRevision)
        throw new WorkflowHumanInteractionError('INTERACTION_RECOVERY_REVISION_DIVERGED')
      const provesTransition = workflowFacts.some(
        (fact) =>
          fact?.kind === 'transition_accepted' &&
          fact?.revision === snapshot.revision &&
          fact?.transitionDelta?.steps?.some(
            (change) =>
              change.stepId === opening.stepId &&
              change.status?.from === 'ready' &&
              change.status?.to === 'waiting_human'
          )
      )
      if (!provesTransition)
        throw new WorkflowHumanInteractionError('INTERACTION_RECOVERY_TRANSITION_UNPROVEN')
      await append(this.path(namespaceId, storageId), {
        event: 'interaction_opened',
        interaction: { ...opening, revision: snapshot.revision },
        revision: snapshot.revision,
        recovery: 'authoritative-workflow-transition',
      })
      return {
        status: 'open',
        interaction: { ...opening, status: 'open', revision: snapshot.revision },
      }
    })
  }
  async open(namespaceId, storageId, input, transition) {
    const normalized = validateHumanInteractionOpenInput(input)
    if (!normalized) throw new WorkflowHumanInteractionError('INVALID_INTERACTION')
    const hash = humanInteractionSemanticHash(normalized)
    return this._locked(`${namespaceId}\0${storageId}`, async () => {
      const items = await this.list(namespaceId, storageId)
      const prior = items.find((item) => item.idempotencyKey === normalized.idempotencyKey)
      if (prior) {
        if (prior.semanticHash !== hash) throw new WorkflowHumanInteractionError('IDEMPOTENCY_KEY_COLLISION')
        if (prior.status === 'open')
          return {
            interaction: prior,
            transition: { ok: true, changed: false, idempotent: true, snapshot: { revision: prior.revision } },
          }
        throw new WorkflowHumanInteractionError('INTERACTION_OPEN_INDETERMINATE')
      }
      if (
        items.some(
          (item) =>
            item.workflowId === normalized.workflowId &&
            item.stepId === normalized.stepId &&
            ['opening', 'open'].includes(item.status)
        )
      )
        throw new WorkflowHumanInteractionError('INTERACTION_ALREADY_OPEN')
      const interaction = {
        interactionId: normalized.interactionId ?? randomUUID(),
        workflowId: normalized.workflowId,
        stepId: normalized.stepId,
        expectedRevision: normalized.expectedRevision,
        kind: normalized.kind,
        prompt: normalized.prompt,
        actions: normalized.actions,
        ...(normalized.interactionType ? { interactionType: normalized.interactionType } : {}),
        ...(normalized.reasonCode ? { reasonCode: normalized.reasonCode } : {}),
        idempotencyKey: normalized.idempotencyKey,
        semanticHash: hash,
        openedAt: new Date().toISOString(),
      }
      await append(this.path(namespaceId, storageId), { event: 'interaction_opening', interaction })
      await this.fault('after-opening', { interaction })
      let result
      try {
        result = await transition(interaction)
      } catch (error) {
        await append(this.path(namespaceId, storageId), {
          event: 'interaction_open_aborted',
          interactionId: interaction.interactionId,
          errorCode: error?.code ?? 'TRANSITION_FAILED',
        })
        throw error
      }
      if (!result?.ok) {
        await append(this.path(namespaceId, storageId), {
          event: 'interaction_open_aborted',
          interactionId: interaction.interactionId,
          errorCode: result?.error?.code ?? 'TRANSITION_FAILED',
        })
        const error = new WorkflowHumanInteractionError(result?.error?.code ?? 'INVALID_INTERACTION_TRANSACTION')
        error.decision = result?.decision
        throw error
      }
      await this.fault('after-transition', { interaction, result })
      await append(this.path(namespaceId, storageId), {
        event: 'interaction_opened',
        interaction: { ...interaction, revision: result.snapshot.revision },
        revision: result.snapshot.revision,
        transitionRequestId: result.requestId,
      })
      return { interaction: { ...interaction, status: 'open', revision: result.snapshot.revision }, transition: result }
    })
  }
  async transact(namespaceId, storageId, interactionId, action) {
    return this._locked(`${namespaceId}\0${storageId}`, async () => {
      const interaction = (await this.list(namespaceId, storageId)).find((item) => item.interactionId === interactionId)
      if (!interaction) throw new WorkflowHumanInteractionError('INTERACTION_NOT_FOUND')
      if (interaction.status !== 'open') throw new WorkflowHumanInteractionError('INTERACTION_CLOSED')
      const result = await action(interaction)
      if (!result?.transition?.ok) throw new WorkflowHumanInteractionError('INVALID_INTERACTION_TRANSACTION')
      await append(this.path(namespaceId, storageId), {
        event: 'interaction_transitioned',
        interactionId,
        expectedRevision: interaction.expectedRevision,
        reply: result.reply,
        actorId: result.actorId,
        evidenceId: result.evidenceId,
        transitionRequestId: result.transition.requestId,
        revision: result.transition.snapshot.revision,
        repliedAt: new Date().toISOString(),
      })
      return {
        interaction: {
          ...interaction,
          status: 'replied',
          reply: result.reply,
          actorId: result.actorId,
          evidenceId: result.evidenceId,
          revision: result.transition.snapshot.revision,
        },
        transition: result.transition,
      }
    })
  }
}
