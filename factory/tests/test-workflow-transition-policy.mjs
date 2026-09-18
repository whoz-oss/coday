import assert from 'node:assert/strict'
import { applyWorkflowTransition,evaluateWorkflowTransition,validateWorkflowTransitionRequest,WORKFLOW_TRANSITIONS } from '../lib/workflow-transition-policy.mjs'
const namespaceId='11111111-1111-4111-8111-111111111111'
const execution={namespaceId,runtimeId:'agentos-primary',kind:'agentos',agentId:'Builder',caseId:'case-1'}
const definition={workflowType:'delivery',version:'1.0.0',definitionHash:'hash',steps:[{id:'build',dependsOn:[],responsibility:{kind:'agent',name:'Builder'}},{id:'review',dependsOn:['build'],responsibility:{kind:'agent',name:'Reviewer'}}]}
function snapshot(status='running'){return {revision:2,governanceMode:'governed',definitionVersion:'1.0.0',definitionHash:'hash',instance:{governanceMode:'governed',workflowType:'delivery',definitionVersion:'1.0.0',definitionHash:'hash',revision:2,status:'running',steps:[{id:'build',status},{id:'review',status:'pending'}]},projection:{schemaVersion:'2',workflowId:'wf-1',workflowType:'delivery',title:'Delivery',status:'running',steps:[{id:'build',name:'Build',status,dependsOn:[],responsibility:{kind:'agent',name:'Builder'}},{id:'review',name:'Review',status:'pending',dependsOn:['build'],responsibility:{kind:'agent',name:'Reviewer'}}]}}}
function request(overrides={}){return {requestId:'request-1',workflowId:'wf-1',stepId:'build',expectedRevision:2,requestedStatus:'completed',evidenceIds:['evidence-1'],...overrides}}
function passEvidence(overrides={}){return {evidenceId:'evidence-1',namespaceId,workflowId:'wf-1',stepId:'build',kind:'agent-result',outcome:'pass',source:{runtimeId:'agentos-primary',kind:'agentos',agentId:'Builder',caseId:'case-1'},...overrides}}
function option(overrides, key, fallback) {
  return Object.prototype.hasOwnProperty.call(overrides, key) ? overrides[key] : fallback
}
function decide(overrides={}) {
  return evaluateWorkflowTransition({
    request: option(overrides, 'request', request()),
    snapshot: option(overrides, 'snapshot', snapshot()),
    definition: option(overrides, 'definition', definition),
    evidence: option(overrides, 'evidence', [passEvidence()]),
    execution: option(overrides, 'execution', execution),
  })
}
assert.deepEqual(WORKFLOW_TRANSITIONS.completed,[])
assert.deepEqual(decide(),{allowed:true})
for(const [expected,options] of [
 ['WORKFLOW_NOT_FOUND',{snapshot:null}],['WORKFLOW_NOT_GOVERNED',{snapshot:{...snapshot(),governanceMode:undefined}}],['WORKFLOW_DEFINITION_NOT_FOUND',{definition:null}],
 ['WORKFLOW_DEFINITION_MISMATCH',{definition:{...definition,definitionHash:'other'}}],['STEP_NOT_FOUND',{request:request({stepId:'missing'})}],['REVISION_CONFLICT',{request:request({expectedRevision:1})}],
 ['ILLEGAL_TRANSITION',{snapshot:snapshot('completed')}],['ACTOR_NOT_AUTHORIZED',{execution:{...execution,agentId:'Other'}}],['EVIDENCE_NOT_FOUND',{evidence:[]}],
 ['EVIDENCE_SCOPE_MISMATCH',{evidence:[passEvidence({stepId:'review'})]}],['PASS_EVIDENCE_REQUIRED',{evidence:[{...passEvidence(),kind:'artifact',outcome:undefined}]}],
 ['EVIDENCE_NEGATIVE',{evidence:[passEvidence({outcome:'fail'})]}],['PASS_EVIDENCE_REQUIRED',{evidence:[passEvidence({source:{...passEvidence().source,caseId:'other'}})]}],
]) {
  const decision = decide(options)
  assert.equal(decision.allowed, false, `expected ${expected} to be denied`)
  assert.equal(decision.code, expected, `expected ${expected}`)
  assert.equal(typeof decision.reason, 'string', `${expected} must have a deterministic reason`)
}
const dependencyRequest=request({stepId:'review',requestedStatus:'ready',evidenceIds:[]})
assert.equal(decide({request:dependencyRequest,execution:{...execution,agentId:'Reviewer'}}).code,'DEPENDENCIES_NOT_SATISFIED')
const applied=applyWorkflowTransition(snapshot(),definition,request(),'2026-01-01T00:00:00.000Z')
assert.equal(applied.revision,3);assert.equal(applied.instance.steps.find(s=>s.id==='review').status,'ready');assert.equal(applied.instance.status,'ready');assert.equal(snapshot().instance.revision,2)
assert.equal(validateWorkflowTransitionRequest({...request(),requestId:undefined},'wf-1').ok,true)
assert.equal(validateWorkflowTransitionRequest({...request()},'wf-1').error.code,'UNTRUSTED_REQUEST_ID')
console.log('workflow transition policy source tests: OK')
