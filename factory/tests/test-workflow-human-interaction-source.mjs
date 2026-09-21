// Governed human checkpoint source scenarios. Intentionally not executed during implementation.
import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { join } from 'node:path';import { tmpdir } from 'node:os'
import { WorkflowHumanInteractionStore } from '../lib/workflow-human-interaction-store.mjs'
import { applyHumanCheckpointOpen, evaluateHumanCheckpointOpen, evaluateHumanResolutionTransition } from '../lib/workflow-transition-policy.mjs'
const root=await mkdtemp(join(tmpdir(),'human-interaction-')),ns='11111111-1111-4111-8111-111111111111',execution={namespaceId:ns,kind:'coday-express',runtimeId:'runtime',agentId:'ProductEngineer',threadId:'thread-1'}
const definition={workflowType:'delivery',version:'1',definitionHash:'hash',steps:[{id:'build',dependsOn:[],responsibility:{kind:'agent',name:'ProductEngineer'}},{id:'approve',dependsOn:['build'],responsibility:{kind:'human'}}]}
const snapshot={revision:2,governanceMode:'governed',definitionVersion:'1',definitionHash:'hash',controllerExecution:execution,instance:{governanceMode:'governed',workflowType:'delivery',definitionVersion:'1',definitionHash:'hash',revision:2,controllerExecution:execution,steps:[{id:'build',status:'completed'},{id:'approve',status:'ready'}]},projection:{status:'ready',steps:[{id:'build',status:'completed'},{id:'approve',status:'ready'}]}}
const input={interactionId:'gate-1',workflowId:'wf',stepId:'approve',expectedRevision:2,kind:'approval',prompt:'Approve?',idempotencyKey:'open-1',actions:[{id:'approve',label:'Approve',requestedStatus:'completed'},{id:'reject',label:'Reject',requestedStatus:'failed'}]}
try{
 const request={workflowId:'wf',stepId:'approve',expectedRevision:2,requestedStatus:'waiting_human',evidenceIds:[]}
 assert.deepEqual(evaluateHumanCheckpointOpen({request,snapshot,definition,execution}),{allowed:true})
 for(const [changed,code] of [[{...request,expectedRevision:1},'REVISION_CONFLICT'],[{...request,stepId:'build'},'ACTOR_NOT_AUTHORIZED']])assert.equal(evaluateHumanCheckpointOpen({request:changed,snapshot,definition,execution}).code,code)
 assert.equal(evaluateHumanCheckpointOpen({request,snapshot:{...snapshot,instance:{...snapshot.instance,steps:[{id:'build',status:'running'},{id:'approve',status:'ready'}]}},definition,execution}).code,'DEPENDENCIES_NOT_SATISFIED')
 assert.equal(evaluateHumanCheckpointOpen({request,snapshot,definition,execution:{...execution,threadId:'other'}}).code,'ACTOR_NOT_AUTHORIZED')
 const store=new WorkflowHumanInteractionStore(root),opened=await store.open(ns,'storage',input,async()=>({ok:true,changed:true,requestId:'transition-1',snapshot:{revision:3,projection:{}}}))
 assert.equal(opened.interaction.status,'open');assert.equal(opened.interaction.revision,3);assert.equal(opened.interaction.evidenceId,undefined)
 assert.equal((await new WorkflowHumanInteractionStore(root).list(ns,'storage'))[0].revision,3,'authoritative post-open revision survives replay')
 const replay=await store.open(ns,'storage',input,async()=>{throw new Error('must not transition twice')});assert.equal(replay.transition.idempotent,true);assert.equal(replay.interaction.revision,3)
 await assert.rejects(()=>store.open(ns,'storage',{...input,prompt:'Changed'},async()=>({ok:true})),error=>error.code==='IDEMPOTENCY_KEY_COLLISION')
 await assert.rejects(()=>store.open(ns,'storage',{...input,interactionId:'gate-2',idempotencyKey:'open-2'},async()=>({ok:true})),error=>error.code==='INTERACTION_ALREADY_OPEN')
 const human={namespaceId:ns,kind:'factory-human',runtimeId:'factory-dashboard',actorId:'human'},evidencePass={evidenceId:'e1',namespaceId:ns,workflowId:'wf',stepId:'approve',kind:'human-decision',outcome:'pass',source:human},evidenceFail={...evidencePass,evidenceId:'e2',outcome:'fail'}
 const waitingSnapshot=applyHumanCheckpointOpen(snapshot,definition,request)
 assert.deepEqual(evaluateHumanResolutionTransition({request:{...request,expectedRevision:3,requestedStatus:'completed',evidenceIds:['e1']},snapshot:waitingSnapshot,definition,evidence:[evidencePass],execution:human}),{allowed:true})
 assert.deepEqual(evaluateHumanResolutionTransition({request:{...request,expectedRevision:3,requestedStatus:'failed',evidenceIds:['e2']},snapshot:waitingSnapshot,definition,evidence:[evidenceFail],execution:human}),{allowed:true})
 const recovery=new WorkflowHumanInteractionStore(root,{fault:async seam=>{if(seam==='after-transition')throw new Error('crash')}})
 await assert.rejects(()=>recovery.open(ns,'storage-2',{...input,interactionId:'gate-recovery'},async()=>({ok:true,changed:true,requestId:'transition-r',snapshot:{revision:3}})))
 assert.equal((await new WorkflowHumanInteractionStore(root).list(ns,'storage-2'))[0].status,'opening','post-transition crash remains explicit and cannot look closed')
 const failed=await store.open(ns,'storage-3',{...input,interactionId:'gate-failure'},async()=>({ok:true,changed:true,requestId:'transition-2',snapshot:{revision:3}}))
 await assert.rejects(()=>store.transact(ns,'storage-3',failed.interaction.interactionId,async()=>{throw new Error('transition failed')}))
 assert.equal((await store.list(ns,'storage-3',{openOnly:true})).length,1)
}finally{await rm(root,{recursive:true,force:true})}
console.log('workflow human interaction source scenarios: OK')
