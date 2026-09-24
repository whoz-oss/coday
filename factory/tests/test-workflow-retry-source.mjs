// Governed retry source scenarios. Intentionally not executed during implementation.
import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createFactoryFrontendRunner } from '../lib/factory-frontend-composition.mjs'
import { AgentStepResultStore } from '../lib/agent-step-result-store.mjs'
import { WorkflowHumanInteractionStore } from '../lib/workflow-human-interaction-store.mjs'

const namespaceId='11111111-1111-4111-8111-111111111111',workflowId='factory-demo-frontend-rehearsal-004',stepId='product-specification'
const definition={workflowType:'bmad-story-frontend',version:'1',definitionHash:'hash',trustedExecution:{allowedPaths:['factory']},steps:[{id:stepId,responsibility:{kind:'agent',name:'ProductEngineer'},dependsOn:[]}]}
const snapshot={revision:7,definitionHash:'hash',governanceMode:'governed',instance:{revision:7,governanceMode:'governed',workflowType:definition.workflowType,definitionVersion:'1',definitionHash:'hash',steps:[{id:stepId,status:'blocked'}]}}
const negative={evidenceId:'old-negative',namespaceId,workflowId,stepId,kind:'agent-result',outcome:'indeterminate',facts:{resultCode:'RESULT_NOT_JSON'},source:{kind:'agentos',agentId:'ProductEngineer'}}
let attempts=1,openCalls=0
const projectionStore={read:async()=>snapshot}
const evidenceStore={list:async()=>[negative]}
const humanInteractionStore={open:async(_ns,_storage,input,transition)=>{openCalls++;assert.equal(input.prompt,'Retry product-specification after RESULT_NOT_JSON?');assert.equal(input.interactionType,'retry');assert.equal(input.actions[0].requestedStatus,'ready');assert.equal(attempts,1);const result=await transition();return{interaction:{...input,interactionId:'retry-1',status:'open',revision:7},transition:result}}}
const runner=createFactoryFrontendRunner({dataRoot:'/tmp/unused',workflowRoot:'/tmp/unused',projectionStore,evidenceStore,humanInteractionStore,definitionRegistry:{get:async()=>definition},oracleRegistry:{},workUnitEnvironmentStore:{},resultStore:new AgentStepResultStore('/tmp/unused')})
const opened=await runner.openRetry({namespaceId,workflowId,stepId,expectedRevision:7,reasonCode:'RESULT_NOT_JSON'})
assert.equal(opened.status,'WAITING_HUMAN');assert.equal(attempts,1,'opening creates no attempt');assert.equal(openCalls,1)
assert.equal((await runner.openRetry({namespaceId,workflowId,stepId,expectedRevision:6,reasonCode:'RESULT_NOT_JSON'})).code,'REVISION_CONFLICT')
assert.equal((await runner.openRetry({namespaceId,workflowId,stepId,expectedRevision:7,reasonCode:'OTHER_REASON'})).code,'RETRY_REASON_MISMATCH')
projectionStore.read=async()=>({...snapshot,instance:{...snapshot.instance,steps:[{id:stepId,status:'ready'}]}})
assert.equal((await runner.openRetry({namespaceId,workflowId,stepId,expectedRevision:7,reasonCode:'RESULT_NOT_JSON'})).code,'ILLEGAL_TRANSITION')
projectionStore.read=async()=>snapshot;definition.steps[0].responsibility={kind:'code'}
assert.equal((await runner.openRetry({namespaceId,workflowId,stepId,expectedRevision:7,reasonCode:'RESULT_NOT_JSON'})).code,'ACTOR_NOT_AUTHORIZED')
assert.deepEqual(await evidenceStore.list(),[negative],'old evidence remains append-only')

const root=await mkdtemp(join(tmpdir(),'factory-retry-interaction-'))
try{
 const store=new WorkflowHumanInteractionStore(root)
 const retryInput={workflowId,stepId,expectedRevision:7,kind:'approval',interactionType:'retry',reasonCode:'RESULT_NOT_JSON',prompt:'Retry product-specification after RESULT_NOT_JSON?',idempotencyKey:'retry-replay',actions:[{id:'approve',label:'Approve',requestedStatus:'ready'},{id:'reject',label:'Reject',requestedStatus:'blocked'}]}
 const first=await store.open(namespaceId,'retry-storage',retryInput,async()=>({ok:true,changed:false,idempotent:false,requestId:'retry-open',snapshot:{revision:7}}))
 assert.equal(first.interaction.revision,7)
 const replay=await new WorkflowHumanInteractionStore(root).open(namespaceId,'retry-storage',retryInput,async()=>{throw new Error('retry replay must not reopen')})
 assert.equal(replay.interaction.status,'open')
 assert.equal(replay.interaction.revision,7,'retry interaction remains at the blocked workflow revision')
 assert.equal(replay.transition.idempotent,true)
}finally{await rm(root,{recursive:true,force:true})}
console.log('workflow retry source scenarios: OK')
