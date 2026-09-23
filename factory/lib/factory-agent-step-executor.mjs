import { createHash, randomUUID } from 'node:crypto'
import { mkdir, open, readFile, rename, rm, stat } from 'node:fs/promises'
import { isAbsolute, join, relative, resolve } from 'node:path'
import {
  bindFactoryStepResult,
  createCase,
  preflightAgent,
  preflightReadOnlyWorkspace,
  preflightWritableWorkspace,
  runAgentTurn,
} from './agentos.mjs'
import { validateWorkflowEvidenceInput } from './workflow-evidence.mjs'
import { validateWorkflowTransitionRequest } from './workflow-transition-policy.mjs'

const hash = (value) => `sha256:${createHash('sha256').update(value).digest('hex')}`
const canonicalize = (value) =>
  Array.isArray(value)
    ? value.map(canonicalize)
    : value && typeof value === 'object'
      ? Object.fromEntries(
          Object.keys(value)
            .sort()
            .map((key) => [key, canonicalize(value[key])])
        )
      : value
const canonical = (value) => JSON.stringify(canonicalize(value))
const terminal = (status) => ['succeeded', 'failed', 'indeterminate', 'interrupted'].includes(status)
const diagnostic = (value, fallback) =>
  String(value ?? fallback)
    .replace(/[\r\n\t]+/g, ' ')
    .replace(/\s+/g, ' ')
    .slice(0, 1000)
const MAX_INLINE_ARTIFACT_BYTES = 256 * 1024
const safeSegment = (value) =>
  typeof value === 'string' && /^[A-Za-z0-9._-]+$/.test(value) && value !== '.' && value !== '..'
const inside = (root, target) => {
  const rel = relative(root, target)
  return rel === '' || (!rel.startsWith('..') && !isAbsolute(rel))
}
export const artifactEvidenceIdempotencyKey = (attemptId, artifactPath) =>
  `${attemptId}:artifact:${createHash('sha256').update(String(artifactPath).split('\\').join('/')).digest('hex')}`
const STRUCTURED_RESULT_FINALIZATION_BRIEF =
  'Do no new analysis or work. Perform no reads, writes, delegation, queryUser, or oracle calls. Using only the work already completed in this case, call FACTORY__submit_step_result exactly once. Your normal assistant message is non-authoritative.'

function extractSingleJsonObject(message) {
  const text = String(message ?? '').trim()
  const fence = [...text.matchAll(/```json\s*([\s\S]*?)\s*```/gi)]
  if (fence.length > 1) return null
  if (fence.length === 1) {
    const outside = (text.slice(0, fence[0].index) + text.slice(fence[0].index + fence[0][0].length)).trim()
    if (/[{}]/.test(outside)) return null
    return fence[0][1].trim()
  }
  const candidates = []
  for (let start = 0; start < text.length; start++) {
    if (text[start] !== '{') continue
    let depth = 0,
      inString = false,
      escape = false
    for (let end = start; end < text.length; end++) {
      const char = text[end]
      if (inString) {
        if (escape) escape = false
        else if (char === '\\') escape = true
        else if (char === '"') inString = false
        continue
      }
      if (char === '"') {
        inString = true
        continue
      }
      if (char === '{') depth++
      if (char === '}' && --depth === 0) {
        const candidate = text.slice(start, end + 1)
        try {
          const value = JSON.parse(candidate)
          if (value && typeof value === 'object' && !Array.isArray(value)) candidates.push(candidate)
        } catch {}
        start = end
        break
      }
    }
  }
  return candidates.length === 1 ? candidates[0] : null
}
export function parseAgentStepResult(message) {
  const text = extractSingleJsonObject(message)
  if (!text) return { ok: false, code: 'RESULT_NOT_JSON' }
  let value
  try {
    value = JSON.parse(text)
  } catch {
    return { ok: false, code: 'RESULT_NOT_JSON' }
  }
  if (
    !value ||
    !['PASS', 'FAIL'].includes(value.status) ||
    typeof value.summary !== 'string' ||
    !value.claims ||
    !Array.isArray(value.claims.modifiedFiles)
  )
    return { ok: false, code: 'RESULT_SCHEMA_INVALID' }
  if (value.artifacts !== undefined && !Array.isArray(value.artifacts))
    return { ok: false, code: 'RESULT_SCHEMA_INVALID' }
  return { ok: true, value }
}

export async function materializeInlineArtifact({
  result,
  repoRoot,
  workflowId,
  stepId,
  attemptId,
  expectedKind,
  maxBytes = MAX_INLINE_ARTIFACT_BYTES,
}) {
  const artifacts = result.artifacts ?? []
  if (!expectedKind)
    return artifacts.length ? { ok: false, code: 'INLINE_ARTIFACT_FORBIDDEN' } : { ok: true, artifacts: [] }
  if (artifacts.length !== 1)
    return { ok: false, code: artifacts.length ? 'ARTIFACT_AMBIGUOUS' : 'EXPECTED_ARTIFACT_MISSING' }
  const artifact = artifacts[0]
  if (!artifact || typeof artifact !== 'object' || Array.isArray(artifact))
    return { ok: false, code: 'INLINE_ARTIFACT_SCHEMA_INVALID' }
  if ('path' in artifact) return { ok: false, code: 'ARTIFACT_PATH_FORBIDDEN' }
  if (Object.keys(artifact).some((key) => !['kind', 'encoding', 'content'].includes(key)))
    return { ok: false, code: 'INLINE_ARTIFACT_SCHEMA_INVALID' }
  if (artifact.kind !== expectedKind) return { ok: false, code: 'ARTIFACT_KIND_MISMATCH' }
  if (artifact.encoding !== 'markdown') return { ok: false, code: 'ARTIFACT_ENCODING_INVALID' }
  if (typeof artifact.content !== 'string' || artifact.content.length === 0)
    return { ok: false, code: 'ARTIFACT_CONTENT_EMPTY' }
  const content = Buffer.from(artifact.content, 'utf8')
  if (content.length === 0) return { ok: false, code: 'ARTIFACT_CONTENT_EMPTY' }
  if (content.byteLength > maxBytes) return { ok: false, code: 'ARTIFACT_CONTENT_TOO_LARGE' }
  if (!safeSegment(workflowId) || !safeSegment(stepId) || !safeSegment(attemptId))
    return { ok: false, code: 'ARTIFACT_PATH_INVALID' }
  const root = resolve(repoRoot),
    zone = resolve(root, 'forge', 'factory-artifacts'),
    workflowDirectory = resolve(zone, workflowId),
    directory = resolve(workflowDirectory, stepId),
    finalPath = resolve(directory, `${attemptId}.md`)
  if (
    !inside(root, zone) ||
    !inside(zone, workflowDirectory) ||
    !inside(workflowDirectory, directory) ||
    !inside(directory, finalPath)
  )
    return { ok: false, code: 'ARTIFACT_OUT_OF_SCOPE' }
  await mkdir(directory, { recursive: true })
  const artifactPath = relative(root, finalPath).split('\\').join('/')
  const verified = (persisted) =>
    persisted.equals(content)
      ? { ok: true, artifacts: [{ kind: expectedKind, path: artifactPath, hash: hash(persisted) }] }
      : { ok: false, code: 'ARTIFACT_SEMANTIC_COLLISION' }
  try {
    return verified(await readFile(finalPath))
  } catch (error) {
    if (error?.code !== 'ENOENT') return { ok: false, code: 'ARTIFACT_MATERIALIZATION_FAILED' }
  }
  const temporary = join(directory, `.${stepId}.${randomUUID()}.tmp`)
  let handle
  try {
    handle = await open(temporary, 'wx', 0o600)
    await handle.writeFile(content)
    await handle.sync()
    await handle.close()
    handle = null
    try {
      await rename(temporary, finalPath)
    } catch (error) {
      if (error?.code !== 'EEXIST') throw error
      return verified(await readFile(finalPath))
    }
    const persisted = await readFile(finalPath)
    if (!persisted.equals(content)) return { ok: false, code: 'ARTIFACT_WRITE_MISMATCH' }
    return verified(persisted)
  } catch {
    return { ok: false, code: 'ARTIFACT_MATERIALIZATION_FAILED' }
  } finally {
    if (handle) await handle.close().catch(() => {})
    await rm(temporary, { force: true }).catch(() => {})
  }
}

async function verifyArtifacts(result, repoRoot, expectedKind) {
  const artifacts = result.artifacts ?? []
  if (expectedKind && !artifacts.some((a) => a.kind === expectedKind))
    return { ok: false, code: 'EXPECTED_ARTIFACT_MISSING' }
  const verified = []
  for (const artifact of artifacts) {
    if (isAbsolute(artifact.path)) return { ok: false, code: 'ARTIFACT_OUT_OF_SCOPE' }
    const absolute = resolve(repoRoot, artifact.path)
    if (relative(repoRoot, absolute).startsWith('..')) return { ok: false, code: 'ARTIFACT_OUT_OF_SCOPE' }
    try {
      if (!(await stat(absolute)).isFile()) return { ok: false, code: 'ARTIFACT_NOT_FILE' }
      const content = await readFile(absolute)
      verified.push({ ...artifact, hash: hash(content) })
    } catch {
      return { ok: false, code: 'ARTIFACT_MISSING' }
    }
  }
  return { ok: true, artifacts: verified }
}

export async function executeAgentStepAttempt(input) {
  const {
    namespaceId,
    workflowId,
    definition,
    projectionStore,
    evidenceStore,
    attemptStore,
    resultStore,
    storageId,
    repoRoot,
    runtimeId = 'factory-runner',
    brief,
    expectedArtifactKind,
    diffFiles = async () => [],
    agentOps = {},
  } = input
  if (!resultStore) return { ok: false, code: 'STRUCTURED_RESULT_STORE_UNAVAILABLE' }
  const ops = {
    createCase,
    bindFactoryStepResult,
    preflightAgent,
    preflightReadOnlyWorkspace,
    preflightWritableWorkspace,
    runAgentTurn,
    ...agentOps,
  }
  const snapshot = await projectionStore.read(namespaceId, workflowId)
  const declared = definition.steps.find((s) => s.id === input.stepId),
    current = snapshot?.instance?.steps.find((s) => s.id === input.stepId)
  if (!snapshot || !declared || declared.responsibility.kind !== 'agent' || current?.status !== 'ready')
    return { ok: false, code: 'STEP_NOT_READY' }
  if (input.expectedRevision !== undefined && snapshot.revision !== input.expectedRevision)
    return { ok: false, code: 'REVISION_CONFLICT' }
  const agentName = declared.responsibility.name,
    preflight = await ops.preflightAgent(namespaceId, agentName)
  if (!preflight.ok || preflight.agent?.name !== agentName || (preflight.agent.subAgents?.length ?? 0) > 0)
    return {
      ok: false,
      code: 'AGENT_PREFLIGHT_FAILED',
      details: diagnostic(
        preflight.reason,
        preflight.agent?.name !== agentName
          ? `Agent identity mismatch: expected ${agentName}, received ${preflight.agent?.name ?? '(missing)'}.`
          : `Agent ${agentName} has forbidden subAgents.`
      ),
    }
  const writable = input.stepId === 'frontend-implementation'
  const workspace = await (writable ? ops.preflightWritableWorkspace : ops.preflightReadOnlyWorkspace)(
    namespaceId,
    preflight.agent,
    repoRoot
  )
  if (!workspace.ok)
    return {
      ok: false,
      code: 'AGENT_PREFLIGHT_FAILED',
      details: diagnostic(workspace.reason, 'Workspace capability preflight failed.'),
    }
  const attemptNumber =
    (await attemptStore.list(namespaceId, storageId))
      .filter((a) => a.stepId === input.stepId)
      .reduce((n, a) => Math.max(n, a.attemptNumber), 0) + 1
  const attemptId = randomUUID(),
    startedAt = new Date().toISOString(),
    briefHash = hash(brief)
  let attempt = {
    attemptId,
    workflowId,
    workflowRevisionAtStart: snapshot.revision,
    stepId: input.stepId,
    attemptNumber,
    namespaceId,
    runtimeId,
    caseId: null,
    agentName,
    briefHash,
    status: 'starting',
    startedAt,
    finishedAt: null,
    evidenceId: null,
    failureCode: null,
  }
  await attemptStore.append(namespaceId, storageId, attempt)
  let caseRecord
  try {
    caseRecord = await ops.createCase(namespaceId, `Factory ${workflowId} ${input.stepId} attempt ${attemptNumber}`)
  } catch {
    await attemptStore.append(namespaceId, storageId, {
      ...attempt,
      status: 'failed',
      finishedAt: new Date().toISOString(),
      failureCode: 'CASE_CREATION_FAILED',
    })
    return { ok: false, code: 'CASE_CREATION_FAILED' }
  }
  attempt = { ...attempt, caseId: caseRecord.id, status: 'running' }
  await attemptStore.append(namespaceId, storageId, attempt)
  const capability = await resultStore.issue(namespaceId, storageId, {
    attemptId,
    workflowId,
    stepId: input.stepId,
    namespaceId,
    caseId: caseRecord.id,
    agentName,
    briefHash,
  })
  try {
    await ops.bindFactoryStepResult(caseRecord.id, {
      namespaceId,
      agentName,
      attemptId,
      runtimeId,
      capabilityToken: capability.token,
      expiresAt: capability.expiresAt,
    })
  } catch {
    await attemptStore.append(namespaceId, storageId, {
      ...attempt,
      status: 'indeterminate',
      finishedAt: new Date().toISOString(),
      failureCode: 'RESULT_BINDING_FAILED',
    })
    return { ok: false, code: 'RESULT_BINDING_FAILED' }
  }
  const execution = { kind: 'agentos', runtimeId, agentId: agentName, caseId: caseRecord.id, threadId: null }
  const runningRequest = validateWorkflowTransitionRequest(
    {
      workflowId,
      stepId: input.stepId,
      expectedRevision: snapshot.revision,
      requestedStatus: 'running',
      evidenceIds: [],
      idempotencyKey: `${attemptId}:running`,
    },
    workflowId
  )
  if (!runningRequest.ok) throw new Error('INVALID_RUNNING_TRANSITION')
  const running = await projectionStore.transition(namespaceId, runningRequest.value, definition, [], execution)
  if (!running.ok) {
    await attemptStore.append(namespaceId, storageId, {
      ...attempt,
      status: 'failed',
      finishedAt: new Date().toISOString(),
      failureCode: running.error.code,
    })
    return { ok: false, code: running.error.code }
  }
  let turn
  try {
    turn = await ops.runAgentTurn(caseRecord.id, agentName, brief)
  } catch {
    turn = { status: 'error', anchored: false, agentsSelected: [], message: '' }
  }
  let failureCode = null,
    result = null,
    artifacts = [],
    finalizationAttempted = false
  if (turn.status !== 'finished') failureCode = `TURN_${turn.status.toUpperCase()}`
  else if (!turn.anchored) failureCode = 'HISTORY_NOT_ANCHORED'
  else if (turn.agentsSelected.length !== 1 || turn.agentsSelected[0] !== agentName)
    failureCode = 'WORKER_IDENTITY_MISMATCH'
  else {
    result = await resultStore.getByAttempt(namespaceId, storageId, attemptId)
    if (!result) {
      finalizationAttempted = true
      let finalizationTurn
      try {
        finalizationTurn = await ops.runAgentTurn(caseRecord.id, agentName, STRUCTURED_RESULT_FINALIZATION_BRIEF)
      } catch {
        finalizationTurn = { status: 'error', anchored: false, agentsSelected: [], message: '', events: [] }
      }
      if (finalizationTurn.status !== 'finished')
        failureCode = `STRUCTURED_RESULT_FINALIZATION_${String(finalizationTurn.status).toUpperCase()}`
      else if (!finalizationTurn.anchored) failureCode = 'STRUCTURED_RESULT_FINALIZATION_HISTORY_NOT_ANCHORED'
      else if (finalizationTurn.agentsSelected.length !== 1 || finalizationTurn.agentsSelected[0] !== agentName)
        failureCode = 'STRUCTURED_RESULT_FINALIZATION_WORKER_IDENTITY_MISMATCH'
      else {
        result = await resultStore.getByAttempt(namespaceId, storageId, attemptId)
        if (!result) failureCode = 'STRUCTURED_RESULT_MISSING_AFTER_FINALIZATION'
      }
    }
    if (!failureCode) {
      const checked = writable
        ? await verifyArtifacts(result, repoRoot, undefined)
        : await materializeInlineArtifact({
            result,
            repoRoot,
            workflowId,
            stepId: input.stepId,
            attemptId,
            expectedKind: expectedArtifactKind,
          })
      if (!checked.ok) failureCode = checked.code
      else artifacts = checked.artifacts
    }
  }
  if (!failureCode && writable) {
    if ((result.artifacts?.length ?? 0) > 0) failureCode = 'INLINE_ARTIFACT_FORBIDDEN'
    if (!failureCode) {
      const actual = [...new Set(await diffFiles(repoRoot))].sort(),
        claimed = [...new Set(result.claims.modifiedFiles)].sort()
      if (JSON.stringify(actual) !== JSON.stringify(claimed)) failureCode = 'CLAIMS_DIFF_MISMATCH'
      else if (
        actual.some((file) => !input.allowedPaths?.some((prefix) => file === prefix || file.startsWith(`${prefix}/`)))
      )
        failureCode = 'MODIFIED_FILE_OUT_OF_SCOPE'
    }
  }
  if (!failureCode && result.status !== 'PASS') failureCode = 'AGENT_REPORTED_FAIL'
  const evidenceSource = { ...execution },
    artifactEvidence = []
  if (!failureCode && input.stepId === 'technical-review') {
    if (!Array.isArray(result.findings) || result.findings.length > 0) failureCode = 'REVIEW_SCHEMA_INVALID'
  }
  for (const artifact of artifacts) {
    const validated = validateWorkflowEvidenceInput(
      {
        workflowId,
        stepId: input.stepId,
        kind: 'artifact',
        artifactRef: artifact.path,
        artifactHash: artifact.hash,
        idempotencyKey: artifactEvidenceIdempotencyKey(attemptId, artifact.path),
      },
      workflowId
    )
    if (!validated.ok) {
      failureCode = 'INVALID_ARTIFACT_EVIDENCE'
      break
    }
    artifactEvidence.push(
      (await evidenceStore.record(namespaceId, storageId, validated.value, evidenceSource)).evidence
    )
  }
  const outcome = failureCode ? (result?.status === 'FAIL' ? 'fail' : 'indeterminate') : 'pass'
  const resultFacts = {
    resultCode: failureCode ?? 'STRUCTURED_PASS',
    attempt: attemptNumber,
    itemCount: artifacts.length,
    briefHash,
    finalizationTurns: finalizationAttempted ? 1 : 0,
    ...(result ? { claimsHash: hash(canonical(result.claims)) } : {}),
  }
  const validatedResult = validateWorkflowEvidenceInput(
    {
      workflowId,
      stepId: input.stepId,
      kind: 'agent-result',
      outcome,
      facts: resultFacts,
      idempotencyKey: `${attemptId}:result`,
    },
    workflowId
  )
  if (!validatedResult.ok) throw new Error('INVALID_AGENT_RESULT_EVIDENCE')
  const resultEvidence = (await evidenceStore.record(namespaceId, storageId, validatedResult.value, evidenceSource))
    .evidence
  const status = failureCode ? (outcome === 'fail' ? 'failed' : 'indeterminate') : 'succeeded'
  const finished = {
    ...attempt,
    status,
    finishedAt: new Date().toISOString(),
    evidenceId: resultEvidence.evidenceId,
    failureCode,
  }
  if (!terminal(status)) throw new Error('NON_TERMINAL_ATTEMPT')
  await attemptStore.append(namespaceId, storageId, finished)
  if (failureCode) {
    const latest = await projectionStore.read(namespaceId, workflowId)
    const blocked = validateWorkflowTransitionRequest(
      {
        workflowId,
        stepId: input.stepId,
        expectedRevision: latest.revision,
        requestedStatus: 'blocked',
        evidenceIds: [resultEvidence.evidenceId],
        idempotencyKey: `${attemptId}:blocked`,
      },
      workflowId
    )
    if (!blocked.ok)
      return {
        ok: false,
        code: failureCode,
        reconciliationCode: 'INVALID_NEGATIVE_TRANSITION',
        attempt: finished,
        evidence: resultEvidence,
      }
    const transition = await projectionStore.transition(
      namespaceId,
      blocked.value,
      definition,
      [resultEvidence],
      execution
    )
    return transition.ok
      ? { ok: false, code: failureCode, attempt: finished, evidence: resultEvidence, snapshot: transition.snapshot }
      : {
          ok: false,
          code: failureCode,
          reconciliationCode: transition.error.code,
          attempt: finished,
          evidence: resultEvidence,
        }
  }
  const latest = await projectionStore.read(namespaceId, workflowId)
  const completed = validateWorkflowTransitionRequest(
    {
      workflowId,
      stepId: input.stepId,
      expectedRevision: latest.revision,
      requestedStatus: 'completed',
      evidenceIds: [resultEvidence.evidenceId, ...artifactEvidence.map((e) => e.evidenceId)],
      idempotencyKey: `${attemptId}:completed`,
    },
    workflowId
  )
  if (!completed.ok) throw new Error('INVALID_COMPLETION_TRANSITION')
  const transition = await projectionStore.transition(
    namespaceId,
    completed.value,
    definition,
    [resultEvidence, ...artifactEvidence],
    execution
  )
  return transition.ok
    ? {
        ok: true,
        attempt: finished,
        result,
        artifacts,
        evidence: [resultEvidence, ...artifactEvidence],
        snapshot: transition.snapshot,
      }
    : { ok: false, code: transition.error.code, attempt: finished }
}

export const hashAgentBrief = (brief) => hash(brief)
export const hashStructuredAgentResult = (result) => hash(canonical(result))
