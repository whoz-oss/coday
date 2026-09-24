import assert from 'node:assert/strict'
import { copyFile, mkdtemp, mkdir, readFile, rm, stat } from 'node:fs/promises'
import { pathToFileURL } from 'node:url'
import { join, resolve } from 'node:path'
import { tmpdir } from 'node:os'

const artifactPath = resolve(import.meta.dirname, '../runtime/active-case-contract.mjs')
const sourcePaths = [
  resolve(import.meta.dirname, '../src/lib/active-case.ts'),
  resolve(import.meta.dirname, '../src/entrypoints/active-case-contract.ts'),
  resolve(import.meta.dirname, '../toolchain/build.mjs'),
]
const expectedExports = [
  'clearActiveCaseId', 'getActiveCaseId', 'getActiveCaseIds', 'registerActiveCase',
  'setActiveCaseId', 'unregisterActiveCase', 'verifyActiveCaseContract',
]

async function importFresh(modulePath, observabilityFile) {
  if (observabilityFile === undefined) delete process.env.FACTORY_ACTIVE_CASE_FILE
  else process.env.FACTORY_ACTIVE_CASE_FILE = observabilityFile
  return import(`${pathToFileURL(modulePath).href}?test=${crypto.randomUUID()}`)
}

async function assertMissing(path) {
  await assert.rejects(stat(path), { code: 'ENOENT' })
}

async function verifyGeneratedFreshness() {
  const artifact = await stat(artifactPath)
  for (const sourcePath of sourcePaths) {
    const source = await stat(sourcePath)
    assert.ok(artifact.mtimeMs >= source.mtimeMs, `generated artifact is stale relative to ${sourcePath}`)
  }
}

async function exerciseContract(module) {
  assert.deepEqual(Object.keys(module).sort(), expectedExports)
  assert.deepEqual(module.getActiveCaseIds(), [])
  module.registerActiveCase('case-a', 'editor')
  module.registerActiveCase('case-b', 'reviewer')
  assert.deepEqual(module.getActiveCaseIds(), ['case-a', 'case-b'])
  module.unregisterActiveCase('case-a')
  module.clearActiveCaseId('case-b')
  assert.equal(module.getActiveCaseId(), null)
}

await verifyGeneratedFreshness()
if (process.argv.includes('--verify-generated')) {
  console.log('✓ active-case generated runtime is fresh')
  process.exit(0)
}

const temporaryDirectory = await mkdtemp(join(tmpdir(), 'factory-active-case-runtime-'))
const previousObservabilityFile = process.env.FACTORY_ACTIVE_CASE_FILE
try {
  const module = await importFresh(artifactPath)
  await exerciseContract(module)

  const observabilityFile = join(temporaryDirectory, 'active-case.txt')
  const observableModule = await importFresh(artifactPath, observabilityFile)
  observableModule.registerActiveCase('observed')
  assert.equal(await readFile(observabilityFile, 'utf8'), 'observed')
  observableModule.unregisterActiveCase('observed')
  await assertMissing(observabilityFile)

  const relocatedDirectory = join(temporaryDirectory, 'relocated')
  await mkdir(relocatedDirectory)
  const relocatedArtifact = join(relocatedDirectory, 'active-case-contract.mjs')
  await copyFile(artifactPath, relocatedArtifact)
  await exerciseContract(await importFresh(relocatedArtifact))

  const artifactSource = await readFile(artifactPath, 'utf8')
  assert.match(artifactSource, /GENERATED FILE.*DO NOT EDIT/)
  assert.doesNotMatch(artifactSource, /(?:from|import\s*\()\s*['"](?!node:)[^'"]+['"]/) 
} finally {
  if (previousObservabilityFile === undefined) delete process.env.FACTORY_ACTIVE_CASE_FILE
  else process.env.FACTORY_ACTIVE_CASE_FILE = previousObservabilityFile
  await rm(temporaryDirectory, { recursive: true, force: true })
}

console.log('✓ TypeScript active-case runtime contract')
