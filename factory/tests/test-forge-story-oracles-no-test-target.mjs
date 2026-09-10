import assert from 'node:assert/strict'
import { mkdtempSync,mkdirSync,writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { resolveForgeRoots } from '../lib/forge-roots.mjs'
import { createEpicRun,parseForgeLedger } from '../lib/forge-ledger.mjs'
import { computeG1EvidenceSetHash,G1_POLICY_VERSION,recordHumanDecision } from '../lib/forge-human-decision.mjs'
import { evaluateG2 } from '../lib/forge-g2.mjs'
import { executeStoryOracles } from '../lib/forge-story-oracles.mjs'

const root=mkdtempSync(join(tmpdir(),'story-oracle-no-target-')),factory=join(root,'factory'),repo=join(root,'repo'),specs=join(repo,'forge','specs')
mkdirSync(factory);mkdirSync(specs,{recursive:true});mkdirSync(join(repo,'apps'),{recursive:true});writeFileSync(join(repo,'apps','x.ts'),'x')
const roots=resolveForgeRoots({orchestratorRoot:factory,runStoreRoot:join(factory,'runs'),repoRoot:repo,forgeRoot:join(repo,'forge')})
const specPath=join(specs,'s.md')
writeFileSync(specPath,'---\nschemaVersion: 1\nworkItem:\n  id: E\n  kind: Epic\nscope:\n  allow:\n    - apps/**\n  create:\n    - new/**\n  deny:\n    - deny/**\noracles:\n  - front.tests\n---\n')
const run=createEpicRun({roots,runId:'epic_no_target',epic:{id:'E',kind:'Epic'},stories:[{id:'S',kind:'Story'}]}),story=run.storyRuns[0].runId
await recordHumanDecision({roots,runId:run.runId,decision:{gate:'G1',attempt:1,policyVersion:G1_POLICY_VERSION,evidenceSetHash:computeG1EvidenceSetHash(parseForgeLedger(run.filePath),run.runId),outcome:'approved',reasonCode:'intent_confirmed'},identityPort:{actorId:async()=> 'h',authorize:async()=>({authorityId:'o'})}})
evaluateG2({roots,runId:run.runId,specPath})
const specHash=parseForgeLedger(run.filePath).filter(event=>event.event==='g2_evaluated').at(-1).spec.sha256
writeFileSync(run.filePath,`${(await import('node:fs')).readFileSync(run.filePath,'utf8')}${JSON.stringify({schemaVersion:1,event:'story_edit_finished',runId:run.runId,storyRunId:story,editId:'edit',status:'finished',outcome:'finished',diffValidation:{status:'valid'},filesModified:['apps/x.ts'],filesCreated:[]})}\n`)
let executions=0
const result=await executeStoryOracles({roots,epicRunId:run.runId,storyRunId:story,editId:'edit',expectedSpecHash:specHash,executor:()=>{executions++;return {exitCode:0}},frontResolver:()=>({owners:['owner'],build:{command:'build',buildHosts:[],target:'build'},tests:{command:null,owners:[],ownersWithTestTarget:[],ownersWithoutTestTarget:['owner'],target:'test'}})})
assert.equal(executions,0)
assert.equal(result.status,'passed')
assert.deepEqual(result.results,[{name:'front.tests',ownerProjects:[],ownersWithTestTarget:[],ownersWithoutTestTarget:['owner'],buildHosts:[],target:'test',configuration:null,status:'skipped',code:'ORACLE_NO_TEST_TARGET',exitCode:null,durationMs:0,commandHash:null}])
console.log('story oracle no test target: ok')
