import assert from 'node:assert/strict'
import { evaluateWorkflowTransition } from '../lib/workflow-transition-policy.mjs'

const namespaceId='11111111-1111-4111-8111-111111111111'
const definition={workflowType:'oracle-smoke',version:'1.0.0',definitionHash:'hash',steps:[{id:'verify-code',dependsOn:[],responsibility:{kind:'code',name:'node-smoke'}}]}
const snapshot=status=>({revision:2,governanceMode:'governed',definitionVersion:'1.0.0',definitionHash:'hash',instance:{governanceMode:'governed',workflowType:'oracle-smoke',definitionVersion:'1.0.0',definitionHash:'hash',revision:2,status,steps:[{id:'verify-code',status}]},projection:{workflowId:'wf',status,steps:[{id:'verify-code',status}]}})
const execution={namespaceId,kind:'factory-oracle',runtimeId:'factory-dashboard',agentId:'factory-oracle'}
const request=(requestedStatus,evidenceIds=[])=>({workflowId:'wf',stepId:'verify-code',expectedRevision:2,requestedStatus,evidenceIds})
const pass={evidenceId:'pass',namespaceId,workflowId:'wf',stepId:'verify-code',kind:'oracle-result',outcome:'pass',source:{kind:'factory-oracle'},facts:{oracleId:'node-smoke'}}
const decide=(status,requestedStatus,evidence=[pass],override={})=>evaluateWorkflowTransition({request:request(requestedStatus,requestedStatus==='completed'?['pass']:[]),snapshot:snapshot(status),definition,evidence,execution,...override})
assert.equal(decide('ready','running',[]).allowed,true,'trusted Factory starts the code step')
assert.equal(decide('running','completed').allowed,true,'trusted PASS completes the code step')
for(const badExecution of [{...execution,kind:'agentos'},{...execution,runtimeId:'other'}])assert.equal(decide('running','completed',[pass],{execution:badExecution}).code,'ACTOR_NOT_AUTHORIZED')
for(const bad of [{...pass,outcome:'fail'},{...pass,outcome:'indeterminate'},{...pass,kind:'artifact'},{...pass,kind:'agent-result'},{...pass,facts:{oracleId:'other'}}])assert.equal(decide('running','completed',[bad]).code,'PASS_EVIDENCE_REQUIRED')
for(const bad of [{...pass,namespaceId:'other'},{...pass,workflowId:'other'},{...pass,stepId:'other'}])assert.equal(decide('running','completed',[bad]).code,'EVIDENCE_SCOPE_MISMATCH')
assert.equal(decide('running','completed',[pass],{execution:{namespaceId,kind:'agentos',runtimeId:'agentos-primary',agentId:'node-smoke',caseId:'case'}}).code,'ACTOR_NOT_AUTHORIZED','agent request_transition remains denied')
console.log('workflow code transition policy source tests: OK')
