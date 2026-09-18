import assert from 'node:assert/strict'
import { appendFile, mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { validateWorkflowEvidenceInput } from '../lib/workflow-evidence.mjs'
import { WorkflowEvidenceStore } from '../lib/workflow-evidence-store.mjs'

const workflowId='governed-1', stepId='implement', source={runtimeId:'agentos-primary',kind:'agentos',agentId:'ProductEngineer',caseId:'case-1',actorId:'user-1'}
const agent={workflowId,stepId,kind:'agent-result',outcome:'pass',facts:{resultCode:'DONE',attempt:1},idempotencyKey:'turn-1'}
assert.equal(validateWorkflowEvidenceInput(agent,workflowId).ok,true)
assert.equal(validateWorkflowEvidenceInput({workflowId,stepId,kind:'artifact',artifactRef:'opaque://result',artifactHash:`sha256:${'a'.repeat(64)}`},workflowId).ok,true)
for(const invalid of [
 {...agent,evidenceId:'model-id'}, {...agent,namespaceId:'model-ns'}, {...agent,source}, {...agent,observedAt:new Date().toISOString()},
 {...agent,facts:{summary:'raw LLM prose'}}, {...agent,payload:{anything:true}},
 {workflowId,stepId,kind:'artifact',artifactRef:'path-only'}, {workflowId,stepId,kind:'artifact',artifactRef:'x',artifactHash:'a'.repeat(64)},
]) assert.equal(validateWorkflowEvidenceInput(invalid,workflowId).ok,false)

const root=await mkdtemp(join(tmpdir(),'factory-evidence-'))
try{
 const store=new WorkflowEvidenceStore(root), validated=validateWorkflowEvidenceInput(agent,workflowId).value
 const first=await store.record('11111111-1111-4111-8111-111111111111','storage',validated,source)
 assert.equal(first.created,true); assert.match(first.evidence.evidenceId,/^[0-9a-f-]{36}$/); assert.ok(!Number.isNaN(Date.parse(first.evidence.observedAt))); assert.deepEqual(first.evidence.source,source)
 const restarted=new WorkflowEvidenceStore(root)
 const replay=await restarted.record('11111111-1111-4111-8111-111111111111','storage',validated,source); assert.equal(replay.idempotent,true); assert.equal(replay.evidence.evidenceId,first.evidence.evidenceId)
 await assert.rejects(()=>new WorkflowEvidenceStore(root).record('11111111-1111-4111-8111-111111111111','storage',{...validated,outcome:'fail'},source),error=>error.code==='IDEMPOTENCY_KEY_COLLISION')
 const secondInput=validateWorkflowEvidenceInput({...agent,stepId:'verify',idempotencyKey:'turn-2'},workflowId).value
 const second=await restarted.record('11111111-1111-4111-8111-111111111111','storage',secondInput,source)
 const listed=await new WorkflowEvidenceStore(root).list('11111111-1111-4111-8111-111111111111','storage')
 assert.deepEqual(listed.map(e=>e.evidenceId),[first.evidence.evidenceId,second.evidence.evidenceId])
 assert.deepEqual((await restarted.list('11111111-1111-4111-8111-111111111111','storage',{stepId})).map(e=>e.evidenceId),[first.evidence.evidenceId])
 assert.deepEqual(await restarted.list('22222222-2222-4222-8222-222222222222','storage'),[])
 const lines=(await readFile(restarted.path('11111111-1111-4111-8111-111111111111','storage'),'utf8')).trim().split('\n').map(JSON.parse)
 for(const line of lines){assert.equal('payload' in line,false);assert.equal('summary' in line,false);assert.equal('idempotencyKey' in line,false);assert.deepEqual(Object.keys(line.facts??{}).every(key=>['resultCode','category','attempt','durationMs','itemCount'].includes(key)),true)}
 // createWorkflowEvidence freezes its root and source, then the store shallow-spreads
 // that root into a detached mutable record: the returned root is assignable while its
 // shared source remains frozen. Neither kind of attempted change may alter durable JSONL.
 assert.equal(Object.isFrozen(first.evidence),false); assert.equal(Object.isFrozen(first.evidence.source),true)
 first.evidence.outcome='fail'
 assert.throws(()=>{first.evidence.source.agentId='mutated'},TypeError)
 const durableFirst=(await new WorkflowEvidenceStore(root).list('11111111-1111-4111-8111-111111111111','storage')).find(item=>item.evidenceId===first.evidence.evidenceId)
 assert.equal(durableFirst.outcome,'pass'); assert.equal(durableFirst.source.agentId,'ProductEngineer')
 await appendFile(restarted.path('11111111-1111-4111-8111-111111111111','storage'),'{"truncated":')
 await assert.rejects(()=>new WorkflowEvidenceStore(root).list('11111111-1111-4111-8111-111111111111','storage'),error=>error.code==='CORRUPT_EVIDENCE_STORAGE')
} finally { await rm(root,{recursive:true,force:true}) }
console.log('workflow evidence source tests: OK')
