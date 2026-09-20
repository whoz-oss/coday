// Phase 7 source scenarios. Intentionally not executed during implementation.
import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { join } from 'node:path';import { tmpdir } from 'node:os'
import { WorkflowHumanInteractionStore } from '../lib/workflow-human-interaction-store.mjs'
import { validateWorkflowEvidenceInput } from '../lib/workflow-evidence.mjs'
import { evaluateWorkflowTransition } from '../lib/workflow-transition-policy.mjs'
const root=await mkdtemp(join(tmpdir(),'human-interaction-')),ns='11111111-1111-4111-8111-111111111111'
try{
 const store=new WorkflowHumanInteractionStore(root),input={interactionId:'gate-1',workflowId:'wf',stepId:'approve',expectedRevision:2,kind:'approval',prompt:'Approve?',actions:[{id:'approve',label:'Approve',requestedStatus:'completed'}]},opened=await store.open(ns,'storage',input)
 assert.equal((await store.list(ns,'storage',{openOnly:true})).length,1)
 const transition={ok:true,requestId:'request-1',snapshot:{revision:3}},result=await store.transact(ns,'storage',opened.interactionId,async interaction=>({actorId:'user-1',evidenceId:'evidence-1',reply:{actionId:interaction.actions[0].id},transition}))
 assert.equal(result.interaction.actorId,'user-1');assert.equal((await new WorkflowHumanInteractionStore(root).list(ns,'storage'))[0].status,'replied')
 await assert.rejects(()=>store.transact(ns,'storage','gate-1',async()=>({transition})),error=>error.code==='INTERACTION_CLOSED')
 const lines=(await readFile(store.path(ns,'storage'),'utf8')).trim().split('\n').map(JSON.parse);assert.deepEqual(lines.map(line=>line.event),['interaction_opened','interaction_transitioned'])
 const human=validateWorkflowEvidenceInput({workflowId:'wf',stepId:'approve',kind:'human-decision',outcome:'pass',facts:{interactionId:'gate-1',actionId:'approve'}},'wf');assert.equal(human.ok,true)
 const snapshot={revision:2,governanceMode:'governed',definitionVersion:'1',definitionHash:'hash',instance:{governanceMode:'governed',workflowType:'delivery',definitionVersion:'1',definitionHash:'hash',revision:2,steps:[{id:'approve',status:'waiting_human'}]}},definition={workflowType:'delivery',version:'1',definitionHash:'hash',steps:[{id:'approve',dependsOn:[],responsibility:{kind:'human'}}]},request={workflowId:'wf',stepId:'approve',expectedRevision:2,requestedStatus:'running',evidenceIds:['evidence-1']},execution={namespaceId:ns,kind:'factory-human',runtimeId:'factory-dashboard',actorId:'user-1'},evidence={evidenceId:'evidence-1',namespaceId:ns,workflowId:'wf',stepId:'approve',kind:'human-decision',outcome:'pass',source:{kind:'factory-human',runtimeId:'factory-dashboard',actorId:'user-1'}}
 const accepted=evaluateWorkflowTransition({request,snapshot,definition,evidence:[evidence],execution})
 assert.deepEqual(accepted,{allowed:true},`expected human transition acceptance, got ${JSON.stringify(accepted)}`)
 const completionWithoutEvidence=evaluateWorkflowTransition({...{request:{...request,requestedStatus:'completed'},snapshot,definition,evidence:[],execution}})
 assert.equal(completionWithoutEvidence.code,'ILLEGAL_TRANSITION');assert.equal(evaluateWorkflowTransition({request,snapshot:{...snapshot,revision:3,instance:{...snapshot.instance,revision:3}},definition,evidence:[evidence],execution}).code,'REVISION_CONFLICT');assert.equal(evaluateWorkflowTransition({request,snapshot:{...snapshot,instance:{...snapshot.instance,steps:[{id:'approve',status:'running'}]}},definition,evidence:[evidence],execution}).code,'ILLEGAL_TRANSITION')
 const pending=await store.open(ns,'storage',{...input,interactionId:'gate-2'});await assert.rejects(()=>store.transact(ns,'storage',pending.interactionId,async()=>{throw new Error('simulated policy/storage crash')}));assert.equal((await new WorkflowHumanInteractionStore(root).list(ns,'storage',{openOnly:true})).some(item=>item.interactionId==='gate-2'),true,'failed transaction must leave interaction open for reconciliation/retry')
}finally{await rm(root,{recursive:true,force:true})}
console.log('workflow human interaction source scenarios: OK')
