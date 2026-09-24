import assert from 'node:assert/strict'
import { handleWorkflowCodeTransitionRequest } from '../dashboard/workflow-code-transition-routes.mjs'

const NS='11111111-1111-4111-8111-111111111111'
const snapshot={revision:2,instance:{workflowType:'oracle-smoke',definitionVersion:'1.0.0'},projection:{workflowId:'wf',status:'running',steps:[{id:'verify-code',status:'running'}]}}
const transition={workflowId:'wf',stepId:'verify-code',expectedRevision:2,requestedStatus:'completed',evidenceIds:['pass']}
function harness(result={ok:true,changed:true,idempotent:false,snapshot:{...snapshot,revision:3,projection:{...snapshot.projection,status:'completed'}}}){const calls=[],notices=[];return{store:{lookup:async()=>({state:'existing',snapshot}),transition:async(...args)=>{calls.push(args);return result}},evidenceStore:{list:async()=>[]},definitionRegistry:{get:async()=>({workflowType:'oracle-smoke',version:'1.0.0'})},calls,notices,notifier:{publish:(...args)=>notices.push(args)}}}
async function request(h,body={transition}){let response;await handleWorkflowCodeTransitionRequest({method:'POST',path:'/api/factory/workflows/wf/code-transitions',readBody:async()=>body,send:(status,payload)=>response={status,body:payload},namespaceId:NS,...h,log:{error(){}}});return response}
let h=harness(),response=await request(h);assert.equal(response.status,200);assert.equal(h.calls[0][4].kind,'factory-oracle');assert.equal(h.calls[0][4].runtimeId,'factory-dashboard');assert.equal(h.notices.length,1)
h=harness();response=await request(h,{transition,execution:{kind:'agentos'}});assert.equal(response.status,400);assert.equal(h.calls.length,0)
h=harness({ok:false,error:{code:'ACTOR_NOT_AUTHORIZED'},decision:{reason:'agent_tool_cannot_transition_non_agent_step'}});response=await request(h);assert.equal(response.status,409);assert.equal(h.notices.length,0)
let unavailable;await handleWorkflowCodeTransitionRequest({method:'POST',path:'/api/factory/workflows/wf/code-transitions',readBody:async()=>({transition}),send:(status,payload)=>unavailable={status,body:payload},namespaceId:undefined,...h,log:{error(){}}});assert.equal(unavailable.status,503)
console.log('workflow code transition HTTP source tests: OK')
