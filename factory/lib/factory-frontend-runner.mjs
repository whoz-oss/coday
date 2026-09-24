import { readFile } from 'node:fs/promises'
import { join } from 'node:path'
import { executeAgentStepAttempt } from './factory-agent-step-executor.mjs'
import { validateWorkflowTransitionRequest } from './workflow-transition-policy.mjs'

const EXPECTED_ARTIFACTS = {
  'ticket-analysis': 'ticket-analysis',
  'product-specification': 'product-specification',
  'ux-design': 'ux-contract',
  'codebase-research': 'codebase-research',
  'technical-design': 'technical-design',
}
export async function buildAgentBrief({ briefRoot, stepId, workflowId, ticket, artifactEvidence, reviewPackage }) {
  const template = await readFile(join(briefRoot, `${stepId}.md`), 'utf8')
  return `${template}\n\n## Mandatory structured completion\nBefore ending, call FACTORY__submit_step_result exactly once. Its arguments contain only the business result: status, bounded summary, structured artifacts/claims and, for technical review, findings. The Factory injects and verifies attempt identity and capability. Your normal assistant message is narrative UI only and is never authoritative. Artifact Markdown may be submitted raw in the structured artifact content; never choose paths or hashes.\n\n## Authoritative run input\n${JSON.stringify({ workflowId, ticket, artifacts: artifactEvidence.map((e) => ({ kind: e.facts?.artifactKind ?? e.stepId, path: e.artifactRef, hash: e.artifactHash })), ...(reviewPackage ? { reviewPackage } : {}) }, null, 2)}\n`
}
function humanPrompt(step) {
  return `Approve or reject ${step.name}. Approval advances the immutable workflow; rejection blocks it.`
}
export async function runWorkflowUntilStop(input) {
  const { namespaceId, workflowId, definition, projectionStore, evidenceStore, attemptStore, storageId } = input
  for (;;) {
    const snapshot = await projectionStore.read(namespaceId, workflowId)
    if (!snapshot) return { status: 'FAILED', code: 'WORKFLOW_NOT_FOUND' }
    const review = snapshot.instance.steps.find((s) => s.id === 'technical-review')
    if (review?.status === 'completed') return { status: 'REVIEW_REACHED', snapshot }
    if (['blocked', 'failed', 'completed', 'cancelled'].includes(snapshot.instance.status))
      return { status: snapshot.instance.status.toUpperCase(), snapshot }
    const waitingHuman = definition.steps.find(
      (step) =>
        step.responsibility.kind === 'human' &&
        snapshot.instance.steps.find((s) => s.id === step.id)?.status === 'waiting_human'
    )
    if (waitingHuman) {
      if (!input.humanInteractionStore) return { status: 'FAILED', code: 'HUMAN_INTERACTION_STORE_UNAVAILABLE' }
      const interactionInput = {
        workflowId,
        stepId: waitingHuman.id,
        expectedRevision: snapshot.revision - 1,
        kind: 'approval',
        prompt: humanPrompt(waitingHuman),
        actions: [
          { id: 'approve', label: 'Approve', requestedStatus: 'completed' },
          { id: 'reject', label: 'Reject', requestedStatus: 'blocked' },
        ],
        idempotencyKey: `runner:${workflowId}:${waitingHuman.id}:${snapshot.revision - 1}`,
      }
      try {
        const recovered = await input.humanInteractionStore.reconcileOpen(
          namespaceId,
          storageId,
          interactionInput,
          snapshot,
          { workflowFacts: await projectionStore.facts(namespaceId, workflowId) }
        )
        if (recovered.status !== 'open') return { status: 'FAILED', code: 'INTERACTION_RECOVERY_STATE_DIVERGED' }
        return { status: 'WAITING_HUMAN', stepId: waitingHuman.id, interaction: recovered.interaction, snapshot }
      } catch (error) {
        return { status: 'FAILED', code: error?.code ?? 'HUMAN_INTERACTION_RECOVERY_FAILED' }
      }
    }
    const declared = definition.steps.find(
      (step) => snapshot.instance.steps.find((s) => s.id === step.id)?.status === 'ready'
    )
    if (!declared) {
      const running = snapshot.instance.steps.find((s) => s.status === 'running')
      if (running) {
        const attempts = (await attemptStore.list(namespaceId, storageId)).filter((a) => a.stepId === running.id)
        const latest = attempts.sort((a, b) => b.attemptNumber - a.attemptNumber).at(0)
        if (latest && ['failed', 'indeterminate', 'interrupted', 'succeeded'].includes(latest.status))
          return {
            status: 'BLOCKED',
            code: 'RUNNING_STEP_WITH_TERMINAL_ATTEMPT',
            stepId: running.id,
            attempt: latest,
            snapshot,
          }
        return {
          status: 'RUNNING',
          code: 'ACTIVE_ATTEMPT_IN_PROGRESS',
          stepId: running.id,
          attempt: latest ?? null,
          snapshot,
        }
      }
      return { status: 'BLOCKED', code: 'NO_READY_STEP', snapshot }
    }
    if (declared.responsibility.kind === 'human') {
      if (!input.humanInteractionStore) return { status: 'FAILED', code: 'HUMAN_INTERACTION_STORE_UNAVAILABLE' }
      try {
        const interactionInput = {
          workflowId,
          stepId: declared.id,
          expectedRevision: snapshot.revision,
          kind: 'approval',
          prompt: humanPrompt(declared),
          actions: [
            { id: 'approve', label: 'Approve', requestedStatus: 'completed' },
            { id: 'reject', label: 'Reject', requestedStatus: 'blocked' },
          ],
          idempotencyKey: `runner:${workflowId}:${declared.id}:${snapshot.revision}`,
        }
        try {
          const recovery = await input.humanInteractionStore.reconcileOpen(
            namespaceId,
            storageId,
            interactionInput,
            snapshot
          )
          if (recovery.status === 'open')
            return { status: 'WAITING_HUMAN', stepId: declared.id, interaction: recovery.interaction, snapshot }
          interactionInput.interactionId =
            `${declared.id}-${snapshot.revision}-recovery-${recovery.abandonedInteraction.interactionId}`.slice(0, 128)
          interactionInput.idempotencyKey =
            `runner-recovery:${workflowId}:${declared.id}:${snapshot.revision}:${recovery.abandonedInteraction.interactionId}`.slice(
              0,
              128
            )
        } catch (error) {
          if (error?.code !== 'INTERACTION_RECOVERY_NOT_FOUND') throw error
        }
        const opened = await input.humanInteractionStore.open(namespaceId, storageId, interactionInput, async () => {
          const request = validateWorkflowTransitionRequest(
            {
              workflowId,
              stepId: declared.id,
              expectedRevision: snapshot.revision,
              requestedStatus: 'waiting_human',
              evidenceIds: [],
              idempotencyKey: `runner-open:${workflowId}:${declared.id}:${snapshot.revision}`,
            },
            workflowId
          )
          if (!request.ok)
            throw Object.assign(new Error('INVALID_HUMAN_TRANSITION'), { code: 'INVALID_HUMAN_TRANSITION' })
          return projectionStore.openHumanCheckpoint(namespaceId, request.value, definition, input.humanGateExecution)
        })
        return {
          status: 'WAITING_HUMAN',
          stepId: declared.id,
          interaction: opened.interaction,
          snapshot: opened.transition.snapshot,
        }
      } catch (error) {
        return { status: 'FAILED', code: error?.code ?? 'HUMAN_INTERACTION_FAILED' }
      }
    }
    if (declared.responsibility.kind === 'code') {
      if (declared.responsibility.name !== 'forge-frontend-verification')
        return { status: 'FAILED', code: 'UNCONFIGURED_ORACLE' }
      const oracle = await input.executeFrontendOracle({
        namespaceId,
        workflowId,
        stepId: declared.id,
        snapshot,
        definition,
      })
      if (!oracle?.ok) return { status: 'FAILED', code: oracle?.code ?? 'ORACLE_FAILED' }
      continue
    }
    const priorEvidence = await evidenceStore.list(namespaceId, storageId)
    let reviewPackage = null
    if (declared.id === 'technical-review') {
      try {
        reviewPackage = await input.buildReviewPackage({ snapshot, evidence: priorEvidence })
      } catch (error) {
        return { status: 'BLOCKED', code: error?.message ?? 'REVIEW_PACKAGE_FAILED' }
      }
    }
    const brief = await buildAgentBrief({
      briefRoot: input.briefRoot,
      stepId: declared.id,
      workflowId,
      ticket: input.ticket,
      artifactEvidence: priorEvidence.filter((e) => e.kind === 'artifact'),
      reviewPackage,
    })
    const result = await executeAgentStepAttempt({
      ...input,
      stepId: declared.id,
      brief,
      expectedRevision: snapshot.revision,
      expectedArtifactKind: EXPECTED_ARTIFACTS[declared.id],
    })
    if (!result.ok)
      return {
        status: 'BLOCKED',
        code: result.code,
        reconciliationCode: result.reconciliationCode,
        details: result.details,
        attempt: result.attempt,
      }
  }
}
