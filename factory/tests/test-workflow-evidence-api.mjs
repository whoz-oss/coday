import assert from 'node:assert/strict'
import { handleWorkflowEvidenceRequest } from '../dashboard/workflow-evidence-routes.mjs'

const NS='11111111-1111-4111-8111-111111111111',OTHER='22222222-2222-4222-8222-222222222222'
const execution={namespaceId:NS,runtimeId:'agentos-test',kind:'agentos',agentId:'ProductEngineer',caseId:'case-1',actorId:'user-1'}
const definition={workflowType:'bmad-story',version:'1.0.0',definitionHash:'hash-1',steps:[{id:'implement'}]}
const governed={governanceMode:'governed',revision:7,instance:{workflowType:'bmad-story',definitionVersion:'1.0.0',definitionHash:'hash-1',status:'running',steps:[{id:'implement',status:'running'}]},projection:{workflowId:'wf-1',status:'running',steps:[{id:'implement',status:'running'}]}}
function harness({state='existing',snapshot=governed,foundDefinition=definition}={}){const recorded=[],listed=[];const projectionStore={lookup:async(ns,id)=>({state,workflowId:id,...(state==='existing'?{snapshot}: {})})};const evidenceStore={record:async(ns,id,value,source)=>{recorded.push({ns,id,value,source});return{created:true,idempotent:false,evidence:{evidenceId:'factory-id',namespaceId:ns,...value,source,observedAt:'2026-01-01T00:00:00.000Z'}}},list:async(ns,id,filter)=>{listed.push({ns,id,filter});return[{evidenceId:'b',observedAt:'2026-01-02T00:00:00.000Z'},{evidenceId:'a',observedAt:'2026-01-01T00:00:00.000Z'}].sort((a,b)=>a.observedAt.localeCompare(b.observedAt)||a.evidenceId.localeCompare(b.evidenceId))}};return{projectionStore,evidenceStore,definitionRegistry:{get:async()=>foundDefinition},recorded,listed}}
async function request(h,method,path,body){let response;const url=new URL(path,'http://localhost');await handleWorkflowEvidenceRequest({method,path:url.pathname,url,readBody:async()=>body,send:(status,payload)=>{response={status,body:payload}},...h,log:{error(){}}});return response}
const agent={workflowId:'wf-1',stepId:'implement',kind:'agent-result',outcome:'pass',facts:{resultCode:'DONE'},idempotencyKey:'turn-1'}
const artifact={workflowId:'wf-1',stepId:'implement',kind:'artifact',artifactRef:'opaque://artifact',artifactHash:`sha256:${'a'.repeat(64)}`}
for(const evidence of [agent,artifact]){const h=harness();const before=structuredClone(governed);const response=await request(h,'POST','/api/factory/workflows/wf-1/evidence',{evidence,execution});assert.equal(response.status,201);assert.equal(response.body.data.evidence.evidenceId,'factory-id');assert.deepEqual(h.recorded[0].source,{runtimeId:'agentos-test',kind:'agentos',agentId:'ProductEngineer',caseId:'case-1',actorId:'user-1'});assert.deepEqual(governed,before)}
const oracleResult={workflowId:'wf-1',stepId:'implement',kind:'oracle-result',outcome:'pass',facts:{oracleId:'node-smoke',oracleVersion:'1.0.0',classification:'CLEAN'}}
const forbiddenOracleHarness=harness()
const forbiddenOracleResponse=await request(forbiddenOracleHarness,'POST','/api/factory/workflows/wf-1/evidence',{evidence:oracleResult,execution})
assert.equal(forbiddenOracleResponse.status,403)
assert.equal(forbiddenOracleResponse.body.error.code,'FACTORY_ONLY_EVIDENCE')
assert.equal(forbiddenOracleHarness.recorded.length,0)
const forbiddenHumanHarness=harness()
const forbiddenHumanResponse=await request(forbiddenHumanHarness,'POST','/api/factory/workflows/wf-1/evidence',{evidence:{workflowId:'wf-1',stepId:'implement',kind:'human-decision',outcome:'pass',facts:{interactionId:'gate-1',actionId:'approve'}},execution})
assert.equal(forbiddenHumanResponse.status,403);assert.equal(forbiddenHumanResponse.body.error.code,'FACTORY_ONLY_EVIDENCE');assert.equal(forbiddenHumanHarness.recorded.length,0)
for(const [state,status,code] of [['absent',404,'WORKFLOW_NOT_FOUND'],['removed',410,'WORKFLOW_REMOVED'],['purged',410,'WORKFLOW_PURGED']]){const response=await request(harness({state}),'POST','/api/factory/workflows/wf-1/evidence',{evidence:agent,execution});assert.equal(response.status,status);assert.equal(response.body.error.code,code)}
for(const legacy of [{projection:{schemaVersion:'1',workflowId:'wf-1'}},{projection:{schemaVersion:'2',workflowId:'wf-1'}}])assert.equal((await request(harness({snapshot:legacy}),'POST','/api/factory/workflows/wf-1/evidence',{evidence:agent,execution})).body.error.code,'DECLARATIVE_WORKFLOW')
assert.equal((await request(harness({foundDefinition:null}),'POST','/api/factory/workflows/wf-1/evidence',{evidence:agent,execution})).body.error.code,'WORKFLOW_DEFINITION_NOT_FOUND')
for(const bad of [{...definition,definitionHash:'other'},{...definition,version:'2.0.0'}])assert.equal((await request(harness({foundDefinition:bad}),'POST','/api/factory/workflows/wf-1/evidence',{evidence:agent,execution})).body.error.code,'WORKFLOW_DEFINITION_MISMATCH')
assert.equal((await request(harness(),'POST','/api/factory/workflows/wf-1/evidence',{evidence:{...agent,stepId:'unknown'},execution})).body.error.code,'UNKNOWN_STEP')
for(const field of ['namespaceId','source','evidenceId','observedAt','actorId']){const h=harness();const response=await request(h,'POST','/api/factory/workflows/wf-1/evidence',{evidence:{...agent,[field]:'model'},execution});assert.equal(response.body.error.code,'INVALID_EVIDENCE');assert.equal(h.recorded.length,0)}
assert.equal((await request(harness(),'POST','/api/factory/workflows/wf-1/evidence',{evidence:agent,execution:{...execution,narrative:'bad'}})).body.error.code,'INVALID_EXECUTION')
const get=harness();const listed=await request(get,'GET',`/api/factory/workflows/wf-1/evidence?namespaceId=${NS}&stepId=implement`);assert.deepEqual(listed.body.data.items.map(x=>x.evidenceId),['a','b']);assert.deepEqual(get.listed[0].filter,{stepId:'implement'});assert.equal((await request(harness(),'GET',`/api/factory/workflows/wf-1/evidence?namespaceId=${OTHER}`)).body.data.namespaceId,OTHER)
for(const method of ['PUT','PATCH','DELETE'])assert.equal((await request(harness(),method,'/api/factory/workflows/wf-1/evidence',{})).status,405)
// The evidence handler has no notifier and never calls projectionStore publish/start; the immutable fixture above proves revision/status/projection remain unchanged.
console.log('workflow evidence HTTP source tests: OK')
