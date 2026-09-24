import assert from 'node:assert/strict'
import { mkdtemp,rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { AgentStepAttemptStore } from '../lib/agent-step-attempt-store.mjs'
const root=await mkdtemp(join(tmpdir(),'attempt-store-')),store=new AgentStepAttemptStore(root)
try{
 const base={attemptId:'a',workflowId:'wf',workflowRevisionAtStart:1,stepId:'ticket-analysis',attemptNumber:1,namespaceId:'ns',runtimeId:'factory',caseId:null,agentName:'ForgeProductWorker',briefHash:`sha256:${'a'.repeat(64)}`,status:'starting',startedAt:new Date().toISOString(),finishedAt:null,evidenceId:null,failureCode:null}
 await store.append('ns','storage',base);await store.append('ns','storage',{...base,caseId:'case',status:'running'});await store.append('ns','storage',{...base,caseId:'case',status:'succeeded',finishedAt:new Date().toISOString(),evidenceId:'e'})
 assert.deepEqual((await store.list('ns','storage')).map((x)=>x.status),['starting','running','succeeded'])
 await assert.rejects(()=>store.append('other','storage',{...base,namespaceId:'ns'}),/NAMESPACE_MISMATCH/)
 await assert.rejects(()=>store.append('ns','storage',{...base,status:'running',caseId:'case'}),/INVALID_AGENT_STEP_ATTEMPT_TRANSITION/)
}finally{await rm(root,{recursive:true,force:true})}
