import assert from 'node:assert/strict'
import { mkdtempSync,mkdirSync,writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { resolveFrontOraclePlan } from '../lib/forge-front-oracle-resolution.mjs'

const root=mkdtempSync(join(tmpdir(),'front-oracle-resolution-'))
const add=(name,targets)=>{const dir=join(root,'apps',name);mkdirSync(dir,{recursive:true});writeFileSync(join(dir,'project.json'),JSON.stringify({name,targets}))}
for(const name of ['aphrodite','admin','agentic-studio','copilot-chat'])add(name,{build:{}})
add('entity-list-base',{test:{}})
const sprintLib=join(root,'frontend','libs','shared','entity-list-base');mkdirSync(sprintLib,{recursive:true});writeFileSync(join(sprintLib,'project.json'),JSON.stringify({name:'sprint-entity-list-base',targets:{test:{}}}));writeFileSync(join(sprintLib,'source.ts'),'')
add('without-test',{})
const file=(owner,name='source.ts')=>{const path=join(root,'apps',owner,name);writeFileSync(path,'');return `apps/${owner}/${name}`}
const hostMap=JSON.stringify({'*':['aphrodite','admin','agentic-studio','copilot-chat']})
const build='pnpm nx run-many --target=build --configuration=development --skip-nx-cache'

// The default remains backward-compatible when no environment override is set.
delete process.env.FACTORY_FRONT_TEST_TARGET
let plan=resolveFrontOraclePlan({repoRoot:root,files:[file('entity-list-base')],hostMapRaw:hostMap,buildTemplate:build})
assert.equal(plan.tests.target,'frontend-test')
assert.deepEqual(plan.tests.ownersWithoutTestTarget,['entity-list-base'])
assert.equal(plan.tests.command,null)

// A tests-only campaign does not resolve build hosts and remains eligible for the no-target skip.
plan=resolveFrontOraclePlan({repoRoot:root,files:[file('without-test','tests-only.ts')],buildTemplate:build,requireBuild:false})
assert.equal(plan.build.command,null)
assert.equal(plan.tests.command,null)

// Sprint exposes `test`; the configured target is used for inspection and execution.
process.env.FACTORY_FRONT_TEST_TARGET='test'
plan=resolveFrontOraclePlan({repoRoot:root,files:[file('entity-list-base','test.ts')],hostMapRaw:hostMap,buildTemplate:build})
assert.equal(plan.tests.target,'test')
assert.deepEqual(plan.tests.ownersWithTestTarget,['entity-list-base'])
assert.match(plan.tests.command,/--target=test/)
assert.match(plan.tests.command,/--projects=entity-list-base/)

// Sprint libraries are resolved from the project.json encountered above the changed file.
plan=resolveFrontOraclePlan({repoRoot:root,files:['frontend/libs/shared/entity-list-base/source.ts'],hostMapRaw:hostMap,buildTemplate:build,testsTarget:'test'})
assert.deepEqual(plan.tests.ownersWithTestTarget,['sprint-entity-list-base'])
assert.match(plan.tests.command,/--projects=sprint-entity-list-base/)

// A duplicate Nx name at two project roots is ambiguous and fails closed.
const duplicate=join(root,'frontend','libs','shared','duplicate-entity-list-base');mkdirSync(duplicate,{recursive:true});writeFileSync(join(duplicate,'project.json'),JSON.stringify({name:'sprint-entity-list-base',targets:{test:{}}}));writeFileSync(join(duplicate,'source.ts'),'')
assert.throws(()=>resolveFrontOraclePlan({repoRoot:root,files:['frontend/libs/shared/entity-list-base/source.ts','frontend/libs/shared/duplicate-entity-list-base/source.ts'],hostMapRaw:hostMap,buildTemplate:build,testsTarget:'test'}),error=>error.code==='ORACLE_INFRASTRUCTURE')
assert.throws(()=>resolveFrontOraclePlan({repoRoot:root,files:['../outside.ts'],hostMapRaw:hostMap,buildTemplate:build}),error=>error.code==='ORACLE_INFRASTRUCTURE')

// Owners without the configured target are retained as facts but excluded from the command.
plan=resolveFrontOraclePlan({repoRoot:root,files:[file('entity-list-base','mixed.ts'),file('without-test')],hostMapRaw:hostMap,buildTemplate:build})
assert.deepEqual(plan.tests.ownersWithTestTarget,['entity-list-base'])
assert.deepEqual(plan.tests.ownersWithoutTestTarget,['without-test'])
assert.match(plan.tests.command,/--projects=entity-list-base/)
assert.doesNotMatch(plan.tests.command,/without-test/)

// Resolution failures remain fail-closed infrastructure errors.
assert.throws(()=>resolveFrontOraclePlan({repoRoot:root,files:['outside.ts'],hostMapRaw:hostMap,buildTemplate:build}),error=>error.code==='ORACLE_INFRASTRUCTURE')
process.env.FACTORY_FRONT_TEST_TARGET=''
assert.throws(()=>resolveFrontOraclePlan({repoRoot:root,files:[file('entity-list-base','invalid-map.ts')],hostMapRaw:'{}',buildTemplate:build}),error=>error.code==='ORACLE_INFRASTRUCTURE')
delete process.env.FACTORY_FRONT_TEST_TARGET
console.log('forge front oracle resolution: ok')
