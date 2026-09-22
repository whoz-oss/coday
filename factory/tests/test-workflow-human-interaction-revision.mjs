// Focused governed human-decision revision scenarios. Intentionally not executed during implementation.
import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { handleWorkflowHumanInteractionRequest } from '../dashboard/workflow-human-interaction-routes.mjs'
import { WorkflowHumanInteractionStore } from '../lib/workflow-human-interaction-store.mjs'
import { workflowProjectionStorageId } from '../lib/workflow-projection-store.mjs'

const namespaceId='11111111-1111-4111-8111-111111111111',workflowId='WZ-27053-v2',definition={workflowType:'delivery',version:'1',definitionHash:'hash'}
const interactionInput={interactionId:'intent-checkpoint-1',workflowId,stepId:'intent-checkpoint',expectedRevision:7,kind:'approval',prompt:'Approve intent?',idempotencyKey:'open-intent',actions:[{id:'approve',label:'Approve',requestedStatus:'completed'},{id:'reject',label:'Reject',requestedStatus:'failed'}]}
const waitingSnapshot=revision=>({revision,governanceMode:'governed',instance:{governanceMode:'governed',workflowType:'delivery',definitionVersion:'1',definitionHash:'hash',revision,steps:[{id:'intent-checkpoint',status:'waiting_human'}]},projection:{status:'waiting_human',steps:[{id:'intent-checkpoint',status:'waiting_human'}]}})
const root=await mkdtemp(join(tmpdir(),'human-revision-'))
try{
 const interactionStore=new WorkflowHumanInteractionStore(root)
 const storageId=workflowProjectionStorageId(namespaceId,workflowId)
 await interactionStore.open(namespaceId,storageId,interactionInput,async()=>({ok:true,changed:true,requestId:'open-transition',snapshot:waitingSnapshot(8)}))
 const interaction=(await interactionStore.list(namespaceId,storageId,{openOnly:true}))[0]
 assert.equal(interaction.expectedRevision,7)
 assert.equal(interaction.revision,8,'ready revision R must be replaced by authoritative waiting_human revision R+1')

 const request=async({actionId='approve',expectedRevision=8,snapshot=waitingSnapshot(8),transitionOk=true}={})=>{
  let response,transitionRequest
  const handled=await handleWorkflowHumanInteractionRequest({
   method:'POST',path:`/api/factory/workflows/${workflowId}/interactions/${interaction.interactionId}/reply`,url:new URL(`http://localhost?namespaceId=${namespaceId}`),readBody:async()=>({expectedRevision,actionId}),send:(status,body)=>response={status,body},projectionStore:{lookup:async()=>({state:'existing',snapshot}),resolveHumanCheckpoint:async(_ns,request)=>{transitionRequest=request;return transitionOk?{ok:true,changed:true,requestId:`resolve-${actionId}`,snapshot:{...snapshot,revision:snapshot.revision+1,instance:{...snapshot.instance,revision:snapshot.revision+1},projection:{...snapshot.projection,status:actionId==='approve'?'completed':'failed'}}}:{ok:false,error:{code:'REVISION_CONFLICT'}}}},interactionStore,evidenceStore:{record:async(_ns,_storage,input,source)=>({evidence:{evidenceId:`evidence-${actionId}`,namespaceId,workflowId,stepId:input.stepId,kind:input.kind,outcome:input.outcome,source}}),list:async()=>[]},definitionRegistry:{get:async()=>definition},identity:{actorId:async()=> 'reviewer'},notifier:{publish(){}}})
  assert.equal(handled,true)
  return{response,transitionRequest}
 }

 const approved=await request()
 assert.equal(approved.response.status,200)
 assert.equal(approved.transitionRequest.expectedRevision,8,'reply transition must use persisted post-open revision')
 assert.equal(approved.transitionRequest.requestedStatus,'completed')
 await assert.rejects(()=>interactionStore.transact(namespaceId,storageId,interaction.interactionId,async()=>({transition:{ok:true}})),error=>error.code==='INTERACTION_CLOSED','decision remains single-use after replay')

 const rejectStore=new WorkflowHumanInteractionStore(root)
 await rejectStore.open(namespaceId,'reject-storage',{...interactionInput,interactionId:'intent-checkpoint-2',idempotencyKey:'open-reject'},async()=>({ok:true,changed:true,requestId:'open-reject-transition',snapshot:waitingSnapshot(8)}))
 const originalTransact=interactionStore.transact.bind(interactionStore)
 interactionStore.transact=(ns,id,interactionId,action)=>rejectStore.transact(ns,'reject-storage','intent-checkpoint-2',action)
 const rejected=await request({actionId:'reject'})
 assert.equal(rejected.response.status,200)
 assert.equal(rejected.transitionRequest.requestedStatus,'failed')
 interactionStore.transact=originalTransact

 const staleStore=new WorkflowHumanInteractionStore(root)
 await staleStore.open(namespaceId,'stale-storage',{...interactionInput,interactionId:'intent-checkpoint-3',idempotencyKey:'open-stale'},async()=>({ok:true,changed:true,requestId:'open-stale-transition',snapshot:waitingSnapshot(8)}))
 interactionStore.transact=(ns,id,interactionId,action)=>staleStore.transact(ns,'stale-storage','intent-checkpoint-3',action)
 const stale=await request({expectedRevision:7})
 assert.equal(stale.response.status,409);assert.equal(stale.response.body.error.code,'REVISION_CONFLICT')
 const intervening=await request({snapshot:waitingSnapshot(9)})
 assert.equal(intervening.response.status,409);assert.equal(intervening.response.body.error.code,'REVISION_CONFLICT')
 assert.equal((await staleStore.list(namespaceId,'stale-storage',{openOnly:true})).length,1,'failed reconciliation must leave interaction open')
 const noLongerWaiting=await request({snapshot:{...waitingSnapshot(8),instance:{...waitingSnapshot(8).instance,steps:[{id:'intent-checkpoint',status:'running'}]}}})
 assert.equal(noLongerWaiting.response.status,409);assert.equal(noLongerWaiting.response.body.error.code,'INTERACTION_STALE')
}finally{await rm(root,{recursive:true,force:true})}
console.log('workflow human interaction revision source scenarios: OK')
