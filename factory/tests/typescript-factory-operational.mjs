// Targeted tests for the generated operational bundle. Run only after generation.
import assert from 'node:assert/strict'
import { copyFile, mkdtemp, mkdir, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { pathToFileURL } from 'node:url'

const artifactPath = resolve(import.meta.dirname, '../runtime/factory-operational.mjs')
const metafilePath = resolve(import.meta.dirname, '../dist/factory-operational/factory-operational.meta.json')
const activeCaseSource = '../src/lib/active-case.ts'
const registrySource = '../src/lib/registry.ts'
const expectedActiveCaseExports = [
  'clearActiveCaseId', 'getActiveCaseId', 'getActiveCaseIds', 'registerActiveCase',
  'setActiveCaseId', 'unregisterActiveCase',
]
const expectedRegistryExports = [
  'createRun', 'startPhase', 'passPhase', 'failPhase', 'endRun',
  'endCurrentRunOnce', 'getCurrentRun',
]

async function importFresh(modulePath, observabilityFile) {
  if (observabilityFile === undefined) delete process.env.FACTORY_ACTIVE_CASE_FILE
  else process.env.FACTORY_ACTIVE_CASE_FILE = observabilityFile
  return import(`${pathToFileURL(modulePath).href}?test=${crypto.randomUUID()}`)
}

async function exerciseActiveCase(module) {
  for (const name of expectedActiveCaseExports) assert.equal(typeof module[name], 'function', `missing ${name}`)
  assert.deepEqual(module.getActiveCaseIds(), [])
  module.registerActiveCase('case-a', 'editor')
  module.registerActiveCase('case-a', 'ignored')
  module.registerActiveCase('case-b', 'reviewer')
  assert.deepEqual(module.getActiveCaseIds(), ['case-a', 'case-b'])
  const snapshot = module.getActiveCaseIds()
  module.unregisterActiveCase('case-a')
  module.unregisterActiveCase('case-a')
  assert.deepEqual(snapshot, ['case-a', 'case-b'])
  module.setActiveCaseId('case-legacy')
  module.clearActiveCaseId(null)
  module.clearActiveCaseId('case-b')
  assert.equal(module.getActiveCaseId(), 'case-legacy')
  module.clearActiveCaseId('case-legacy')
  assert.equal(module.getActiveCaseId(), null)
}

const temporaryDirectory = await mkdtemp(join(tmpdir(), 'factory-operational-'))
const previousObservabilityFile = process.env.FACTORY_ACTIVE_CASE_FILE
try {
  const module = await import(pathToFileURL(artifactPath).href)
  await exerciseActiveCase(module)
  for (const name of expectedRegistryExports) assert.equal(typeof module[name], 'function', `missing ${name}`)

  const facade = await import('../lib/registry.mjs')
  for (const name of expectedRegistryExports) assert.equal(facade[name], module[name], `${name} facade identity mismatch`)

  // AgentRuntimeGateway isolation: the operational bundle must expose the port factory
  // and every legacy-compatible AgentOS operation the facade delegates to.
  const expectedAgentOsExports = [
    'createAgentOsRuntimeAdapter', 'createAgentOsHttpClient', 'getAgentOsRuntimeAdapter',
    'createCase', 'postMessage', 'bindFactoryStepResult', 'getCase', 'listEvents',
    'killCase', 'listAgents', 'preflightAgent', 'listIntegrations',
    'preflightWorkspace', 'preflightWritableWorkspace', 'preflightReadOnlyWorkspace', 'runAgentTurn',
  ]
  for (const name of expectedAgentOsExports) assert.equal(typeof module[name], 'function', `missing ${name}`)
  const agentOsFacade = await import('../lib/agentos.mjs')
  for (const name of expectedAgentOsExports.filter((n) => n !== 'createAgentOsRuntimeAdapter' && n !== 'createAgentOsHttpClient' && n !== 'getAgentOsRuntimeAdapter')) {
    assert.equal(agentOsFacade[name], module[name], `${name} AgentOS facade identity mismatch`)
  }

  const calls = []
  module.registerActiveCase('case-a')
  const controller = module.createShutdownController({
    activeCaseIds: module.getActiveCaseIds,
    caseTerminator: { terminate: async (id) => calls.push(['terminate', id]) },
    endCurrentRunOnce: (status, facts) => {
      calls.push(['endCurrentRunOnce', status, facts])
      return true
    },
    rejectPendingGates: () => calls.push(['reject']),
    warn: () => {},
    exit: (code) => calls.push(['exit', code]),
  })
  await controller.handle('SIGTERM')
  await controller.handle('SIGTERM')
  module.unregisterActiveCase('case-a')
  assert.equal(calls.filter(([name]) => name === 'terminate').length, 1, 'shutdown is not idempotent')
  assert.equal(calls.filter(([name]) => name === 'endCurrentRunOnce').length, 1, 'run ended more than once')
  assert.equal(calls.at(-1)?.[1], 1, 'shutdown did not request exit code 1')

  const observabilityFile = join(temporaryDirectory, 'active-case.txt')
  const observableModule = await importFresh(artifactPath, observabilityFile)
  observableModule.registerActiveCase('observed')
  assert.equal(await readFile(observabilityFile, 'utf8'), 'observed')
  observableModule.unregisterActiveCase('observed')
  await assert.rejects(readFile(observabilityFile), { code: 'ENOENT' })

  const relocatedDirectory = join(temporaryDirectory, 'relocated')
  await mkdir(relocatedDirectory)
  const relocatedArtifact = join(relocatedDirectory, 'factory-operational.mjs')
  await copyFile(artifactPath, relocatedArtifact)
  await exerciseActiveCase(await importFresh(relocatedArtifact))

  const artifactSource = await readFile(artifactPath, 'utf8')
  assert.match(artifactSource, /GENERATED FILE.*DO NOT EDIT/)
  assert.doesNotMatch(artifactSource, /(?:from|import\s*\()\s*['"](?!node:)[^'"]+['"]/, 'bundle has an external non-node import')

  const metafile = JSON.parse(await readFile(metafilePath, 'utf8'))
  const activeCaseInputs = Object.keys(metafile.inputs).filter((input) => input.endsWith(activeCaseSource))
  assert.equal(activeCaseInputs.length, 1, 'active-case source must be included exactly once in the operational bundle')
  const registryInputs = Object.keys(metafile.inputs).filter((input) => input.endsWith(registrySource))
  assert.equal(registryInputs.length, 1, 'registry source must be included exactly once in the operational bundle')
  const adapterInputs = Object.keys(metafile.inputs).filter((input) => input.includes('src/adapters/agentos/'))
  assert.equal(adapterInputs.length, 6, 'AgentOS adapter sources must be included exactly once each in the operational bundle')
  const gatewayPortInputs = Object.keys(metafile.inputs).filter((input) => input.endsWith('src/ports/agent-runtime-gateway.ts'))
  assert.equal(gatewayPortInputs.length, 1, 'agent-runtime-gateway port must be included exactly once in the operational bundle')

  // Tranche 5: agent-step attempts, structured results and the step executor.
  const expectedAgentAttemptExports = [
    'AGENT_STEP_ATTEMPT_STATUSES', 'AgentStepAttemptStore', 'AgentStepResultStore',
    'hashAgentStepResult', 'artifactEvidenceIdempotencyKey', 'parseAgentStepResult',
    'materializeInlineArtifact', 'executeAgentStepAttempt', 'hashAgentBrief', 'hashStructuredAgentResult',
  ]
  for (const name of expectedAgentAttemptExports) assert.ok(name in module, `missing ${name}`)
  assert.equal(typeof module.AgentStepAttemptStore, 'function', 'missing AgentStepAttemptStore')
  assert.equal(typeof module.AgentStepResultStore, 'function', 'missing AgentStepResultStore')
  assert.equal(typeof module.executeAgentStepAttempt, 'function', 'missing executeAgentStepAttempt')

  const attemptStoreFacade = await import('../lib/agent-step-attempt-store.mjs')
  assert.equal(attemptStoreFacade.AGENT_STEP_ATTEMPT_STATUSES, module.AGENT_STEP_ATTEMPT_STATUSES, 'statuses facade identity mismatch')
  assert.equal(attemptStoreFacade.AgentStepAttemptStore, module.AgentStepAttemptStore, 'attempt store facade identity mismatch')
  const resultStoreFacade = await import('../lib/agent-step-result-store.mjs')
  assert.equal(resultStoreFacade.AgentStepResultStore, module.AgentStepResultStore, 'result store facade identity mismatch')
  assert.equal(resultStoreFacade.hashAgentStepResult, module.hashAgentStepResult, 'hash facade identity mismatch')
  const executorFacade = await import('../lib/factory-agent-step-executor.mjs')
  for (const name of ['artifactEvidenceIdempotencyKey', 'parseAgentStepResult', 'materializeInlineArtifact', 'executeAgentStepAttempt', 'hashAgentBrief', 'hashStructuredAgentResult'])
    assert.equal(executorFacade[name], module[name], `${name} executor facade identity mismatch`)

  const attemptStore = new module.AgentStepAttemptStore(join(temporaryDirectory, 'attempts'))
  const baseAttempt = {
    attemptId: 'attempt-a', workflowId: 'wf', workflowRevisionAtStart: 1, stepId: 'ticket-analysis',
    attemptNumber: 1, namespaceId: 'ns', runtimeId: 'factory-runner', caseId: null,
    agentName: 'Worker', briefHash: `sha256:${'a'.repeat(64)}`, status: 'starting',
    startedAt: new Date().toISOString(), finishedAt: null, evidenceId: null, failureCode: null,
  }
  await attemptStore.append('ns', 'storage', baseAttempt)
  await attemptStore.append('ns', 'storage', { ...baseAttempt, caseId: 'case', status: 'running' })
  await attemptStore.append('ns', 'storage', { ...baseAttempt, caseId: 'case', status: 'succeeded', finishedAt: new Date().toISOString(), evidenceId: 'e' })
  assert.deepEqual((await attemptStore.list('ns', 'storage')).map((x) => x.status), ['starting', 'running', 'succeeded'])

  const resultStore = new module.AgentStepResultStore(join(temporaryDirectory, 'results'))
  const identity = { attemptId: 'attempt-a', workflowId: 'wf', stepId: 'ticket-analysis', namespaceId: 'ns', caseId: 'case', agentName: 'Worker', briefHash: `sha256:${'a'.repeat(64)}` }
  const business = { status: 'PASS', summary: 'done', claims: { modifiedFiles: [] } }
  const capability = await resultStore.issue('ns', 'storage', identity)
  const submitted = await resultStore.submit(capability.token, business, { attemptId: 'attempt-a', caseId: 'case', agentName: 'Worker' })
  assert.equal(submitted.ok, true)
  assert.equal(submitted.idempotent, false)
  assert.equal((await resultStore.getByAttempt('ns', 'storage', 'attempt-a'))?.summary, 'done')
  assert.equal(module.hashAgentStepResult(business), (await resultStore.getByAttempt('ns', 'storage', 'attempt-a'))?.resultHash)
  assert.equal(module.parseAgentStepResult('{"status":"PASS","summary":"ok","claims":{"modifiedFiles":[]}}').ok, true)
  assert.equal(module.parseAgentStepResult('not json').code, 'RESULT_NOT_JSON')

  const agentAttemptSources = [
    'src/domain/agent-attempt/agent-step-attempt.ts',
    'src/domain/agent-attempt/agent-step-result.ts',
    'src/adapters/persistence/agent-step-attempt-store.ts',
    'src/adapters/persistence/agent-step-result-store.ts',
    'src/application/agent-attempt/factory-agent-step-executor.ts',
    'src/application/agentos-operations.ts',
  ]
  for (const source of agentAttemptSources) {
    const inputs = Object.keys(metafile.inputs).filter((input) => input.endsWith(source))
    assert.equal(inputs.length, 1, `${source} must be included exactly once in the operational bundle`)
  }
} finally {
  if (previousObservabilityFile === undefined) delete process.env.FACTORY_ACTIVE_CASE_FILE
  else process.env.FACTORY_ACTIVE_CASE_FILE = previousObservabilityFile
  await rm(temporaryDirectory, { recursive: true, force: true })
}

console.log('✓ TypeScript Factory operational and active-case contracts')
