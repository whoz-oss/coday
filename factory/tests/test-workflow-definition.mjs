import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { hashWorkflowDefinition, validateWorkflowDefinition } from '../lib/workflow-definition.mjs'
import { WorkflowDefinitionRegistry } from '../lib/workflow-definition-registry.mjs'

let failed = 0
function expect(name, condition) { console.log(`${condition ? '✓' : '✗'} ${name}`); if (!condition) failed++ }
const valid = { schemaVersion: '1', workflowType: 'demo', version: '1.0.0', title: 'Demo', steps: [
  { id: 'one', name: 'One', responsibility: { kind: 'agent', name: 'Agent' }, dependsOn: [] },
  { id: 'two', name: 'Two', responsibility: { kind: 'human', name: 'Owner' }, dependsOn: ['one'] },
] }
expect('valid definition', validateWorkflowDefinition(valid).ok)
expect('agent responsibility includes code editing work', validateWorkflowDefinition({ ...valid, steps: [{ ...valid.steps[0], name: 'Implementation', responsibility: { kind: 'agent', name: 'BmadBuilder' } }] }).ok)
expect('code responsibility is accepted for deterministic Factory execution', validateWorkflowDefinition({ ...valid, steps: [{ ...valid.steps[0], name: 'Build', responsibility: { kind: 'code', name: 'Factory' } }] }).ok)
expect('stable hash independent from object key order', hashWorkflowDefinition(valid) === hashWorkflowDefinition({ title: valid.title, version: valid.version, steps: valid.steps, workflowType: valid.workflowType, schemaVersion: valid.schemaVersion }))
expect('unknown dependency rejected', validateWorkflowDefinition({ ...valid, steps: [{ ...valid.steps[0], dependsOn: ['missing'] }] }).error.code === 'MISSING_DEPENDENCY')
expect('cycle rejected', validateWorkflowDefinition({ ...valid, steps: [{ ...valid.steps[0], dependsOn: ['two'] }, valid.steps[1]] }).error.code === 'DEPENDENCY_CYCLE')
expect('invalid responsibility rejected', validateWorkflowDefinition({ ...valid, steps: [{ ...valid.steps[0], responsibility: { kind: 'machine', name: 'Agent' } }] }).error.code === 'INVALID_RESPONSIBILITY')
expect('duplicate rejected', validateWorkflowDefinition({ ...valid, steps: [valid.steps[0], valid.steps[0]] }).error.code === 'DUPLICATE_STEP_ID')

const root = await mkdtemp(join(tmpdir(), 'workflow-definitions-'))
try {
  await mkdir(join(root, 'demo'), { recursive: true }); await writeFile(join(root, 'demo', '1.0.0.json'), JSON.stringify(valid))
  const registry = new WorkflowDefinitionRegistry(root); await registry.initialize()
  expect('registry list and detail', (await registry.list()).length === 1 && (await registry.get('demo', '1.0.0')).definitionHash === hashWorkflowDefinition(valid))
  await mkdir(join(root, 'other'), { recursive: true }); await writeFile(join(root, 'other', '1.0.0.json'), JSON.stringify(valid))
  let collision = false; try { await new WorkflowDefinitionRegistry(root).initialize() } catch (error) { collision = error.code === 'DEFINITION_PATH_MISMATCH' }
  expect('path/content collision rejected', collision)
} finally { await rm(root, { recursive: true, force: true }) }
process.exit(failed ? 1 : 0)
