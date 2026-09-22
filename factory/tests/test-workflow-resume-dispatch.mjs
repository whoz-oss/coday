import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { WorkflowResumeDispatchStore } from '../lib/workflow-resume-dispatch-store.mjs'
const root=await mkdtemp(join(tmpdir(),'resume-dispatch-'))
try{
 const store=new WorkflowResumeDispatchStore(root),input={dispatchId:'human:gate-1:revision:3',workflowId:'wf',interactionId:'gate-1',revision:3,controllerExecution:{kind:'coday-express',runtimeId:'thread-1',threadId:'thread-1',agentId:'Sway'}}
 const first=await store.reserve('namespace','storage',input);assert.equal(first.ok,true);assert.equal(first.idempotent,false)
 const uncertain=await store.reserve('namespace','storage',input);assert.deepEqual(uncertain,{ok:false,code:'DISPATCH_INDETERMINATE'})
 const delivered=await store.delivered('namespace','storage',input.dispatchId);assert.equal(delivered.ok,true)
 const replay=await store.reserve('namespace','storage',input);assert.equal(replay.delivered,true);assert.equal(replay.idempotent,true)
}finally{await rm(root,{recursive:true,force:true})}
console.log('workflow resume dispatch source scenarios: OK')
