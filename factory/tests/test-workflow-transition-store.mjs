import assert from 'node:assert/strict'
import { mkdtemp,readFile,rm } from 'node:fs/promises'
import { join } from 'node:path';import { tmpdir } from 'node:os'
import { WorkflowProjectionStore,workflowProjectionStorageId } from '../lib/workflow-projection-store.mjs'
import { WorkflowEvidenceStore } from '../lib/workflow-evidence-store.mjs'
import { validateWorkflowTransitionRequest } from '../lib/workflow-transition-policy.mjs'
const ns='11111111-1111-4111-8111-111111111111', workflowId='wf-1'
const definition={schemaVersion:'1',workflowType:'delivery',version:'1.0.0',title:'Delivery',definitionHash:'hash',steps:[{id:'build',name:'Build',dependsOn:[],responsibility:{kind:'agent',name:'Builder'}},{id:'review',name:'Review',dependsOn:['build'],responsibility:{kind:'agent',name:'Reviewer'}}]}
const execution={runtimeId:'agentos-primary',kind:'agentos',agentId:'Builder',caseId:'case-1',actorId:'actor-1'}
const root=await mkdtemp(join(tmpdir(),'factory-transition-store-'))
try{
 const store=new WorkflowProjectionStore(root);await store.initialize();await store.start(ns,{workflowId,workflowType:'delivery',title:'Delivery'},definition,execution)
 const evidenceStore=new WorkflowEvidenceStore(root), storageId=workflowProjectionStorageId(ns,workflowId)
 const recorded=await evidenceStore.record(ns,storageId,{workflowId,stepId:'build',kind:'agent-result',outcome:'pass',facts:{resultCode:'DONE'},idempotencyKey:'result-1'},execution)
 const evidenceBefore=await readFile(evidenceStore.path(ns,storageId),'utf8')
 const runningRequest=validateWorkflowTransitionRequest({
  workflowId, stepId:'build', expectedRevision:1, requestedStatus:'running', evidenceIds:[], idempotencyKey:'start-build'
 },workflowId).value
 const running=await store.transition(ns,runningRequest,definition,await evidenceStore.list(ns,storageId),execution)
 assert.equal(running.ok,true,'the initially ready step must first enter running')
 assert.equal(running.snapshot.revision,2)
 const transition=validateWorkflowTransitionRequest({
  workflowId, stepId:'build', expectedRevision:2, requestedStatus:'completed',
  evidenceIds:[recorded.evidence.evidenceId], idempotencyKey:'transition-1'
 },workflowId).value
 const accepted=await store.transition(ns,transition,definition,await evidenceStore.list(ns,storageId),execution)
 assert.equal(accepted.ok,true,'running to completed must be accepted with matching PASS evidence')
 assert.equal(accepted.changed,true)
 assert.equal(accepted.idempotent,false)
 assert.equal(accepted.snapshot.revision,3)
 assert.equal(accepted.snapshot.instance.revision,3)
 assert.equal(accepted.snapshot.instance.steps.find(s=>s.id==='build').status,'completed')
 assert.equal(accepted.snapshot.projection.steps.find(s=>s.id==='review').status,'ready')
 assert.equal(accepted.snapshot.projection.status,'ready')
 assert.equal(await readFile(evidenceStore.path(ns,storageId),'utf8'),evidenceBefore,'transition must not mutate evidence journal')
 const facts=(await readFile(store.paths(ns,workflowId).events,'utf8')).trim().split('\n').map(JSON.parse)
 assert.deepEqual(facts.slice(-2).map(f=>f.kind),['transition_requested','transition_accepted']);assert.ok(!facts.at(-2).revision,'requested is not a mutation fact');assert.equal(facts.at(-1).revision,3)
 for(const fact of facts.slice(-2)){assert.equal('reason' in fact,false);assert.equal('payload' in fact,false);assert.ok(JSON.stringify(fact).length<10000)}
 const restarted=new WorkflowProjectionStore(root), retry=await restarted.transition(ns,{...transition,requestId:'retry-generated'},definition,await evidenceStore.list(ns,storageId),execution)
 assert.equal(retry.ok,true);assert.equal(retry.changed,false);assert.equal(retry.idempotent,true);assert.equal(retry.snapshot.revision,3)
 const collision=await restarted.transition(ns,{...transition,requestId:'collision-generated',requestedStatus:'failed'},definition,await evidenceStore.list(ns,storageId),execution)
 assert.equal(collision.error.code,'IDEMPOTENCY_KEY_COLLISION');assert.equal((await restarted.read(ns,workflowId)).revision,3)
 const beforeReject=JSON.stringify(await restarted.read(ns,workflowId));const rejectedRequest=validateWorkflowTransitionRequest({workflowId,stepId:'review',expectedRevision:3,requestedStatus:'completed',evidenceIds:[]},workflowId).value
 const rejected=await restarted.transition(ns,rejectedRequest,definition,await evidenceStore.list(ns,storageId),{...execution,agentId:'Reviewer'})
 assert.equal(rejected.ok,false);assert.equal(JSON.stringify(await restarted.read(ns,workflowId)),beforeReject)
 const afterRejectFacts=(await readFile(store.paths(ns,workflowId).events,'utf8')).trim().split('\n').map(JSON.parse);assert.deepEqual(afterRejectFacts.slice(-2).map(f=>f.kind),['transition_requested','transition_rejected']);assert.equal(afterRejectFacts.at(-1).observedRevision,3)
 // Crash fixture uses the real protocol seam: pending and accepted are durable, final snapshot is not.
 const crashRequest=validateWorkflowTransitionRequest({workflowId,stepId:'review',expectedRevision:3,requestedStatus:'running',evidenceIds:[]},workflowId).value
 const durableEvidence=await evidenceStore.list(ns,storageId)
 await assert.rejects(()=>restarted.transition(ns,crashRequest,definition,durableEvidence,{...execution,agentId:'Reviewer'},{fault:async point=>{if(point==='after-accepted')throw new Error('simulated crash')}}))
 const recovered=await new WorkflowProjectionStore(root).read(ns,workflowId);assert.equal(recovered.revision,4);assert.equal(recovered.instance.steps.find(s=>s.id==='review').status,'running')
 const bypass=await restarted.publish(ns,{...recovered.projection,expectedRevision:4,status:'completed'},execution);assert.equal(bypass.error.code,'GOVERNED_WORKFLOW_REQUIRES_TRANSITION')
}finally{await rm(root,{recursive:true,force:true})}
console.log('workflow transition store source tests: OK')
