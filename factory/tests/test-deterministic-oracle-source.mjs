// Source scenarios for Phase 6. Intentionally not executed during implementation.
import assert from 'node:assert/strict'
import { validateOracleDefinition, OracleDefinitionRegistry } from '../lib/oracle-definition.mjs'
import { classifyOracleExecution } from '../lib/oracle-executor.mjs'
import { validateWorkflowEvidenceInput } from '../lib/workflow-evidence.mjs'
import { evaluateWorkflowTransition } from '../lib/workflow-transition-policy.mjs'

const oracle={schemaVersion:'1',id:'check',version:'1.0.0',domain:'factory',argv:['node','fixture.mjs'],cwd:'repo-root',timeoutMs:1000,success:{rule:'exit-code',requireWork:true},applicable:{workflowTypes:['oracle-smoke'],stepIds:['verify-code']}}
const frozen=validateOracleDefinition(oracle);assert.equal(Object.isFrozen(frozen),true);assert.equal(Object.isFrozen(frozen.argv),true)
for(const bad of [{...oracle,argv:[]},{...oracle,argv:['sh','-c','model command']},{...oracle,cwd:'/model/root'},{...oracle,timeoutMs:0},{...oracle,extra:true}])assert.throws(()=>validateOracleDefinition(bad))
assert.deepEqual(classifyOracleExecution(frozen,{exitCode:0,signal:null,timedOut:false,spawnError:null,counts:{executed:1}}),{classification:'CLEAN',outcome:'pass'})
assert.deepEqual(classifyOracleExecution(frozen,{exitCode:0,signal:null,timedOut:false,spawnError:null,counts:{executed:0}}),{classification:'EMPTY_SUCCESS',outcome:'indeterminate'})
assert.equal(classifyOracleExecution(frozen,{exitCode:1,signal:null,timedOut:false,spawnError:null,counts:{executed:1}}).outcome,'fail')
for(const result of [{exitCode:null,signal:'SIGKILL',timedOut:false,spawnError:null},{exitCode:null,signal:null,timedOut:true,spawnError:null},{exitCode:null,signal:null,timedOut:false,spawnError:'ENOENT'}])assert.deepEqual(classifyOracleExecution(frozen,{...result,counts:{executed:0}}),{classification:'ORACLE_INFRASTRUCTURE',outcome:'indeterminate'})
const evidence=validateWorkflowEvidenceInput({workflowId:'wf',stepId:'verify-code',kind:'oracle-result',outcome:'pass',facts:{oracleId:'check',oracleVersion:'1.0.0',classification:'CLEAN',durationMs:2,outputHash:`sha256:${'a'.repeat(64)}`}},'wf');assert.equal(evidence.ok,true);assert.equal(JSON.stringify(evidence).includes('raw output'),false)
const definition={workflowType:'oracle-smoke',version:'1.0.0',definitionHash:'hash',steps:[{id:'verify-code',dependsOn:[],responsibility:{kind:'code',name:'check'}}]}
const snapshot={revision:2,governanceMode:'governed',definitionVersion:'1.0.0',definitionHash:'hash',instance:{governanceMode:'governed',workflowType:'oracle-smoke',definitionVersion:'1.0.0',definitionHash:'hash',revision:2,steps:[{id:'verify-code',status:'running'}]}}
const request={workflowId:'wf',stepId:'verify-code',expectedRevision:2,requestedStatus:'completed',evidenceIds:['ev']}
const pass={evidenceId:'ev',namespaceId:'ns',workflowId:'wf',stepId:'verify-code',kind:'oracle-result',outcome:'pass',source:{kind:'factory-oracle'},facts:{oracleId:'check'}}
assert.equal(evaluateWorkflowTransition({request,snapshot,definition,evidence:[pass],execution:{namespaceId:'ns',kind:'factory-oracle',runtimeId:'factory-dashboard'}}).allowed,true)
assert.equal(evaluateWorkflowTransition({request,snapshot,definition,evidence:[pass],execution:{namespaceId:'ns',kind:'agentos',runtimeId:'agentos-primary',agentId:'check'}}).code,'ACTOR_NOT_AUTHORIZED')
assert.equal(evaluateWorkflowTransition({request,snapshot,definition,evidence:[{...pass,facts:{oracleId:'other'}}],execution:{namespaceId:'ns',kind:'factory-oracle',runtimeId:'factory-dashboard'}}).code,'PASS_EVIDENCE_REQUIRED')
// HTTP route suites inject absent/removed/purged, declarative/mismatched/non-code snapshots,
// reject command/cwd/root fields, assert run-only leaves projection revision unchanged,
// exercise duplicate/colliding idempotency keys, bounded output, and 400/404/409/410/405 statuses.
// Operational fixture: a child spawning a grandchild must be killed as a detached process group on timeout.
console.log('deterministic oracle source scenarios: OK')
