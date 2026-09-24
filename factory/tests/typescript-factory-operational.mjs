// Targeted tests for the generated operational bundle. Run only after generation.
import assert from 'node:assert/strict'
import { copyFile, mkdtemp, mkdir, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { pathToFileURL } from 'node:url'

const artifactPath = resolve(import.meta.dirname, '../runtime/factory-operational.mjs')
const metafilePath = resolve(import.meta.dirname, '../dist/factory-operational/factory-operational.meta.json')
const activeCaseSource = '../src/lib/active-case.ts'
const expectedActiveCaseExports = [
  'clearActiveCaseId', 'getActiveCaseId', 'getActiveCaseIds', 'registerActiveCase',
  'setActiveCaseId', 'unregisterActiveCase',
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
  const module = await importFresh(artifactPath)
  await exerciseActiveCase(module)

  const calls = []
  module.registerActiveCase('case-a')
  const controller = module.createShutdownController({
    activeCaseIds: module.getActiveCaseIds,
    caseTerminator: { terminate: async (id) => calls.push(['terminate', id]) },
    currentRun: () => ({ filePath: '/tmp/run', _startedAt: 0 }),
    endRun: (_run, status, facts) => calls.push(['endRun', status, facts]),
    rejectPendingGates: () => calls.push(['reject']),
    warn: () => {},
    exit: (code) => calls.push(['exit', code]),
  })
  await controller.handle('SIGTERM')
  await controller.handle('SIGTERM')
  module.unregisterActiveCase('case-a')
  assert.equal(calls.filter(([name]) => name === 'terminate').length, 1, 'shutdown is not idempotent')
  assert.equal(calls.filter(([name]) => name === 'endRun').length, 1, 'run ended more than once')
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
} finally {
  if (previousObservabilityFile === undefined) delete process.env.FACTORY_ACTIVE_CASE_FILE
  else process.env.FACTORY_ACTIVE_CASE_FILE = previousObservabilityFile
  await rm(temporaryDirectory, { recursive: true, force: true })
}

console.log('✓ TypeScript Factory operational and active-case contracts')
