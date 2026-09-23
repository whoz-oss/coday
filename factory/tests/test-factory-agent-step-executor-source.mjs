import assert from 'node:assert/strict'
import {mkdtemp,readdir,readFile,rm} from 'node:fs/promises'
import {tmpdir} from 'node:os'
import {join} from 'node:path'
import { artifactEvidenceIdempotencyKey, materializeInlineArtifact, parseAgentStepResult } from '../lib/factory-agent-step-executor.mjs'
import {WORKFLOW_EVIDENCE_LIMITS,validateWorkflowEvidenceInput} from '../lib/workflow-evidence.mjs'

const valid='{"status":"PASS","summary":"ok","claims":{"modifiedFiles":[]}}'
assert.equal(parseAgentStepResult(valid).ok,true)
assert.equal(parseAgentStepResult(`\`\`\`json\n${valid}\n\`\`\``).ok,true)
assert.equal(parseAgentStepResult(`Bounded preamble\n${valid}\nBounded epilogue`).ok,true)
assert.equal(parseAgentStepResult(`${valid}\n${valid}`).code,'RESULT_NOT_JSON')
import { executeAgentStepAttempt,hashAgentBrief } from '../lib/factory-agent-step-executor.mjs'
assert.match(hashAgentBrief('bounded brief'),/^sha256:[0-9a-f]{64}$/)
const longArtifactPath=`forge/factory-artifacts/${'workflow-'.repeat(30)}/product-specification.md`
const compactKey=artifactEvidenceIdempotencyKey('00000000-0000-4000-8000-000000000000',longArtifactPath)
assert.equal(compactKey.length<=WORKFLOW_EVIDENCE_LIMITS.idempotencyKey,true)
assert.match(compactKey,/^00000000-0000-4000-8000-000000000000:artifact:[0-9a-f]{64}$/)
assert.equal(artifactEvidenceIdempotencyKey('00000000-0000-4000-8000-000000000000',longArtifactPath),compactKey)
assert.equal(validateWorkflowEvidenceInput({workflowId:'wf',stepId:'product-specification',kind:'artifact',artifactRef:longArtifactPath,artifactHash:`sha256:${'0'.repeat(64)}`,idempotencyKey:compactKey},'wf').ok,true)

{
  const root=await mkdtemp(join(tmpdir(),'factory-agent-artifact-'))
  try{
    const base={repoRoot:root,workflowId:'wf',stepId:'product-specification',attemptId:'attempt-1',expectedKind:'product-specification'}
    const result={artifacts:[{kind:'product-specification',encoding:'markdown',content:'# Product\n'}]}
    const first=await materializeInlineArtifact({...base,result});assert.equal(first.ok,true)
    const identical=await materializeInlineArtifact({...base,result});assert.deepEqual(identical,first)
    const collision=await materializeInlineArtifact({...base,result:{artifacts:[{kind:'product-specification',encoding:'markdown',content:'# Different\n'}]}})
    assert.equal(collision.code,'ARTIFACT_SEMANTIC_COLLISION')
    assert.equal(await readFile(join(root,first.artifacts[0].path),'utf8'),'# Product\n')
    assert.equal((await readdir(join(root,'forge','factory-artifacts','wf','product-specification'))).some((name)=>name.endsWith('.tmp')),false)
    const secondAttempt=await materializeInlineArtifact({...base,attemptId:'attempt-2',result:{artifacts:[{kind:'product-specification',encoding:'markdown',content:'# Different\n'}]}})
    assert.equal(secondAttempt.ok,true)
    assert.notEqual(secondAttempt.artifacts[0].path,first.artifacts[0].path)
    assert.equal((await materializeInlineArtifact({...base,attemptId:'../unsafe',result})).code,'ARTIFACT_PATH_INVALID')
  }finally{await rm(root,{recursive:true,force:true})}
}
const definition={steps:[{id:'analysis',responsibility:{kind:'agent',name:'Worker'}}]},snapshot={revision:2,instance:{steps:[{id:'analysis',status:'ready'}]}}
const common={namespaceId:'ns',workflowId:'wf',stepId:'analysis',definition,projectionStore:{read:async()=>snapshot},evidenceStore:{},attemptStore:{},resultStore:{},storageId:'s',repoRoot:'/tmp',brief:'x',agentOps:{preflightAgent:async()=>({ok:true,agent:{name:'Worker',subAgents:[]}}),preflightReadOnlyWorkspace:async()=>({ok:true})}}
assert.equal((await executeAgentStepAttempt({...common,expectedRevision:1})).code,'REVISION_CONFLICT')
assert.equal((await executeAgentStepAttempt({...common,agentOps:{...common.agentOps,preflightAgent:async()=>({ok:true,agent:{name:'Substitute',subAgents:[]}})}})).code,'AGENT_PREFLIGHT_FAILED')
const workspaceFailure=await executeAgentStepAttempt({...common,agentOps:{...common.agentOps,preflightReadOnlyWorkspace:async()=>({ok:false,reason:'wrong root'})}})
assert.equal(workspaceFailure.code,'AGENT_PREFLIGHT_FAILED')
assert.equal(workspaceFailure.details,'wrong root')
const bounded=await executeAgentStepAttempt({...common,agentOps:{...common.agentOps,preflightAgent:async()=>({ok:false,reason:`missing\n${'x'.repeat(2000)}`,agent:null})}})
assert.equal(bounded.code,'AGENT_PREFLIGHT_FAILED');assert.equal(bounded.details.includes('\n'),false);assert.equal(bounded.details.length,1000)

async function runTurnScenario({turns,results,workflowId='wf',stepId='analysis',expectedArtifactKind,repoRoot='/tmp'}) {
  const calls=[], issued=[], attempts=[], recordedEvidence=[]
  let revision=2, resultIndex=0
  const ready={revision,instance:{steps:[{id:'analysis',status:'ready'}]}}
  const projectionStore={
    read:async()=>ready,
    transition:async(_namespace,request)=>{revision++;return{ok:true,snapshot:{revision,instance:{steps:[{id:'analysis',status:request.requestedStatus}]}}}},
  }
  const evidenceStore={record:async(_namespace,_storage,value)=>{recordedEvidence.push(value);return{evidence:{...value,evidenceId:`e-${value.kind}`}}}}
  const attemptStore={list:async()=>[],append:async(_namespace,_storage,value)=>{attempts.push(value);return value}}
  const resultStore={
    issue:async(...args)=>{issued.push(args);return{token:'token',expiresAt:'2099-01-01T00:00:00.000Z'}},
    getByAttempt:async()=>results[resultIndex++]??null,
  }
  const agentOps={
    preflightAgent:async()=>({ok:true,agent:{name:'Worker',subAgents:[]}}),
    preflightReadOnlyWorkspace:async()=>({ok:true}),
    createCase:async()=>({id:'case-1'}),
    bindFactoryStepResult:async()=>{},
    runAgentTurn:async(...args)=>{calls.push(args);const next=turns[calls.length-1];if(next instanceof Error)throw next;return next},
  }
  const scenarioDefinition={steps:[{id:stepId,responsibility:{kind:'agent',name:'Worker'}}]}
  ready.instance.steps[0]={id:stepId,status:'ready'}
  const value=await executeAgentStepAttempt({namespaceId:'ns',workflowId,stepId,definition:scenarioDefinition,projectionStore,evidenceStore,attemptStore,resultStore,storageId:'s',repoRoot,brief:'business work',expectedArtifactKind,agentOps})
  return{value,calls,issued,attempts,recordedEvidence}
}
const finished=(overrides={})=>({status:'finished',anchored:true,agentsSelected:['Worker'],message:'',events:[],...overrides})
const structured={status:'PASS',summary:'done',claims:{modifiedFiles:[]}}

{
  const scenario=await runTurnScenario({turns:[finished()],results:[structured]})
  assert.equal(scenario.value.ok,true);assert.equal(scenario.calls.length,1);assert.equal(scenario.issued.length,1)
}
{
  const root=await mkdtemp(join(tmpdir(),'factory-finalized-artifact-'))
  try{
    const artifactResult={...structured,artifacts:[{kind:'product-specification',encoding:'markdown',content:'# Finalized\n'}]}
    const scenario=await runTurnScenario({turns:[finished(),finished()],results:[null,artifactResult],workflowId:`wf-${'x'.repeat(100)}`,stepId:'product-specification',expectedArtifactKind:'product-specification',repoRoot:root})
    assert.equal(scenario.value.ok,true)
    const artifactEvidence=scenario.recordedEvidence.find((value)=>value.kind==='artifact')
    assert.equal(artifactEvidence.idempotencyKey.length<=WORKFLOW_EVIDENCE_LIMITS.idempotencyKey,true)
    assert.match(artifactEvidence.idempotencyKey,/:artifact:[0-9a-f]{64}$/)
  }finally{await rm(root,{recursive:true,force:true})}
}
{
  const scenario=await runTurnScenario({turns:[finished(),finished()],results:[null,structured]})
  assert.equal(scenario.value.ok,true);assert.equal(scenario.calls.length,2);assert.equal(scenario.issued.length,1)
  assert.deepEqual(scenario.calls.map(([caseId,agent])=>[caseId,agent]),[['case-1','Worker'],['case-1','Worker']])
  assert.match(scenario.calls[1][2],/Do no new analysis or work/);assert.match(scenario.calls[1][2],/exactly once/)
}
{
  const scenario=await runTurnScenario({turns:[finished(),finished(),finished()],results:[null,null]})
  assert.equal(scenario.value.code,'STRUCTURED_RESULT_MISSING_AFTER_FINALIZATION');assert.equal(scenario.calls.length,2)
}
for(const status of ['error','case_error']){
  const scenario=await runTurnScenario({turns:[finished(),finished({status})],results:[null]})
  assert.equal(scenario.value.code,`STRUCTURED_RESULT_FINALIZATION_${status.toUpperCase()}`);assert.equal(scenario.calls.length,2)
}
{
  const scenario=await runTurnScenario({turns:[finished(),finished({anchored:false})],results:[null]})
  assert.equal(scenario.value.code,'STRUCTURED_RESULT_FINALIZATION_HISTORY_NOT_ANCHORED');assert.equal(scenario.calls.length,2)
}
for(const agentsSelected of [['Other'],['Worker','Other']]){
  const scenario=await runTurnScenario({turns:[finished(),finished({agentsSelected})],results:[null]})
  assert.equal(scenario.value.code,'STRUCTURED_RESULT_FINALIZATION_WORKER_IDENTITY_MISMATCH');assert.equal(scenario.calls.length,2)
}
