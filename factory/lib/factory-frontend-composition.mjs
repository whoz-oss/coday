import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { join } from 'node:path'
import { AgentStepAttemptStore } from './agent-step-attempt-store.mjs'
import { buildFrontendReviewPackage } from './factory-review-package.mjs'
import { runWorkflowUntilStop } from './factory-frontend-runner.mjs'
import { executeOracle, oracleArtifact, oracleRootIdentity, validateOracleRoot } from './oracle-executor.mjs'
import { countTaskOutcomes } from './oracle.mjs'
import { hashOracleDefinition } from './oracle-definition.mjs'
import { validateWorkflowEvidenceInput } from './workflow-evidence.mjs'
import { validateWorkflowTransitionRequest } from './workflow-transition-policy.mjs'
import { workflowProjectionStorageId } from './workflow-projection-store.mjs'
const execFileAsync = promisify(execFile)
async function gitFiles(repoRoot, args) {
  const { stdout } = await execFileAsync('git', args, { cwd: repoRoot, encoding: 'utf8' })
  return stdout.split('\n').filter(Boolean)
}
export function createFactoryFrontendRunner({
  dataRoot,
  workflowRoot,
  projectionStore,
  evidenceStore,
  humanInteractionStore,
  definitionRegistry,
  oracleRegistry,
  workUnitEnvironmentStore,
  resultStore,
  notifier,
  controllerExecution = { kind: 'agentos', runtimeId: 'factory-dashboard', agentId: 'factory-runner' },
  humanGateExecution = { kind: 'factory-human-gate', runtimeId: 'factory-dashboard', agentId: 'factory-runner' },
}) {
  if (!resultStore) throw new Error('FACTORY_FRONTEND_RESULT_STORE_REQUIRED')
  const attemptStore = new AgentStepAttemptStore(dataRoot)
  const openRetry = async ({ namespaceId, workflowId, stepId, expectedRevision, reasonCode }) => {
    const snapshot = await projectionStore.read(namespaceId, workflowId)
    if (!snapshot?.instance) return { status: 'FAILED', code: 'WORKFLOW_NOT_FOUND' }
    const definition = await definitionRegistry.get(snapshot.instance.workflowType, snapshot.instance.definitionVersion)
    if (snapshot.governanceMode !== 'governed' || snapshot.instance.governanceMode !== 'governed')
      return { status: 'FAILED', code: 'WORKFLOW_NOT_GOVERNED' }
    if (
      !definition ||
      definition.definitionHash !== snapshot.instance.definitionHash ||
      snapshot.definitionHash !== definition.definitionHash
    )
      return { status: 'FAILED', code: 'WORKFLOW_DEFINITION_MISMATCH' }
    if (snapshot.revision !== expectedRevision || snapshot.instance.revision !== expectedRevision)
      return { status: 'FAILED', code: 'REVISION_CONFLICT' }
    const declared = definition.steps.find((item) => item.id === stepId),
      step = snapshot.instance.steps.find((item) => item.id === stepId)
    if (!declared || !step) return { status: 'FAILED', code: 'STEP_NOT_FOUND' }
    if (declared.responsibility?.kind !== 'agent') return { status: 'FAILED', code: 'ACTOR_NOT_AUTHORIZED' }
    if (step.status !== 'blocked') return { status: 'FAILED', code: 'ILLEGAL_TRANSITION' }
    if (!/^[A-Z][A-Z0-9_]{1,127}$/.test(reasonCode)) return { status: 'FAILED', code: 'INVALID_RETRY_REQUEST' }
    const storageId = workflowProjectionStorageId(namespaceId, workflowId),
      evidence = await evidenceStore.list(namespaceId, storageId)
    const negative = evidence
      .filter(
        (item) =>
          item.stepId === stepId && item.kind === 'agent-result' && ['fail', 'indeterminate'].includes(item.outcome)
      )
      .at(-1)
    const observedReason = negative?.facts?.resultCode
    if (!negative) return { status: 'FAILED', code: 'RETRY_EVIDENCE_NOT_FOUND' }
    if (observedReason !== reasonCode) return { status: 'FAILED', code: 'RETRY_REASON_MISMATCH' }
    const idempotencyKey = `retry:${workflowId}:${stepId}:${expectedRevision}:${reasonCode}`
    try {
      const opened = await humanInteractionStore.open(
        namespaceId,
        storageId,
        {
          workflowId,
          stepId,
          expectedRevision,
          kind: 'approval',
          interactionType: 'retry',
          reasonCode,
          prompt: `Retry ${stepId} after ${reasonCode}?`,
          idempotencyKey,
          actions: [
            { id: 'approve', label: 'Approve', requestedStatus: 'ready' },
            { id: 'reject', label: 'Reject', requestedStatus: 'blocked' },
          ],
        },
        async () => ({
          ok: true,
          changed: false,
          idempotent: false,
          requestId: `retry-open:${idempotencyKey}`,
          snapshot,
        })
      )
      return { status: 'WAITING_HUMAN', interaction: opened.interaction, snapshot }
    } catch (cause) {
      return { status: 'FAILED', code: cause?.code ?? 'RETRY_OPEN_FAILED' }
    }
  }
  const notifySnapshot = (namespaceId, workflowId, result) => {
    if (result?.ok && result.changed)
      notifier?.publish(namespaceId, { workflowId, namespaceId, revision: result.snapshot.revision })
  }
  const notifyingProjectionStore = {
    ...projectionStore,
    read: projectionStore.read.bind(projectionStore),
    transition: async (...args) => {
      const result = await projectionStore.transition(...args)
      notifySnapshot(args[0], args[1]?.workflowId, result)
      return result
    },
    openHumanCheckpoint: projectionStore.openHumanCheckpoint?.bind(projectionStore),
    resolveHumanCheckpoint: projectionStore.resolveHumanCheckpoint?.bind(projectionStore),
  }
  const runner = async ({ namespaceId, workflowId, ticket }) => {
    if (!oracleRegistry) return { status: 'FAILED', code: 'ORACLE_REGISTRY_UNAVAILABLE' }
    if (!workUnitEnvironmentStore) return { status: 'FAILED', code: 'WORKSPACE_STORE_UNAVAILABLE' }
    const snapshot = await projectionStore.read(namespaceId, workflowId)
    if (!snapshot?.instance) return { status: 'FAILED', code: 'WORKFLOW_NOT_FOUND' }
    const definition = await definitionRegistry.get(snapshot.instance.workflowType, snapshot.instance.definitionVersion)
    if (
      !definition ||
      definition.definitionHash !== snapshot.instance.definitionHash ||
      definition.workflowType !== 'bmad-story-frontend'
    )
      return { status: 'FAILED', code: 'FRONTEND_DEFINITION_MISMATCH' }
    const environmentId = snapshot.instance.environmentRef?.environmentId,
      environment = environmentId ? await workUnitEnvironmentStore.read(namespaceId, environmentId) : null
    if (!environment || environment.environment.lifecycleState !== 'active')
      return { status: 'FAILED', code: 'ACTIVE_WORKSPACE_REQUIRED' }
    const allowedPaths = definition.trustedExecution?.allowedPaths
    if (
      !Array.isArray(allowedPaths) ||
      allowedPaths.length === 0 ||
      allowedPaths.some(
        (path) =>
          typeof path !== 'string' || path.length === 0 || path.startsWith('/') || path.split('/').includes('..')
      )
    )
      return { status: 'FAILED', code: 'FRONTEND_SCOPE_NOT_CONFIGURED' }
    const repoRoot = await validateOracleRoot(environment.environment.worktreePath),
      storageId = workflowProjectionStorageId(namespaceId, workflowId)
    const diffFiles = async () => [
      ...new Set([
        ...(await gitFiles(repoRoot, ['diff', '--name-only', '--relative', 'HEAD'])),
        ...(await gitFiles(repoRoot, ['ls-files', '--others', '--exclude-standard'])),
      ]),
    ]
    const executeFrontendOracle = async ({ stepId, snapshot: current }) => {
      const oracle = oracleRegistry?.get('forge-frontend-verification')
      if (
        !oracle ||
        !oracle.applicable.workflowTypes.includes(definition.workflowType) ||
        !oracle.applicable.stepIds.includes(stepId)
      )
        return { ok: false, code: 'ORACLE_NOT_APPLICABLE' }
      const modified = await diffFiles(),
        previousModifiedFiles = process.env.FACTORY_FRONT_MODIFIED_FILES
      process.env.FACTORY_FRONT_MODIFIED_FILES = JSON.stringify(modified)
      let result
      try {
        result = await executeOracle(oracle, { repoRoot, countTaskOutcomes })
      } finally {
        if (previousModifiedFiles === undefined) delete process.env.FACTORY_FRONT_MODIFIED_FILES
        else process.env.FACTORY_FRONT_MODIFIED_FILES = previousModifiedFiles
      }
      const artifact = oracleArtifact(result),
        facts = {
          oracleId: oracle.id,
          oracleVersion: oracle.version,
          oracleHash: hashOracleDefinition(oracle),
          cwdId: oracleRootIdentity(repoRoot),
          exitCode: result.exitCode ?? -1,
          signal: result.signal ?? 'none',
          timedOut: result.timedOut,
          durationMs: result.durationMs,
          classification: result.classification,
          executed: result.counts.executed,
          fromCache: result.counts.fromCache,
          upToDate: result.counts.upToDate,
          skipped: result.counts.skipped,
          outputHash: artifact.hash,
        }
      const validated = validateWorkflowEvidenceInput(
        {
          workflowId,
          stepId,
          kind: 'oracle-result',
          outcome: result.outcome,
          facts,
          idempotencyKey: `runner-oracle:${current.revision}:${facts.oracleHash}:${facts.outputHash}`,
        },
        workflowId
      )
      if (!validated.ok) return { ok: false, code: 'INVALID_ORACLE_EVIDENCE' }
      const stored = await evidenceStore.record(namespaceId, storageId, validated.value, {
        kind: 'factory-oracle',
        runtimeId: 'factory-dashboard',
        agentId: oracle.id,
      })
      if (result.outcome !== 'pass' || facts.executed !== true || facts.fromCache === true)
        return { ok: false, code: 'ORACLE_FAILED' }
      const request = validateWorkflowTransitionRequest(
        {
          workflowId,
          stepId,
          expectedRevision: current.revision,
          requestedStatus: 'completed',
          evidenceIds: [stored.evidence.evidenceId],
          idempotencyKey: `runner-oracle-complete:${current.revision}`,
        },
        workflowId
      )
      if (!request.ok) return { ok: false, code: 'INVALID_ORACLE_TRANSITION' }
      return projectionStore.transition(namespaceId, request.value, definition, [stored.evidence], controllerExecution)
    }
    const buildReviewPackage = async ({ evidence }) => {
      const implementation = evidence
          .filter((item) => item.stepId === 'frontend-implementation' && item.kind === 'agent-result')
          .at(-1),
        modifiedFiles = await diffFiles(),
        claims = { modifiedFiles, claimsHash: implementation?.facts?.claimsHash ?? null }
      const { stdout: diff } = await execFileAsync('git', ['diff', '--no-ext-diff', '--binary', 'HEAD'], {
        cwd: repoRoot,
        encoding: 'utf8',
        maxBuffer: 20 * 1024 * 1024,
      })
      return buildFrontendReviewPackage({
        ticket,
        evidence,
        diff: [diff],
        claims,
        oracleResults: evidence.filter((item) => item.kind === 'oracle-result'),
        repoRoot,
      })
    }
    return runWorkflowUntilStop({
      namespaceId,
      workflowId,
      ticket,
      definition,
      projectionStore: notifyingProjectionStore,
      evidenceStore,
      humanInteractionStore,
      attemptStore,
      resultStore,
      storageId,
      repoRoot,
      briefRoot: join(workflowRoot, 'bmad-story-frontend', 'briefs'),
      controllerExecution,
      humanGateExecution,
      diffFiles,
      allowedPaths,
      executeFrontendOracle,
      buildReviewPackage,
    })
  }
  runner.openRetry = openRetry
  return runner
}
