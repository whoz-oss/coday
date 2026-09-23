// Source-only governed scenarios; intentionally not executed during implementation.
import assert from 'node:assert/strict'
import {mkdtemp,rm,readFile} from 'node:fs/promises'
import {tmpdir} from 'node:os'
import {join} from 'node:path'
import {AgentStepResultStore} from '../lib/agent-step-result-store.mjs'

let now=new Date('2030-01-01T00:00:00Z')
const root=await mkdtemp(join(tmpdir(),'step-result-'))
const makeStore=()=>new AgentStepResultStore(root,{clock:()=>now,ttlMs:1000})
const id={attemptId:'attempt',workflowId:'wf',stepId:'step',namespaceId:'ns',caseId:'case',agentName:'Worker',briefHash:`sha256:${'a'.repeat(64)}`}
const business={status:'PASS',summary:'done',artifacts:[{kind:'spec',encoding:'markdown',content:'# raw'}],claims:{modifiedFiles:[]},findings:[]}
const observed=attemptId=>({attemptId,caseId:'case',agentName:'Worker'})
const records=async store=>(await store.list('ns','storage')).filter(event=>event.type==='result-submitted')

try{
 const store=makeStore(),cap=await store.issue('ns','storage',id)
 assert.equal((await readFile(store.path('ns','storage'),'utf8')).includes(cap.token),false,'clear token is never persisted')
 assert.equal((await store.submit(cap.token,business,observed('attempt'))).idempotent,false,'first valid submission succeeds')
 assert.equal((await store.submit(cap.token,business,observed('attempt'))).idempotent,true,'identical replay succeeds')
 assert.equal((await store.submit(cap.token,{...business,summary:'different'},observed('attempt'))).code,'RESULT_SEMANTIC_COLLISION')
 assert.equal((await store.submit(cap.token,business,{...observed('attempt'),caseId:'wrong'})).code,'RESULT_IDENTITY_MISMATCH')

 const beforeExpiry=await store.issue('ns','storage',{...id,attemptId:'before-expiry'})
 now=new Date(now.getTime()+2000)
 assert.equal((await store.submit(beforeExpiry.token,business,observed('before-expiry'))).code,'RESULT_CAPABILITY_EXPIRED')

 now=new Date('2030-01-01T00:01:00Z')
 const issuedBeforeRestart=await store.issue('ns','storage',{...id,attemptId:'restart-before'})
 const restartedBefore=makeStore();await restartedBefore.initialize()
 assert.equal((await restartedBefore.submit(issuedBeforeRestart.token,business,observed('restart-before'))).ok,true,'issued capability recovers after restart')

 const submittedBeforeRestart=await store.issue('ns','storage',{...id,attemptId:'restart-after'})
 await store.submit(submittedBeforeRestart.token,business,observed('restart-after'))
 now=new Date(now.getTime()+2000)
 const restartedAfter=makeStore();await restartedAfter.initialize()
 assert.equal((await restartedAfter.submit(submittedBeforeRestart.token,business,observed('restart-after'))).idempotent,true,'submitted capability permits identical retry after expiry and restart')
 assert.equal((await restartedAfter.submit(submittedBeforeRestart.token,{...business,summary:'collision'},observed('restart-after'))).code,'RESULT_SEMANTIC_COLLISION')

 await assert.rejects(()=>restartedAfter.issue('ns','storage',{...id,attemptId:'restart-after'}),/RESULT_CAPABILITY_ALREADY_ISSUED/)
 await assert.rejects(()=>restartedAfter.issue('ns','storage',{...id,attemptId:'restart-after',caseId:'other'}),/RESULT_CAPABILITY_IDENTITY_CONFLICT/)

 now=new Date('2030-01-01T00:02:00Z')
 const identicalCap=await restartedAfter.issue('ns','storage',{...id,attemptId:'concurrent-identical'})
 const identical=await Promise.all([restartedAfter.submit(identicalCap.token,business,observed('concurrent-identical')),restartedAfter.submit(identicalCap.token,business,observed('concurrent-identical'))])
 assert.equal(identical.filter(result=>result.ok&&!result.idempotent).length,1)
 assert.equal(identical.filter(result=>result.ok&&result.idempotent).length,1)

 const conflictingCap=await restartedAfter.issue('ns','storage',{...id,attemptId:'concurrent-conflict'})
 const conflicting=await Promise.all([restartedAfter.submit(conflictingCap.token,business,observed('concurrent-conflict')),restartedAfter.submit(conflictingCap.token,{...business,summary:'other'},observed('concurrent-conflict'))])
 assert.equal(conflicting.filter(result=>result.ok).length,1)
 assert.equal(conflicting.filter(result=>result.code==='RESULT_SEMANTIC_COLLISION').length,1)
 const persisted=await records(restartedAfter)
 assert.equal(persisted.filter(result=>result.attemptId==='concurrent-identical').length,1)
 assert.equal(persisted.filter(result=>result.attemptId==='concurrent-conflict').length,1)
}finally{await rm(root,{recursive:true,force:true})}
