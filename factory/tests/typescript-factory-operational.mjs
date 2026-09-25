// Targeted tests for the generated operational bundle. Run only after generation.
import assert from 'node:assert/strict'
import { copyFile, mkdtemp, mkdir, readFile, readdir, rm } from 'node:fs/promises'
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

  // Tranche 6: oracle domain + application (command, executor, baseline, registry).
  const expectedOracleExports = [
    // domain/oracle/oracle.ts
    'countTaskOutcomes', 'diffSnapshots',
    // domain/oracle/oracle-definition.ts
    'validateOracleDefinition', 'hashOracleDefinition', 'OracleDefinitionRegistryCore',
    // application/oracle/oracle-definition-registry.ts
    'OracleDefinitionRegistry',
    // application/oracle/oracle-command.ts
    'resolveBuildHosts', 'resolveOwnerProjects', 'buildOracleCommand',
    // application/oracle/oracle-executor.ts
    'runCommand', 'snapshotDiff', 'diffSince', 'classifyOracleExecution', 'validateOracleRoot',
    'oracleRootIdentity', 'executeOracle', 'oracleArtifact',
    // application/oracle/oracle-baseline.ts
    'normalizeDiagnosticLine', 'extractOracleDiagnostics', 'isInfrastructureIdentity',
    'runBaselineOracle', 'classifyOracleResult', 'buildQuarantineRecord',
  ]
  for (const name of expectedOracleExports) assert.ok(name in module, `missing oracle export ${name}`)
  assert.equal(typeof module.OracleDefinitionRegistry, 'function', 'missing OracleDefinitionRegistry')
  assert.equal(typeof module.executeOracle, 'function', 'missing executeOracle')
  assert.equal(typeof module.runBaselineOracle, 'function', 'missing runBaselineOracle')

  const oracleFacade = await import('../lib/oracle.mjs')
  for (const name of ['countTaskOutcomes', 'diffSnapshots', 'runCommand', 'snapshotDiff', 'diffSince'])
    assert.equal(oracleFacade[name], module[name], `${name} oracle facade identity mismatch`)
  const oracleDefinitionFacade = await import('../lib/oracle-definition.mjs')
  for (const name of ['validateOracleDefinition', 'hashOracleDefinition', 'OracleDefinitionRegistry'])
    assert.equal(oracleDefinitionFacade[name], module[name], `${name} oracle-definition facade identity mismatch`)
  const oracleCommandFacade = await import('../lib/oracle-command.mjs')
  for (const name of ['resolveBuildHosts', 'resolveOwnerProjects', 'buildOracleCommand'])
    assert.equal(oracleCommandFacade[name], module[name], `${name} oracle-command facade identity mismatch`)
  const oracleExecutorFacade = await import('../lib/oracle-executor.mjs')
  for (const name of ['classifyOracleExecution', 'validateOracleRoot', 'oracleRootIdentity', 'executeOracle', 'oracleArtifact'])
    assert.equal(oracleExecutorFacade[name], module[name], `${name} oracle-executor facade identity mismatch`)
  const oracleBaselineFacade = await import('../lib/oracle-baseline.mjs')
  for (const name of ['normalizeDiagnosticLine', 'extractOracleDiagnostics', 'isInfrastructureIdentity', 'runBaselineOracle', 'classifyOracleResult', 'buildQuarantineRecord'])
    assert.equal(oracleBaselineFacade[name], module[name], `${name} oracle-baseline facade identity mismatch`)

  // Exercise the migrated oracle surface for real behaviour.
  assert.deepEqual(
    (({ upToDate, executed }) => ({ upToDate, executed }))(module.countTaskOutcomes('> Task :compileJava UP-TO-DATE')),
    { upToDate: 1, executed: 0 }
  )
  assert.equal(module.diffSnapshots(
    { modified: new Map([['a.ts', 'h1']]), untracked: new Map() },
    { modified: new Map([['a.ts', 'h2']]), untracked: new Map([['b.ts', 'h3']]) }
  ).modified.length, 1)
  const validDefinition = {
    schemaVersion: '1', id: 'smoke', version: '1.0.0', domain: 'factory',
    argv: ['node', 'fixture.mjs'], cwd: 'repo-root', timeoutMs: 1000,
    success: { rule: 'exit-code', requireWork: true },
    applicable: { workflowTypes: ['oracle-smoke'], stepIds: ['verify-code'] },
  }
  const frozenDefinition = module.validateOracleDefinition(validDefinition)
  assert.ok(Object.isFrozen(frozenDefinition))
  assert.throws(() => module.validateOracleDefinition({ ...validDefinition, argv: ['sh', '-c', 'model'] }))
  assert.match(module.hashOracleDefinition(validDefinition), /^sha256:[0-9a-f]{64}$/)
  assert.equal(module.normalizeDiagnosticLine('src/app/foo.ts(42,7): error TS2345: nope'), 'TS:TS2345:src/app/foo.ts:42:7')
  assert.equal(module.normalizeDiagnosticLine(' NX   Running target type-check for 4 projects failed'), null)
  assert.equal(module.isInfrastructureIdentity('TS:TS5090:apps/client/tsconfig.app.json:1:1'), true)
  assert.equal(module.isInfrastructureIdentity('TS:TS2345:src/app/foo.ts:42:7'), false)
  assert.deepEqual(
    module.classifyOracleExecution({ success: { rule: 'exit-code', requireWork: true } }, {
      exitCode: 0, signal: null, timedOut: false, spawnError: null, counts: { executed: 0 },
    }),
    { classification: 'EMPTY_SUCCESS', outcome: 'indeterminate' }
  )

  const oracleSources = [
    'src/domain/oracle/oracle.ts',
    'src/domain/oracle/oracle-definition.ts',
    'src/application/oracle/oracle-definition-registry.ts',
    'src/application/oracle/oracle-command.ts',
    'src/application/oracle/oracle-executor.ts',
    'src/application/oracle/oracle-baseline.ts',
  ]
  for (const source of oracleSources) {
    const inputs = Object.keys(metafile.inputs).filter((input) => input.endsWith(source))
    assert.equal(inputs.length, 1, `${source} must be included exactly once in the operational bundle`)
  }

  // Tranche 7: work-unit environment domain, store, service and controller.
  const expectedEnvironmentExports = [
    // domain/environment/work-unit-environment.ts
    'WORK_UNIT_ENVIRONMENT_STATES', 'WORK_UNIT_ENVIRONMENT_ERROR_CODES',
    'validateNamespaceId', 'validateCanonicalAbsolutePath', 'validateGitRef',
    'validateIsoInstant', 'validateWorkUnitEnvironment',
    // adapters/persistence/work-unit-environment-store.ts
    'ENVIRONMENT_STORE_ERROR_CODES', 'WorkUnitEnvironmentStoreError', 'WorkUnitEnvironmentStore',
    // application/environment/work-unit-environment-service.ts
    'WorkUnitEnvironmentService',
    // application/environment/work-unit-environment-controller.ts
    'WorkUnitEnvironmentController', 'handleWorkUnitEnvironmentRequest',
  ]
  for (const name of expectedEnvironmentExports)
    assert.ok(name in module, `missing work-unit environment export ${name}`)
  for (const name of ['WorkUnitEnvironmentStore', 'WorkUnitEnvironmentService', 'WorkUnitEnvironmentController', 'handleWorkUnitEnvironmentRequest'])
    assert.equal(typeof module[name], 'function', `missing work-unit environment function ${name}`)

  const environmentFacade = await import('../lib/work-unit-environment.mjs')
  for (const name of ['WORK_UNIT_ENVIRONMENT_STATES', 'WORK_UNIT_ENVIRONMENT_ERROR_CODES', 'validateNamespaceId', 'validateCanonicalAbsolutePath', 'validateGitRef', 'validateIsoInstant', 'validateWorkUnitEnvironment'])
    assert.equal(environmentFacade[name], module[name], `${name} environment facade identity mismatch`)
  const environmentStoreFacade = await import('../lib/work-unit-environment-store.mjs')
  for (const name of ['ENVIRONMENT_STORE_ERROR_CODES', 'WorkUnitEnvironmentStoreError', 'WorkUnitEnvironmentStore'])
    assert.equal(environmentStoreFacade[name], module[name], `${name} environment-store facade identity mismatch`)
  const environmentServiceFacade = await import('../lib/work-unit-environment-service.mjs')
  assert.equal(environmentServiceFacade.WorkUnitEnvironmentService, module.WorkUnitEnvironmentService, 'environment-service facade identity mismatch')
  const environmentControllerFacade = await import('../lib/work-unit-environment-controller.mjs')
  for (const name of ['WorkUnitEnvironmentController', 'handleWorkUnitEnvironmentRequest'])
    assert.equal(environmentControllerFacade[name], module[name], `${name} environment-controller facade identity mismatch`)

  // Exercise the migrated environment surface for real behaviour.
  const environmentNamespace = '123e4567-e89b-42d3-a456-426614174000'
  const environmentCase = '223e4567-e89b-42d3-a456-426614174000'
  const environmentBase = {
    schemaVersion: '1', environmentId: 'env-1', workUnitId: 'unit-1', namespaceId: environmentNamespace,
    repoRoot: '/repo', integrationBranch: 'main', branch: 'feature/x', worktreePath: '/worktrees/x',
    baseCommit: null, createdAt: '2025-01-01T00:00:00.000Z', createdBy: 'factory', lifecycleState: 'provisioning',
  }
  assert.equal(module.validateWorkUnitEnvironment(environmentBase).ok, true)
  assert.equal(module.validateWorkUnitEnvironment({ ...environmentBase, branch: '../x' }).ok, false)
  assert.equal(module.validateWorkUnitEnvironment({ ...environmentBase, repoRoot: 'repo' }).ok, false)
  assert.equal(module.validateNamespaceId('not-a-uuid').ok, false)
  assert.equal(module.validateIsoInstant('2025-01-01T00:00:00.000Z').ok, true)
  assert.equal(module.validateIsoInstant('2025-02-30T00:00:00.000Z').ok, false)
  assert.equal(module.validateGitRef('feature/x').ok, true)
  assert.equal(module.validateCanonicalAbsolutePath('/worktrees/x').ok, true)
  assert.deepEqual(module.WORK_UNIT_ENVIRONMENT_STATES, ['provisioning', 'active', 'completed', 'abandoned', 'error', 'removed'])

  const environmentStore = new module.WorkUnitEnvironmentStore(join(temporaryDirectory, 'environment-store'))
  await environmentStore.initialize()
  const reserved = await environmentStore.reserve(environmentBase)
  assert.equal(reserved.ok, true)
  assert.equal(reserved.snapshot.revision, 1)
  assert.equal((await environmentStore.reserve(environmentBase)).changed, false)
  assert.equal((await environmentStore.list(environmentNamespace)).length, 1)
  assert.equal((await environmentStore.read(environmentNamespace, 'env-1')).environment.environmentId, 'env-1')
  {
    let code
    try {
      await environmentStore.read('not-a-uuid', 'env-1')
    } catch (error) {
      code = error.code
    }
    assert.equal(code, module.ENVIRONMENT_STORE_ERROR_CODES.INVALID_NAMESPACE)
  }

  let environmentRevision = 0
  const environmentSnapshots = []
  const environmentStoreStub = {
    async read(_ns, id) { return environmentSnapshots.find((x) => x.environment.environmentId === id) ?? null },
    async reserve(environment) {
      const snapshot = { revision: ++environmentRevision, environment }
      environmentSnapshots.push(snapshot)
      return { ok: true, changed: true, snapshot }
    },
    async transition(_ns, id, environment) {
      const index = environmentSnapshots.findIndex((x) => x.environment.environmentId === id)
      const snapshot = { revision: ++environmentRevision, environment }
      environmentSnapshots[index] = snapshot
      return { ok: true, changed: true, snapshot }
    },
    async list(_ns, { states } = {}) {
      return environmentSnapshots.filter((x) => !states || states.includes(x.environment.lifecycleState))
    },
  }
  const environmentSha = 'a'.repeat(40)
  const environmentGit = {
    async provisionWorktree(input, onReady) {
      await onReady({ ...input, repoRoot: '/repo', worktreePath: input.worktreePath, baseCommit: environmentSha, integrationBranch: input.integrationBranch })
      return { ...input, baseCommit: environmentSha, headCommit: environmentSha }
    },
    async reconcile(environment) { return { status: 'owned', ...environment, headCommit: environmentSha } },
    async removeWorktree() { return { removed: true } },
  }
  const environmentService = new module.WorkUnitEnvironmentService({
    store: environmentStoreStub,
    git: environmentGit,
    clock: () => new Date('2025-01-01T00:00:00.000Z'),
  })
  const environmentInput = {
    environmentId: 'env-svc', workUnitId: 'unit-svc', namespaceId: environmentNamespace,
    repoRoot: '/repo', integrationBranch: 'main', branch: 'feature/svc',
    worktreePath: '/worktrees/svc', createdBy: 'factory',
  }
  const firstProvision = await environmentService.provision(environmentInput)
  const secondProvision = await environmentService.provision(environmentInput)
  assert.equal(firstProvision.ok, true)
  assert.equal(secondProvision.ok, true)
  assert.equal(secondProvision.changed, false)
  const bound = await environmentService.bindParentCase(environmentNamespace, 'env-svc', environmentCase)
  assert.equal(bound.ok, true)
  assert.equal(bound.snapshot.environment.lifecycleState, 'active')
  const inspected = await environmentService.inspect(environmentNamespace, 'env-svc')
  assert.equal(inspected.ok, true)
  assert.equal(inspected.reconciliation.status, 'owned')

  const environmentWorkflowStore = {
    async read() {
      return {
        instance: {
          controllerExecution: { kind: 'agentos', caseId: environmentCase },
          environmentRef: { environmentId: 'env-svc', environmentHash: undefined },
        },
      }
    },
    async bindEnvironment() { return { ok: true } },
  }
  const environmentController = new module.WorkUnitEnvironmentController({
    store: environmentStoreStub,
    git: environmentGit,
    policy: { resolve: async () => ({ repoRoot: '/repo', worktreePath: '/worktrees/policy' }) },
    workflowStore: environmentWorkflowStore,
  })
  const rejectedProvision = await environmentController.provision({
    namespaceId: environmentNamespace,
    caseId: '33333333-3333-4333-8333-333333333333',
    createdBy: 'factory',
    body: { workflowId: 'wf-1', workUnitId: 'unit-1', integrationBranch: 'main', branch: 'feature/x', repoRoot: '/attacker' },
  })
  assert.deepEqual([rejectedProvision.status, rejectedProvision.error.code], [400, 'INVALID_ENVIRONMENT_REQUEST'])

  const requestContext = (caseId) => ({
    method: 'GET',
    path: '/api/factory/workflows/wf-1/environment',
    url: new URL('http://local/api/factory/workflows/wf-1/environment'),
    readBody: async () => ({}),
    send: (status, body) => { requestContext.response = { status, body } },
    controller: environmentController,
    identity: async () => ({ namespaceId: environmentNamespace, caseId, actorId: 'factory' }),
    log: { error() {} },
  })
  await module.handleWorkUnitEnvironmentRequest(requestContext(environmentCase))
  assert.equal(requestContext.response.status, 200)
  await module.handleWorkUnitEnvironmentRequest(requestContext('33333333-3333-4333-8333-333333333333'))
  assert.deepEqual([requestContext.response.status, requestContext.response.body.error.code], [409, 'ENVIRONMENT_NOT_BOUND'])
  assert.equal(
    await module.handleWorkUnitEnvironmentRequest({ ...requestContext(environmentCase), path: '/not-an-environment-route' }),
    false,
    'unrelated paths must not be handled'
  )

  const environmentSources = [
    'src/domain/environment/work-unit-environment.ts',
    'src/adapters/persistence/work-unit-environment-store.ts',
    'src/application/environment/work-unit-environment-service.ts',
    'src/application/environment/work-unit-environment-controller.ts',
  ]
  for (const source of environmentSources) {
    const inputs = Object.keys(metafile.inputs).filter((input) => input.endsWith(source))
    assert.equal(inputs.length, 1, `${source} must be included exactly once in the operational bundle`)
  }

  // Tranche 8: delivery domain, stores, control-plane adapters and controllers.
  const expectedDeliveryExports = [
    // domain/delivery/delivery-definition.ts
    'DELIVERY_DEFINITION_SCHEMA_VERSION', 'DELIVERY_STAGES', 'DELIVERY_EVIDENCE_KINDS',
    'validateDeliveryDefinition', 'hashDeliveryDefinition', 'defaultDeliveryDefinition',
    // domain/delivery/delivery-policy.ts
    'DELIVERY_INITIAL_STAGE', 'validateDeliveryPromotionRequest', 'deliveryScopeHash',
    'deliverySemanticHash', 'evaluateDeliveryPromotion', 'applyDeliveryPromotion',
    // domain/delivery/delivery-operation-definition.ts
    'DELIVERY_OPERATION_KINDS', 'DELIVERY_OPERATION_STATES', 'DELIVERY_OPERATION_ERROR_CODES',
    'canonicalDeliveryHash', 'normalizeDeliveryOperationRequest', 'deriveDeliveryOperationIdentity',
    'validateDeliveryOperationTransition', 'validateDeliveryOperationRecord',
    // domain/delivery/delivery-operation-policy.ts
    'evaluateDeliveryOperationPolicy', 'resolveDeliveryVerificationRequest',
    // adapters/persistence/delivery-store.ts
    'DeliveryStore',
    // adapters/persistence/delivery-evidence-store.ts
    'validateDeliveryEvidence', 'DeliveryEvidenceStore',
    // adapters/delivery/delivery-target-registry.ts
    'DeliveryTargetRegistry', 'unavailableDeliveryTargetRegistry',
    // adapters/delivery/delivery-git-control-plane.ts
    'DeliveryGitControlPlane',
    // adapters/delivery/delivery-pr-adapter.ts
    'DeliveryPullRequestAdapter',
    // adapters/delivery/delivery-deployment-adapter.ts
    'DELIVERY_ADAPTER_OUTCOMES', 'normalizeDeliveryAdapterOutcome', 'DeliveryDeploymentAdapter',
    'DeliveryVerificationAdapter', 'UnconfiguredDeliveryDeploymentAdapter', 'UnconfiguredDeliveryVerificationAdapter',
    // application/delivery/delivery-controller.ts
    'DeliveryController', 'handleDeliveryRequest',
    // application/delivery/delivery-operation-controller.ts
    'DeliveryOperationController',
  ]
  for (const name of expectedDeliveryExports)
    assert.ok(name in module, `missing delivery export ${name}`)
  for (const name of [
    'validateDeliveryDefinition', 'hashDeliveryDefinition', 'defaultDeliveryDefinition',
    'validateDeliveryPromotionRequest', 'deliveryScopeHash', 'deliverySemanticHash',
    'evaluateDeliveryPromotion', 'applyDeliveryPromotion', 'canonicalDeliveryHash',
    'normalizeDeliveryOperationRequest', 'deriveDeliveryOperationIdentity',
    'validateDeliveryOperationTransition', 'validateDeliveryOperationRecord',
    'evaluateDeliveryOperationPolicy', 'resolveDeliveryVerificationRequest',
    'DeliveryStore', 'validateDeliveryEvidence', 'DeliveryEvidenceStore',
    'DeliveryTargetRegistry', 'DeliveryGitControlPlane', 'DeliveryPullRequestAdapter',
    'normalizeDeliveryAdapterOutcome', 'DeliveryDeploymentAdapter', 'DeliveryVerificationAdapter',
    'UnconfiguredDeliveryDeploymentAdapter', 'UnconfiguredDeliveryVerificationAdapter',
    'DeliveryController', 'handleDeliveryRequest', 'DeliveryOperationController',
  ])
    assert.equal(typeof module[name], 'function', `missing delivery function ${name}`)

  const deliveryFacades = {
    '../lib/delivery-definition.mjs': [
      'DELIVERY_DEFINITION_SCHEMA_VERSION', 'DELIVERY_STAGES', 'DELIVERY_EVIDENCE_KINDS',
      'validateDeliveryDefinition', 'hashDeliveryDefinition', 'defaultDeliveryDefinition',
    ],
    '../lib/delivery-policy.mjs': [
      'DELIVERY_INITIAL_STAGE', 'validateDeliveryPromotionRequest', 'deliveryScopeHash',
      'deliverySemanticHash', 'evaluateDeliveryPromotion', 'applyDeliveryPromotion',
    ],
    '../lib/delivery-operation-definition.mjs': [
      'DELIVERY_OPERATION_KINDS', 'DELIVERY_OPERATION_STATES', 'DELIVERY_OPERATION_ERROR_CODES',
      'canonicalDeliveryHash', 'normalizeDeliveryOperationRequest', 'deriveDeliveryOperationIdentity',
      'validateDeliveryOperationTransition', 'validateDeliveryOperationRecord',
    ],
    '../lib/delivery-operation-policy.mjs': [
      'evaluateDeliveryOperationPolicy', 'resolveDeliveryVerificationRequest',
    ],
    '../lib/delivery-store.mjs': ['DeliveryStore'],
    '../lib/delivery-evidence-store.mjs': ['validateDeliveryEvidence', 'DeliveryEvidenceStore'],
    '../lib/delivery-target-registry.mjs': ['DeliveryTargetRegistry', 'unavailableDeliveryTargetRegistry'],
    '../lib/delivery-git-control-plane.mjs': ['DeliveryGitControlPlane'],
    '../lib/delivery-pr-adapter.mjs': ['DeliveryPullRequestAdapter'],
    '../lib/delivery-deployment-adapter.mjs': [
      'DELIVERY_ADAPTER_OUTCOMES', 'normalizeDeliveryAdapterOutcome', 'DeliveryDeploymentAdapter',
      'DeliveryVerificationAdapter', 'UnconfiguredDeliveryDeploymentAdapter', 'UnconfiguredDeliveryVerificationAdapter',
    ],
    '../lib/delivery-controller.mjs': ['DeliveryController', 'handleDeliveryRequest'],
    '../lib/delivery-operation-controller.mjs': ['DeliveryOperationController'],
  }
  for (const [facadePath, names] of Object.entries(deliveryFacades)) {
    const facade = await import(facadePath)
    for (const name of names)
      assert.equal(facade[name], module[name], `${name} facade identity mismatch in ${facadePath}`)
  }

  // Exercise the migrated delivery surface for real behaviour.
  assert.deepEqual(module.DELIVERY_STAGES, [
    'implementation-ready', 'artifact-ready', 'release-approved', 'deployed', 'production-verified',
  ])
  assert.equal(module.DELIVERY_INITIAL_STAGE, 'implementation-ready')
  assert.equal(module.DELIVERY_DEFINITION_SCHEMA_VERSION, '1')
  const deliveryDefinition = module.defaultDeliveryDefinition()
  assert.equal(module.validateDeliveryDefinition(deliveryDefinition).ok, true)
  assert.equal(module.validateDeliveryDefinition({ ...deliveryDefinition, title: '' }).ok, false)
  assert.match(module.hashDeliveryDefinition(deliveryDefinition), /^[0-9a-f]{64}$/)
  assert.match(module.canonicalDeliveryHash({ b: 1, a: 2 }), /^sha256:[0-9a-f]{64}$/)
  assert.equal(module.canonicalDeliveryHash({ b: 1, a: 2 }), module.canonicalDeliveryHash({ a: 2, b: 1 }))
  assert.deepEqual(module.DELIVERY_OPERATION_KINDS, [
    'deployment', 'production-verification', 'rollback', 'rollback-verification',
  ])
  assert.equal(
    module.normalizeDeliveryOperationRequest({ kind: 'deployment', expectedRevision: 1, idempotencyKey: 'k', targetId: 't' }).ok,
    false,
    'deployment without refs must be rejected'
  )
  const deliveryCommit = 'b'.repeat(40)
  const deliveryDigest = `sha256:${'c'.repeat(64)}`
  const normalizedDeployment = module.normalizeDeliveryOperationRequest({
    kind: 'deployment', expectedRevision: 1, idempotencyKey: 'k', targetId: 't',
    artifactRef: { digest: deliveryDigest, mediaType: 'application/vnd.oci.image', producerRef: 'build', buildRef: 'b1', sourceCommit: deliveryCommit },
    releaseRef: { releaseId: 'r1', artifactDigest: deliveryDigest, sourceCommit: deliveryCommit, approvedEvidenceId: 'e1' },
  })
  assert.equal(normalizedDeployment.ok, true)
  const deliveryIdentity = module.deriveDeliveryOperationIdentity(
    { namespaceId: environmentNamespace, workflowId: 'wf-1', deliveryId: 'wf-1-delivery', caseId: environmentCase, runtimeId: 'factory-dashboard' },
    normalizedDeployment.value,
    deliveryDigest
  )
  assert.equal(deliveryIdentity.ok, true)
  assert.match(deliveryIdentity.value.operationId, /^dop_[0-9a-f]{32}$/)
  assert.equal(
    module.validateDeliveryOperationTransition({ operationId: 'op', state: 'pending' }, { operationId: 'op', state: 'running' }).ok,
    true
  )
  assert.equal(
    module.validateDeliveryOperationTransition({ operationId: 'op', state: 'succeeded' }, { operationId: 'op', state: 'running' }).ok,
    false
  )
  assert.equal(module.evaluateDeliveryOperationPolicy({ request: normalizedDeployment.value, snapshot: null, target: null }).code, 'DELIVERY_NOT_FOUND')

  const deliveryNamespace = '423e4567-e89b-42d3-a456-426614174000'
  const deliveryStore = new module.DeliveryStore(join(temporaryDirectory, 'delivery-store'))
  await deliveryStore.initialize()
  const deliverySnapshotInput = {
    schemaVersion: '1',
    deliveryId: 'wf-d-delivery',
    namespaceId: deliveryNamespace,
    workflowId: 'wf-d',
    environmentId: '123e4567-e89b-42d3-a456-426614174000',
    environmentHash: `sha256:${'d'.repeat(64)}`,
    parentCaseId: '223e4567-e89b-42d3-a456-426614174000',
    runtimeId: 'agentos',
    baseCommit: deliveryCommit,
    headCommit: deliveryCommit,
    stage: 'implementation-ready',
    revision: 1,
    evidenceIds: [],
  }
  const createdDelivery = await deliveryStore.create(deliverySnapshotInput)
  assert.equal(createdDelivery.ok, true)
  assert.equal(createdDelivery.changed, true)
  assert.equal((await deliveryStore.create(deliverySnapshotInput)).changed, false)
  const readDelivery = await deliveryStore.read(deliveryNamespace, 'wf-d-delivery')
  assert.equal(readDelivery.deliveryId, 'wf-d-delivery')
  assert.equal(readDelivery.stage, 'implementation-ready')
  assert.equal(readDelivery.snapshotHash, createdDelivery.snapshot.snapshotHash)
  assert.equal((await deliveryStore.inspectDeliveryOperations(deliveryNamespace, 'wf-d-delivery')).operations.length, 0)
  assert.equal(await deliveryStore.hasIndeterminateOperation(deliveryNamespace, 'wf-d-delivery'), false)
  assert.equal(module.validateDeliveryEvidence({ deliveryId: 'wf-d-delivery' }).ok, false)
  const deliveryEvidenceStore = new module.DeliveryEvidenceStore(join(temporaryDirectory, 'delivery-evidence-store'))
  assert.deepEqual(await deliveryEvidenceStore.list(deliveryNamespace, 'wf-d-delivery'), [])

  const deliveryTargetRegistry = new module.DeliveryTargetRegistry([
    {
      targetId: 'prod', environmentKind: 'production', adapterId: 'deploy', adapterTargetRef: 'cluster',
      supportsRollback: true, verificationSuiteId: 'smoke', verificationSuiteHash: deliveryDigest,
    },
  ])
  assert.equal(deliveryTargetRegistry.lookup('prod').ok, true)
  assert.equal(deliveryTargetRegistry.lookup('missing').error.code, 'DELIVERY_TARGET_NOT_FOUND')
  assert.equal(module.unavailableDeliveryTargetRegistry.lookup('prod').error.code, 'DELIVERY_TARGET_REGISTRY_UNAVAILABLE')
  assert.equal(
    module.normalizeDeliveryAdapterOutcome({ state: 'succeeded', correlationRef: 'c1' }).ok,
    true
  )
  assert.equal(module.normalizeDeliveryAdapterOutcome({ state: 'exploded' }).ok, false)
  assert.equal((await new module.UnconfiguredDeliveryDeploymentAdapter().deploy()).error.code, 'DELIVERY_ADAPTER_NOT_CONFIGURED')

  const deliverySources = [
    'src/domain/delivery/delivery-definition.ts',
    'src/domain/delivery/delivery-policy.ts',
    'src/domain/delivery/delivery-operation-definition.ts',
    'src/domain/delivery/delivery-operation-policy.ts',
    'src/adapters/persistence/delivery-store.ts',
    'src/adapters/persistence/delivery-evidence-store.ts',
    'src/adapters/delivery/delivery-target-registry.ts',
    'src/adapters/delivery/delivery-git-control-plane.ts',
    'src/adapters/delivery/delivery-pr-adapter.ts',
    'src/adapters/delivery/delivery-deployment-adapter.ts',
    'src/application/delivery/delivery-controller.ts',
    'src/application/delivery/delivery-operation-controller.ts',
  ]
  for (const source of deliverySources) {
    const inputs = Object.keys(metafile.inputs).filter((input) => input.endsWith(source))
    assert.equal(inputs.length, 1, `${source} must be included exactly once in the operational bundle`)
  }

  // Tranche 9: Forge/BMAD domain, adapters, application services and Jira.
  const expectedForgeExports = [
    // domain/forge-bmad/forge-roots.ts
    'FORGE_ROOTS_SCHEMA_VERSION', 'DEFAULT_RUN_STORE_POLICY', 'EXTERNAL_RUN_STORE_POLICY',
    'REPO_RUN_STORE_POLICY', 'isWithin', 'defaultRunStoreRoot',
    // domain/forge-bmad/forge-human-decision.ts
    'G1_POLICY_VERSION', 'G1_OUTCOMES', 'G1_REASON_CODES', 'canonicalG1', 'computeG1EvidenceSetHash',
    // domain/forge-bmad/forge-spec.ts
    'FORGE_SPEC_SCHEMA_VERSION', 'G2_POLICY_VERSION', 'ORACLE_CATALOG',
    'parseForgeSpecFrontmatter', 'validateForgeSpecSchema', 'computeForgeSpecHash',
    // domain/forge-bmad/forge-story-spec.ts
    'FORGE_STORY_SPEC_SCHEMA_VERSION', 'G2_US_POLICY_VERSION', 'parseStorySpecFrontmatter',
    'validateStorySpec', 'validateInheritance', 'computeStorySpecHash',
    // domain/forge-bmad/forge-bmad-parser.ts
    'parseYamlMinimal', 'extractFrontmatter', 'normalizeForgeRunYaml', 'normalizeStoryFrontmatterFields',
    'normalizeSprintStatus', 'validateStrictForgeYamlSyntax', 'validateForgeRunStructure',
    // domain/forge-bmad/forge-ledger.ts
    'FORGE_LEDGER_SCHEMA_VERSION', 'FORGE_WORKFLOW_VERSION', 'parseForgeLedgerLines', 'projectForgeRun',
    // domain/forge-bmad/forge-workflow-adapter.ts
    'FORGE_WORKFLOW_ERROR_CODES', 'adaptForgeRunToWorkflowProjection',
    // domain/forge-bmad/jira.ts
    'COMMENTS_CHAR_BUDGET', 'extractTicketId', 'extractAdfText', 'applyCommentBudget',
    // adapters/forge/forge-roots-resolver.ts
    'resolveForgeRoots', 'ensureForgeRunStore',
    // adapters/forge/forge-bmad-file-reader.ts
    'readForgeRunYaml', 'readForgeRunYamlStrict', 'readStoryFrontmatter', 'readSprintStatus',
    // adapters/forge/forge-spec-reader.ts
    'loadForgeSpec', 'readStorySpec', 'hashStorySpec',
    // adapters/forge/forge-ledger-store.ts
    'appendForgeLedgerEvent', 'createEpicRun', 'parseForgeLedger', 'listForgeRunProjections',
    // adapters/jira/jira-client.ts
    'fetchJiraComments', 'fetchJiraTicket',
    // application/forge-bmad/forge-human-decision.ts
    'recordHumanDecision',
    // application/forge-bmad/forge-g2.ts
    'evaluateG2', 'evaluateG2US',
    // application/forge-bmad/forge-story-analysis.ts
    'AGENT_EXECUTION_REFERENCE_SCHEMA_VERSION', 'STORY_ANALYSIS_POLICY_VERSION',
    'STORY_ANALYSIS_PLAN_SCHEMA_VERSION', 'writeStoryAnalysisArtifact', 'executeStoryAnalysis',
    // application/forge-bmad/forge-story-edit.ts
    'STORY_EDIT_SCHEMA_VERSION', 'STORY_EDIT_POLICY_VERSION', 'executeStoryEdit',
    // application/forge-bmad/forge-story-oracles.ts
    'STORY_ORACLE_POLICY_VERSION', 'isAllowedStoryOracleRequestBody', 'executeStoryOracles',
    // application/forge-bmad/forge-workflow-sync.ts
    'SAFE_FORGE_TICKET_ID', 'sanitizeForgeSyncAttribution', 'syncForgeWorkflowProjection',
    // application/forge-bmad/forge-front-oracle-resolution.ts
    'FRONT_ORACLE_MAP_SCHEMA_VERSION', 'resolveOwnerProjectConfigs', 'inspectNxProject',
    'parseFrontBuildHostMap', 'resolveFrontOraclePlan',
  ]
  for (const name of expectedForgeExports) assert.ok(name in module, `missing forge export ${name}`)
  for (const name of [
    'isWithin', 'defaultRunStoreRoot', 'canonicalG1', 'computeG1EvidenceSetHash', 'parseForgeSpecFrontmatter',
    'validateForgeSpecSchema', 'computeForgeSpecHash', 'parseStorySpecFrontmatter', 'validateStorySpec',
    'validateInheritance', 'computeStorySpecHash', 'parseYamlMinimal', 'extractFrontmatter',
    'normalizeForgeRunYaml', 'normalizeStoryFrontmatterFields', 'normalizeSprintStatus',
    'validateStrictForgeYamlSyntax', 'validateForgeRunStructure', 'parseForgeLedgerLines', 'projectForgeRun',
    'adaptForgeRunToWorkflowProjection', 'extractTicketId', 'extractAdfText', 'applyCommentBudget',
    'resolveForgeRoots', 'ensureForgeRunStore', 'readForgeRunYaml', 'readForgeRunYamlStrict',
    'readStoryFrontmatter', 'readSprintStatus', 'loadForgeSpec', 'readStorySpec', 'hashStorySpec',
    'appendForgeLedgerEvent', 'createEpicRun', 'parseForgeLedger', 'listForgeRunProjections',
    'fetchJiraComments', 'fetchJiraTicket', 'recordHumanDecision', 'evaluateG2', 'evaluateG2US',
    'writeStoryAnalysisArtifact', 'executeStoryAnalysis', 'executeStoryEdit',
    'isAllowedStoryOracleRequestBody', 'executeStoryOracles', 'sanitizeForgeSyncAttribution',
    'syncForgeWorkflowProjection', 'resolveOwnerProjectConfigs', 'inspectNxProject',
    'parseFrontBuildHostMap', 'resolveFrontOraclePlan',
  ])
    assert.equal(typeof module[name], 'function', `missing forge function ${name}`)

  const forgeFacades = {
    '../lib/forge-roots.mjs': [
      'FORGE_ROOTS_SCHEMA_VERSION', 'DEFAULT_RUN_STORE_POLICY', 'EXTERNAL_RUN_STORE_POLICY',
      'REPO_RUN_STORE_POLICY', 'resolveForgeRoots', 'defaultRunStoreRoot', 'ensureForgeRunStore',
    ],
    '../lib/forge-spec.mjs': [
      'FORGE_SPEC_SCHEMA_VERSION', 'G2_POLICY_VERSION', 'ORACLE_CATALOG', 'loadForgeSpec',
    ],
    '../lib/forge-story-spec.mjs': [
      'FORGE_STORY_SPEC_SCHEMA_VERSION', 'G2_US_POLICY_VERSION', 'validateInheritance', 'readStorySpec', 'hashStorySpec',
    ],
    '../lib/forge-bmad-reader.mjs': [
      'readForgeRunYaml', 'readForgeRunYamlStrict', 'readStoryFrontmatter', 'readSprintStatus',
    ],
    '../lib/forge-human-decision.mjs': [
      'G1_POLICY_VERSION', 'computeG1EvidenceSetHash', 'recordHumanDecision',
    ],
    '../lib/forge-ledger.mjs': [
      'FORGE_LEDGER_SCHEMA_VERSION', 'FORGE_WORKFLOW_VERSION', 'createEpicRun', 'parseForgeLedger',
      'projectForgeRun', 'listForgeRunProjections',
    ],
    '../lib/forge-g2.mjs': ['evaluateG2', 'evaluateG2US'],
    '../lib/forge-story-analysis.mjs': [
      'AGENT_EXECUTION_REFERENCE_SCHEMA_VERSION', 'STORY_ANALYSIS_POLICY_VERSION',
      'STORY_ANALYSIS_PLAN_SCHEMA_VERSION', 'writeStoryAnalysisArtifact', 'executeStoryAnalysis',
    ],
    '../lib/forge-story-edit.mjs': ['STORY_EDIT_SCHEMA_VERSION', 'STORY_EDIT_POLICY_VERSION', 'executeStoryEdit'],
    '../lib/forge-story-oracles.mjs': [
      'STORY_ORACLE_POLICY_VERSION', 'isAllowedStoryOracleRequestBody', 'executeStoryOracles',
    ],
    '../lib/forge-workflow-adapter.mjs': ['FORGE_WORKFLOW_ERROR_CODES', 'adaptForgeRunToWorkflowProjection'],
    '../lib/forge-workflow-sync.mjs': [
      'SAFE_FORGE_TICKET_ID', 'sanitizeForgeSyncAttribution', 'syncForgeWorkflowProjection',
    ],
    '../lib/forge-front-oracle-resolution.mjs': [
      'FRONT_ORACLE_MAP_SCHEMA_VERSION', 'resolveOwnerProjectConfigs', 'inspectNxProject',
      'parseFrontBuildHostMap', 'resolveFrontOraclePlan',
    ],
    '../lib/jira.mjs': [
      'extractTicketId', 'extractAdfText', 'fetchJiraComments', 'applyCommentBudget', 'fetchJiraTicket',
    ],
  }
  for (const [facadePath, names] of Object.entries(forgeFacades)) {
    const facade = await import(facadePath)
    for (const name of names) assert.equal(facade[name], module[name], `${name} facade identity mismatch in ${facadePath}`)
  }

  // Exercise the migrated Forge/Jira surface for real behaviour.
  assert.equal(module.defaultRunStoreRoot('/repo'), '/repo/forge/factory-runs')
  assert.equal(module.G1_POLICY_VERSION, 'forge-g1-human-v1')
  assert.equal(module.G2_POLICY_VERSION, 'forge-g2-deterministic-v1')
  assert.equal(module.G2_US_POLICY_VERSION, 'forge-g2-us-deterministic-v1')
  assert.equal(module.STORY_ORACLE_POLICY_VERSION, 'forge-story-oracles-v1')
  assert.equal(module.STORY_EDIT_POLICY_VERSION, 'forge-story-edit-v1')
  assert.equal(module.STORY_ANALYSIS_POLICY_VERSION, 'forge-story-analysis-v2')
  assert.equal(module.FORGE_LEDGER_SCHEMA_VERSION, 1)
  assert.ok(module.ORACLE_CATALOG.has('front.build'))
  assert.equal(module.extractTicketId('https://acme.atlassian.net/browse/proj-1234'), 'PROJ-1234')
  assert.equal(module.extractTicketId('not-a-ticket'), null)
  assert.equal(
    module.extractAdfText({ type: 'doc', content: [{ type: 'paragraph', content: [{ type: 'text', text: 'hi' }] }] }),
    'hi\n'
  )
  assert.deepEqual(module.applyCommentBudget([{ author: 'a', created: '', body: '' }], 8000), {
    included: [{ author: 'a', created: '', body: '' }],
    omitted: 0,
  })
  assert.equal(module.projectForgeRun([]), null)
  assert.equal(module.parseForgeLedgerLines('{"schemaVersion":1}\n').length, 1)
  assert.throws(() => module.parseForgeLedgerLines('not json'), /invalid JSONL/)
  assert.deepEqual(
    module.validateInheritance(
      { scope: { allow: ['apps/**'], create: [], deny: [] }, oracles: [] },
      { scope: { allow: ['apps/**'], create: [], deny: [] }, oracles: [] }
    ),
    { valid: true, violations: [] }
  )
  const validProjection = module.adaptForgeRunToWorkflowProjection({
    ticketId: 'WZ-1', ticketSummary: null, gates: {}, runOutcome: { status: 'in-progress' },
  })
  assert.equal(validProjection.ok, true)
  assert.equal(validProjection.projection.workflowId, 'forge-run-WZ-1')
  assert.equal(module.sanitizeForgeSyncAttribution({ actorId: 'factory' }).ok, true)
  assert.equal(module.sanitizeForgeSyncAttribution({ prompt: 'x' }).error.code, 'INVALID_ATTRIBUTION')
  assert.equal(module.isAllowedStoryOracleRequestBody({ editId: 'e', expectedSpecHash: 'h', attempt: 1 }), true)
  assert.equal(module.isAllowedStoryOracleRequestBody({ command: 'rm' }), false)
  assert.equal(module.validateStrictForgeYamlSyntax('ticket_id: WZ-1\nrun_outcome:\n  status: in-progress\n'), true)
  assert.equal(module.validateStrictForgeYamlSyntax('- list'), false)
  assert.equal(module.validateForgeRunStructure({ ticket_id: 'WZ-1', run_outcome: { status: 'in-progress' } }, 'WZ-1').ok, true)
  assert.deepEqual(module.parseFrontBuildHostMap('{"*":["a"]}'), { '*': ['a'] })

  const forgeSources = [
    'src/domain/forge-bmad/types.ts',
    'src/domain/forge-bmad/forge-roots.ts',
    'src/domain/forge-bmad/forge-human-decision.ts',
    'src/domain/forge-bmad/forge-spec.ts',
    'src/domain/forge-bmad/forge-story-spec.ts',
    'src/domain/forge-bmad/forge-bmad-parser.ts',
    'src/domain/forge-bmad/forge-ledger.ts',
    'src/domain/forge-bmad/forge-workflow-adapter.ts',
    'src/domain/forge-bmad/jira.ts',
    'src/adapters/forge/forge-roots-resolver.ts',
    'src/adapters/forge/forge-bmad-file-reader.ts',
    'src/adapters/forge/forge-spec-reader.ts',
    'src/adapters/forge/forge-ledger-store.ts',
    'src/adapters/jira/jira-client.ts',
    'src/application/forge-bmad/forge-human-decision.ts',
    'src/application/forge-bmad/forge-g2.ts',
    'src/application/forge-bmad/forge-story-analysis.ts',
    'src/application/forge-bmad/forge-story-edit.ts',
    'src/application/forge-bmad/forge-story-oracles.ts',
    'src/application/forge-bmad/forge-workflow-sync.ts',
    'src/application/forge-bmad/forge-front-oracle-resolution.ts',
  ]
  for (const source of forgeSources) {
    const inputs = Object.keys(metafile.inputs).filter((input) => input.endsWith(source))
    assert.equal(inputs.length, 1, `${source} must be included exactly once in the operational bundle`)
  }

  // Domain purity: the Forge/BMAD domain must not reach for I/O, HTTP or process runners.
  const domainDirectory = resolve(import.meta.dirname, '../src/domain/forge-bmad')
  const forbiddenImport = /(?:from|import\s*\()\s*['"]node:(?:fs|child_process|http|https|net)['"]/
  for (const file of await readdir(domainDirectory)) {
    const source = await readFile(join(domainDirectory, file), 'utf8')
    assert.doesNotMatch(source, forbiddenImport, `${file} must not import an I/O or process runner module`)
  }
} finally {
  if (previousObservabilityFile === undefined) delete process.env.FACTORY_ACTIVE_CASE_FILE
  else process.env.FACTORY_ACTIVE_CASE_FILE = previousObservabilityFile
  await rm(temporaryDirectory, { recursive: true, force: true })
}

console.log('✓ TypeScript Factory operational and active-case contracts')
