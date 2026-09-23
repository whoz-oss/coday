import assert from 'node:assert/strict'
import { runWorkflowUntilStop } from '../lib/factory-frontend-runner.mjs'
const definition={steps:[{id:'gate',name:'Gate',responsibility:{kind:'human',name:'Owner'}}]}
const snapshot={revision:1,instance:{status:'ready',steps:[{id:'gate',status:'ready'}]}}
let opened=false,attemptLists=0,receivedGateExecution
const humanGateExecution={kind:'factory-human-gate',runtimeId:'factory-dashboard',agentId:'factory-runner'}
const result=await runWorkflowUntilStop({namespaceId:'ns',workflowId:'wf',definition,projectionStore:{read:async()=>snapshot,openHumanCheckpoint:async(_n,_r,_d,execution)=>{receivedGateExecution=execution;return{ok:true,snapshot:{revision:2}}}},evidenceStore:{},attemptStore:{list:async()=>{attemptLists++;return[]}},storageId:'s',humanInteractionStore:{open:async(_n,_s,input,transition)=>{opened=true;const t=await transition();return{interaction:{interactionId:'i',...input},transition:t}}},controllerExecution:{kind:'agentos',runtimeId:'factory',agentId:'runner'},humanGateExecution})
assert.equal(result.status,'WAITING_HUMAN');assert.equal(opened,true);assert.equal(result.interaction.actions[0].requestedStatus,'completed');assert.deepEqual(receivedGateExecution,humanGateExecution);assert.equal(attemptLists,0,'/continue opening a ready human gate creates no AgentStepAttempt')
let recoveryAttempts=0
const waiting={revision:2,instance:{status:'waiting_human',steps:[{id:'gate',status:'waiting_human'}]}}
const recovered=await runWorkflowUntilStop({namespaceId:'ns',workflowId:'wf',definition,projectionStore:{read:async()=>waiting,facts:async()=>[{kind:'transition_accepted'}]},evidenceStore:{},attemptStore:{list:async()=>{recoveryAttempts++;return[]}},storageId:'s',humanInteractionStore:{reconcileOpen:async()=>({status:'open',interaction:{interactionId:'recovered'}})}})
assert.equal(recovered.status,'WAITING_HUMAN');assert.equal(recovered.interaction.interactionId,'recovered');assert.equal(recoveryAttempts,0,'recovery creates no AgentStepAttempt')
const blocked=await runWorkflowUntilStop({...{namespaceId:'ns',workflowId:'wf',definition,projectionStore:{read:async()=>snapshot},evidenceStore:{},attemptStore:{},storageId:'s'}})
assert.equal(blocked.code,'HUMAN_INTERACTION_STORE_UNAVAILABLE')
const runningSnapshot={revision:2,instance:{status:'running',steps:[{id:'gate',status:'running'}]}}
const terminal=await runWorkflowUntilStop({namespaceId:'ns',workflowId:'wf',definition,projectionStore:{read:async()=>runningSnapshot},evidenceStore:{},attemptStore:{list:async()=>[{stepId:'gate',attemptNumber:1,status:'indeterminate',failureCode:'RESULT_NOT_JSON'}]},storageId:'s'})
assert.equal(terminal.code,'RUNNING_STEP_WITH_TERMINAL_ATTEMPT')
const active=await runWorkflowUntilStop({namespaceId:'ns',workflowId:'wf',definition,projectionStore:{read:async()=>runningSnapshot},evidenceStore:{},attemptStore:{list:async()=>[{stepId:'gate',attemptNumber:1,status:'running'}]},storageId:'s'})
assert.equal(active.code,'ACTIVE_ATTEMPT_IN_PROGRESS')
