import assert from 'node:assert/strict'
import { mkdtempSync,mkdirSync,writeFileSync,readFileSync,realpathSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { resolveForgeRoots } from '../lib/forge-roots.mjs'
import { createEpicRun,parseForgeLedger,projectForgeRun } from '../lib/forge-ledger.mjs'
import { computeG1EvidenceSetHash,G1_POLICY_VERSION,recordHumanDecision } from '../lib/forge-human-decision.mjs'
import { evaluateG2 } from '../lib/forge-g2.mjs'
import { executeStoryOracles } from '../lib/forge-story-oracles.mjs'
const root=mkdtempSync(join(tmpdir(),'oracle-contract-')),f=join(root,'f'),r=join(root,'r'),s=join(r,'forge','specs');mkdirSync(f);mkdirSync(s,{recursive:true});mkdirSync(join(r,'apps'));writeFileSync(join(r,'apps','x.ts'),'x');const roots=resolveForgeRoots({orchestratorRoot:f,runStoreRoot:join(f,'runs'),repoRoot:r,forgeRoot:join(r,'forge')});const spec=join(s,'s.md');writeFileSync(spec,'---\nschemaVersion: 1\nworkItem:\n  id: E\n  kind: Epic\nscope:\n  allow:\n    - apps/**\n  create:\n    - new/**\n  deny:\n    - deny/**\noracles:\n  - front.build\n  - front.tests\n---\n');const run=createEpicRun({roots,runId:'epic_oc',epic:{id:'E',kind:'Epic'},stories:[{id:'S',kind:'Story'}]});const story=run.storyRuns[0].runId;let ev=parseForgeLedger(run.filePath);await recordHumanDecision({roots,runId:run.runId,decision:{gate:'G1',attempt:1,policyVersion:G1_POLICY_VERSION,evidenceSetHash:computeG1EvidenceSetHash(ev,run.runId),outcome:'approved',reasonCode:'intent_confirmed'},identityPort:{actorId:async()=> 'h',authorize:async()=>({authorityId:'o'})}});evaluateG2({roots,runId:run.runId,specPath:spec});const h=parseForgeLedger(run.filePath).filter(e=>e.event==='g2_evaluated').at(-1).spec.sha256;const append=e=>writeFileSync(run.filePath,readFileSync(run.filePath,'utf8')+JSON.stringify(e)+'\n');const finish=(id,status='finished',outcome='finished',diff='valid')=>append({schemaVersion:1,event:'story_edit_finished',runId:run.runId,storyRunId:story,editId:id,status,outcome,diffValidation:{status:diff},filesModified:['apps/x.ts'],filesCreated:[]});const expectCode=(op,code)=>assert.rejects(op,e=>{assert.equal(e.code,code);return true});const call=(id,extra={})=>executeStoryOracles({roots,epicRunId:run.runId,storyRunId:story,editId:id,expectedSpecHash:h,commandResolver:(o)=>`cmd-${o.name}`,executor:()=>({exitCode:0,durationMs:1,stdout:'secret',stderr:'raw'}),...extra})
await expectCode(()=>executeStoryOracles({roots,epicRunId:run.runId,storyRunId:'missing',editId:'x',expectedSpecHash:h}),'STORY_ORACLE_STORY_NOT_FOUND');await expectCode(()=>call('none'),'STORY_ORACLE_EDIT_NOT_VALID');finish('badstatus','failed');await expectCode(()=>call('badstatus'),'STORY_ORACLE_EDIT_NOT_VALID');finish('runtime','finished','killed');await expectCode(()=>call('runtime'),'STORY_ORACLE_EDIT_NOT_VALID');finish('diff','finished','finished','invalid');await expectCode(()=>call('diff'),'STORY_ORACLE_EDIT_NOT_VALID')
finish('pass');let seen=[];let res=await call('pass',{executor:(cmd,opt)=>{seen.push({cmd,opt});return {exitCode:0,durationMs:2,stdout:'secret',stderr:'raw'}}});assert.equal(res.status,'passed');assert.deepEqual(seen.map(x=>x.cmd),['cmd-build','cmd-tests']);const canonicalRepoRoot=realpathSync(r);assert.ok(seen.every(x=>x.opt?.cwd===canonicalRepoRoot),`Expected executor cwd=${canonicalRepoRoot}; observed=${JSON.stringify(seen.map(x=>x.opt?.cwd))}`);const hashes=res.results.map(x=>x.commandHash);assert.equal(hashes[0],`sha256:${(await import('node:crypto')).createHash('sha256').update('cmd-build').digest('hex')}`);assert.notEqual(hashes[0],hashes[1]);assert.equal(readFileSync(run.filePath,'utf8').includes('secret'),false);assert.equal(readFileSync(run.filePath,'utf8').includes('raw'),false)
finish('fail');let runs=0;res=await call('fail',{executor:()=>{runs++;return {exitCode:3,durationMs:1}}});assert.equal(res.status,'failed');assert.equal(res.results[0].code,'ORACLE_FAIL');assert.equal(runs,1)
finish('timeout');res=await call('timeout',{executor:()=>({exitCode:-1,timedOut:true,durationMs:1})});assert.equal(res.status,'blocked');assert.equal(res.results[0].code,'ORACLE_TIMEOUT');finish('crash');res=await call('crash',{executor:()=>{throw new Error('boom')}});assert.equal(res.status,'blocked');assert.equal(res.results[0].code,'ORACLE_CRASH')
append({schemaVersion:1,event:'story_oracles_started',campaignId:'active',runId:run.runId,storyRunId:story,editId:'active-edit'});finish('active-edit');await expectCode(()=>call('active-edit'),'STORY_ORACLE_ALREADY_RUNNING');finish('retry');await call('retry',{attempt:1});res=await call('retry',{attempt:2});assert.equal(res.status,'passed');await expectCode(()=>call('retry',{attempt:1}),'STORY_ORACLE_ATTEMPT_COLLISION');await expectCode(()=>call('retry',{attempt:1.2}),'STORY_ORACLE_ATTEMPT_INVALID')
// G2 is the first-line catalog guard: an unknown spec oracle must block there.
const unknown=join(s,'unknown.md')
writeFileSync(unknown,readFileSync(spec,'utf8').replace('front.tests','unknown.oracle'))
const unknownRun=createEpicRun({roots,runId:'epic_unknown',epic:{id:'E',kind:'Epic'},stories:[{id:'S',kind:'Story'}]})
let ue=parseForgeLedger(unknownRun.filePath)
await recordHumanDecision({roots,runId:unknownRun.runId,decision:{gate:'G1',attempt:1,policyVersion:G1_POLICY_VERSION,evidenceSetHash:computeG1EvidenceSetHash(ue,unknownRun.runId),outcome:'approved',reasonCode:'intent_confirmed'},identityPort:{actorId:async()=> 'h',authorize:async()=>({authorityId:'o'})}})
const unknownG2=evaluateG2({roots,runId:unknownRun.runId,specPath:unknown})
assert.equal(unknownG2.event.status,'blocked')
assert.equal(unknownG2.event.code,'G2_ORACLE_UNKNOWN')
assert.equal(unknownG2.event.spec,null)
assert.equal(unknownG2.event.spec?.sha256,undefined)

// Defense in depth: a corrupt/legacy ledger can claim G2 passed for an
// otherwise valid spec whose current artifact has been altered afterwards.
// StoryOracle must independently reject the unknown catalog id.
const corruptSpec=join(s,'corrupt.md')
writeFileSync(corruptSpec,readFileSync(spec,'utf8').replace('front.tests','unknown.oracle'))
const corruptRun=createEpicRun({roots,runId:'epic_corrupt_catalog',epic:{id:'E',kind:'Epic'},stories:[{id:'S',kind:'Story'}]})
let corruptEvents=parseForgeLedger(corruptRun.filePath)
await recordHumanDecision({roots,runId:corruptRun.runId,decision:{gate:'G1',attempt:1,policyVersion:G1_POLICY_VERSION,evidenceSetHash:computeG1EvidenceSetHash(corruptEvents,corruptRun.runId),outcome:'approved',reasonCode:'intent_confirmed'},identityPort:{actorId:async()=> 'h',authorize:async()=>({authorityId:'o'})}})
const corruptHash=`sha256:${(await import('node:crypto')).createHash('sha256').update(readFileSync(corruptSpec)).digest('hex')}`
writeFileSync(corruptRun.filePath,readFileSync(corruptRun.filePath,'utf8')+JSON.stringify({schemaVersion:1,event:'g2_evaluated',runId:corruptRun.runId,status:'passed',spec:{path:corruptSpec,sha256:corruptHash,schemaVersion:1},policyVersion:'forge-g2-deterministic-v1'})+'\n'+JSON.stringify({schemaVersion:1,event:'story_edit_finished',runId:corruptRun.runId,storyRunId:corruptRun.storyRuns[0].runId,editId:'u',status:'finished',outcome:'finished',diffValidation:{status:'valid'},filesModified:['apps/x.ts'],filesCreated:[]})+'\n')
await expectCode(()=>executeStoryOracles({roots,epicRunId:corruptRun.runId,storyRunId:corruptRun.storyRuns[0].runId,editId:'u',expectedSpecHash:corruptHash,commandResolver:()=>'',executor:()=>({})}),'STORY_ORACLE_CATALOG_INVALID')
assert.ok(projectForgeRun(parseForgeLedger(run.filePath)).stories[0].oracleCampaigns.length>0)
console.log('story oracles contract: ok')
