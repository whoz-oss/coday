import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { join, resolve } from 'node:path'
import { tmpdir } from 'node:os'
import { handleWorkflowOracleRequest } from '../dashboard/workflow-oracle-routes.mjs'

const NS='11111111-1111-4111-8111-111111111111'
const oracle={schemaVersion:'1',id:'node-smoke',version:'1.0.0',domain:'factory',argv:[process.execPath,'factory/tests/fixtures/oracle-process/pass.mjs'],cwd:'repo-root',timeoutMs:1000,success:{rule:'exit-code',requireWork:true},applicable:{workflowTypes:['oracle-smoke'],stepIds:['verify-code']}}
const definition={workflowType:'oracle-smoke',version:'1.0.0',definitionHash:'workflow-hash',steps:[{id:'verify-code',dependsOn:[],responsibility:{kind:'code',name:'node-smoke'}}]}
const governed={governanceMode:'governed',revision:4,definitionHash:'workflow-hash',instance:{governanceMode:'governed',workflowType:'oracle-smoke',definitionVersion:'1.0.0',definitionHash:'workflow-hash',revision:4,status:'running',steps:[{id:'verify-code',status:'running'}]},projection:{workflowId:'wf-1',status:'running',steps:[{id:'verify-code',status:'running'}]}}
function harness(overrides={}){
 const records=[],notices=[]
 return {
  projectionStore:{lookup:async()=>({state:overrides.state??'existing',snapshot:overrides.snapshot??governed})},
  evidenceStore:{record:async(_ns,_storage,input,source)=>{records.push({input,source});return overrides.recordResult??{created:true,idempotent:false,evidence:{evidenceId:'evidence-1',...input,source}}}},
  definitionRegistry:{get:async()=>Object.prototype.hasOwnProperty.call(overrides,'definition')?overrides.definition:definition},
  oracleRegistry:Object.prototype.hasOwnProperty.call(overrides,'oracleRegistry')?overrides.oracleRegistry:{get:id=>id==='node-smoke'?oracle:null},
  repoRoot:overrides.repoRoot,
  records,notices,
 }
}
async function request(h,{method='POST',path='/api/factory/workflows/wf-1/steps/verify-code/oracles/node-smoke/runs',body={namespaceId:NS,idempotencyKey:'run-1'}}={}){let response;await handleWorkflowOracleRequest({method,path,readBody:async()=>body,send:(status,payload)=>response={status,body:payload},...h,log:{error(){}}});return response}
const temporaryRoot=await mkdtemp(join(tmpdir(),'oracle-api-'))
const trustedRepoRoot=resolve('.')
try{
 let h=harness({repoRoot:trustedRepoRoot})
 const before=structuredClone(governed)
 let response=await request(h)
 const recorded=h.records[0].input

 assert.equal(response.status,201)
 assert.equal(response.body.data.evidenceId,'evidence-1')
 assert.equal(recorded.kind,'oracle-result')
 assert.equal(recorded.facts.exitCode,0,'the trusted fixture command must exit successfully')
 assert.equal(recorded.facts.classification,'CLEAN','the nominal run must classify as CLEAN')
 assert.equal(recorded.facts.executed,1,'the PASS fixture must emit one recognized work marker')
 assert.equal(recorded.outcome,'pass')
 assert.equal(h.records[0].source.kind,'factory-oracle')
 assert.equal(JSON.stringify(h.records[0]).includes('> Task'),false)
 assert.deepEqual(governed,before)
 assert.equal(h.notices.length,0)

 h=harness({repoRoot:trustedRepoRoot,recordResult:{created:false,idempotent:true,evidence:{evidenceId:'same',outcome:'pass'}}})
 response=await request(h)
 assert.equal(response.status,200)
 assert.equal(response.body.data.idempotent,true)
 assert.equal(response.body.data.outcome,'pass','the replay reruns the same nominal PASS oracle')
 for(const [state,status,code] of [['absent',404,'WORKFLOW_NOT_FOUND'],['removed',410,'WORKFLOW_REMOVED'],['purged',410,'WORKFLOW_PURGED']]){response=await request(harness({repoRoot:trustedRepoRoot,state}));assert.equal(response.status,status);assert.equal(response.body.error.code,code)}
 response=await request(harness({repoRoot:trustedRepoRoot,snapshot:{governanceMode:'declarative'}}));assert.equal(response.body.error.code,'WORKFLOW_NOT_GOVERNED')
 response=await request(harness({repoRoot:trustedRepoRoot,definition:null}));assert.equal(response.body.error.code,'WORKFLOW_DEFINITION_MISMATCH')
 response=await request(harness({repoRoot:trustedRepoRoot,definition:{...definition,definitionHash:'other'}}));assert.equal(response.body.error.code,'WORKFLOW_DEFINITION_MISMATCH')
 response=await request(harness({repoRoot:trustedRepoRoot,definition:{...definition,steps:[]}}));assert.equal(response.body.error.code,'STEP_NOT_FOUND')
 response=await request(harness({repoRoot:trustedRepoRoot,definition:{...definition,steps:[{...definition.steps[0],responsibility:{kind:'agent',name:'node-smoke'}}]}}));assert.equal(response.body.error.code,'STEP_NOT_CODE')
 response=await request(harness({repoRoot:trustedRepoRoot,oracleRegistry:{get:()=>null}}));assert.equal(response.body.error.code,'ORACLE_NOT_FOUND')
 const other={...oracle,applicable:{workflowTypes:['other'],stepIds:['verify-code']}}
 response=await request(harness({repoRoot:trustedRepoRoot,oracleRegistry:{get:()=>other}}));assert.equal(response.body.error.code,'ORACLE_NOT_APPLICABLE')
 response=await request(harness({repoRoot:trustedRepoRoot,definition:{...definition,steps:[{...definition.steps[0],responsibility:{kind:'code',name:'other'}}]}}));assert.equal(response.body.error.code,'ORACLE_NOT_APPLICABLE')
 for(const body of [{namespaceId:NS,command:'bad'},{namespaceId:NS,argv:['bad']},{namespaceId:NS,cwd:'/'},{namespaceId:NS,root:'/'},{namespaceId:NS,env:{SECRET:'bad'}},{idempotencyKey:'missing-ns'}]){h=harness({repoRoot:trustedRepoRoot});response=await request(h,{body});assert.equal(response.status,400);assert.equal(h.records.length,0)}
 response=await request(harness({repoRoot:undefined}));assert.equal(response.status,503);assert.equal(response.body.error.code,'INVALID_ORACLE_ROOT')
 response=await request(harness({repoRoot:'relative'}));assert.equal(response.body.error.code,'INVALID_ORACLE_ROOT')
 response=await request(harness({repoRoot:join(temporaryRoot,'missing')}));assert.equal(response.body.error.code,'INVALID_ORACLE_ROOT')
 response=await request(harness({repoRoot:trustedRepoRoot,oracleRegistry:null}));assert.equal(response.body.error.code,'ORACLE_REGISTRY_UNAVAILABLE')
 assert.equal((await request(harness({repoRoot:trustedRepoRoot}),{method:'GET'})).status,405)
}finally{await rm(temporaryRoot,{recursive:true,force:true})}
// Store-level collision semantics are covered by workflow evidence store tests. This
// route prefixes idempotency with oracle hash + normalized root, preventing reuse
// across a changed definition/root while documenting that checkout HEAD is unbound.
console.log('workflow oracle HTTP source tests: OK')
