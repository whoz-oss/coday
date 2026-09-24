import assert from 'node:assert/strict'
import { copyFile, mkdtemp, mkdir, readFile, rm, stat } from 'node:fs/promises'
import { pathToFileURL } from 'node:url'
import { join, resolve } from 'node:path'
import { tmpdir } from 'node:os'

const artifactPath = resolve(import.meta.dirname, '../dist/stage-1/active-case.mjs')
const expectedExports = [
  'clearActiveCaseId',
  'getActiveCaseId',
  'getActiveCaseIds',
  'registerActiveCase',
  'setActiveCaseId',
  'unregisterActiveCase',
]

async function importFresh(modulePath, observabilityFile) {
  if (observabilityFile === undefined) {
    delete process.env.FACTORY_ACTIVE_CASE_FILE
  } else {
    process.env.FACTORY_ACTIVE_CASE_FILE = observabilityFile
  }
  return import(`${pathToFileURL(modulePath).href}?test=${crypto.randomUUID()}`)
}

async function assertMissing(path) {
  await assert.rejects(stat(path), { code: 'ENOENT' })
}

async function exerciseContract(module) {
  assert.deepEqual(Object.keys(module).sort(), expectedExports)
  assert.deepEqual(module.getActiveCaseIds(), [])
  assert.equal(module.getActiveCaseId(), null)

  module.registerActiveCase('case-a', 'editor')
  module.registerActiveCase('case-a', 'ignored-label')
  module.registerActiveCase('case-b', 'reviewer')
  assert.deepEqual(module.getActiveCaseIds(), ['case-a', 'case-b'])
  assert.equal(module.getActiveCaseId(), 'case-a')

  const snapshot = module.getActiveCaseIds()
  module.unregisterActiveCase('case-a')
  module.unregisterActiveCase('case-a')
  assert.deepEqual(snapshot, ['case-a', 'case-b'])
  assert.deepEqual(module.getActiveCaseIds(), ['case-b'])

  module.setActiveCaseId('legacy')
  assert.equal(module.getActiveCaseId(), 'case-b')
  module.clearActiveCaseId(null)
  module.clearActiveCaseId('case-b')
  assert.equal(module.getActiveCaseId(), 'legacy')
  module.clearActiveCaseId('legacy')
  assert.equal(module.getActiveCaseId(), null)
}

const temporaryDirectory = await mkdtemp(join(tmpdir(), 'factory-typescript-stage1-'))
const previousObservabilityFile = process.env.FACTORY_ACTIVE_CASE_FILE

try {
  const module = await importFresh(artifactPath)
  await exerciseContract(module)

  const observabilityFile = join(temporaryDirectory, 'active-case.txt')
  const observableModule = await importFresh(artifactPath, observabilityFile)
  observableModule.registerActiveCase('observed-a')
  assert.equal(await readFile(observabilityFile, 'utf8'), 'observed-a')
  observableModule.registerActiveCase('observed-b')
  assert.equal(await readFile(observabilityFile, 'utf8'), 'observed-b')
  observableModule.unregisterActiveCase('observed-a')
  assert.equal(await readFile(observabilityFile, 'utf8'), 'observed-b')
  observableModule.unregisterActiveCase('observed-b')
  await assertMissing(observabilityFile)

  const unwritablePath = join(temporaryDirectory, 'missing', 'active-case.txt')
  const silentIoModule = await importFresh(artifactPath, unwritablePath)
  assert.doesNotThrow(() => silentIoModule.registerActiveCase('io-error'))
  assert.doesNotThrow(() => silentIoModule.unregisterActiveCase('io-error'))

  const relocatedDirectory = join(temporaryDirectory, 'relocated')
  await mkdir(relocatedDirectory)
  const relocatedArtifact = join(relocatedDirectory, 'active-case.mjs')
  await copyFile(artifactPath, relocatedArtifact)
  const relocatedModule = await importFresh(relocatedArtifact)
  await exerciseContract(relocatedModule)

  const artifactSource = await readFile(artifactPath, 'utf8')
  assert.doesNotMatch(artifactSource, /from ['"](?!node:)[^'"]+['"]/)
  assert.doesNotMatch(artifactSource, /factory\/src|toolchain\/node_modules/)
} finally {
  if (previousObservabilityFile === undefined) {
    delete process.env.FACTORY_ACTIVE_CASE_FILE
  } else {
    process.env.FACTORY_ACTIVE_CASE_FILE = previousObservabilityFile
  }
  await rm(temporaryDirectory, { recursive: true, force: true })
}

console.log('✓ Stage 1 TypeScript active-case contract')
