import assert from 'node:assert/strict'
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { hashOracleDefinition, OracleDefinitionRegistry, validateOracleDefinition } from '../lib/oracle-definition.mjs'

const valid = {
  schemaVersion: '1', id: 'node-smoke', version: '1.0.0', domain: 'factory',
  argv: ['node', 'fixture.mjs'], cwd: 'repo-root', timeoutMs: 1000,
  success: { rule: 'exit-code', requireWork: true },
  applicable: { workflowTypes: ['oracle-smoke'], stepIds: ['verify-code'] },
}
const definition = validateOracleDefinition(valid)
assert.ok(Object.isFrozen(definition))
assert.ok(Object.isFrozen(definition.argv))
assert.ok(Object.isFrozen(definition.applicable.stepIds))
const reordered = {
  version: valid.version, id: valid.id, schemaVersion: valid.schemaVersion,
  applicable: { stepIds: valid.applicable.stepIds, workflowTypes: valid.applicable.workflowTypes },
  success: { requireWork: true, rule: 'exit-code' }, timeoutMs: valid.timeoutMs,
  cwd: valid.cwd, argv: valid.argv, domain: valid.domain,
}
assert.equal(hashOracleDefinition(valid), hashOracleDefinition(reordered))

const invalidDefinitions = [
  { label: 'invalid id', value: { ...valid, id: '../oracle' } },
  { label: 'invalid version', value: { ...valid, version: 'latest' } },
  { label: 'unknown field', value: { ...valid, environment: { SECRET: 'model' } } },
  { label: 'empty argv', value: { ...valid, argv: [] } },
  { label: 'too many argv entries', value: { ...valid, argv: Array(33).fill('x') } },
  { label: 'oversized argv entry', value: { ...valid, argv: ['x'.repeat(513)] } },
  { label: 'absolute cwd', value: { ...valid, cwd: '/tmp/model' } },
  { label: 'traversing cwd', value: { ...valid, cwd: '../checkout' } },
  { label: 'zero timeout', value: { ...valid, timeoutMs: 0 } },
  { label: 'excessive timeout', value: { ...valid, timeoutMs: 3_600_001 } },
  { label: 'unknown success rule', value: { ...valid, success: { rule: 'stdout-regex', requireWork: true } } },
  { label: 'invalid workflow applicability', value: { ...valid, applicable: { workflowTypes: ['../wf'], stepIds: ['verify-code'] } } },
  { label: 'invalid step applicability', value: { ...valid, applicable: { workflowTypes: ['oracle-smoke'], stepIds: [] } } },
]
for (const scenario of invalidDefinitions) {
  assert.throws(() => validateOracleDefinition(scenario.value), scenario.label)
}

const root = await mkdtemp(join(tmpdir(), 'oracle-registry-'))
try {
  await writeFile(join(root, 'node-smoke@1.0.0.json'), JSON.stringify(valid))
  const registry = await new OracleDefinitionRegistry(root).initialize()
  assert.equal(registry.get('node-smoke').id, 'node-smoke')
  assert.equal(registry.get('unknown'), null)

  await writeFile(join(root, 'wrong-name.json'), JSON.stringify({ ...valid, id: 'other' }))
  await assert.rejects(() => new OracleDefinitionRegistry(root).initialize(), /ORACLE_PATH_IDENTITY_MISMATCH/)
  await rm(join(root, 'wrong-name.json'))

  await writeFile(join(root, 'node-smoke@2.0.0.json'), JSON.stringify({ ...valid, version: '2.0.0' }))
  await assert.rejects(() => new OracleDefinitionRegistry(root).initialize(), /DUPLICATE_ORACLE_ID/)
} finally {
  await rm(root, { recursive: true, force: true })
}
console.log('oracle definition registry source tests: OK')
