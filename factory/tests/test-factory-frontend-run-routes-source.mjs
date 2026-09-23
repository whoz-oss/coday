import assert from 'node:assert/strict'
import { handleFactoryFrontendRunRequest } from '../dashboard/factory-frontend-run-routes.mjs'
let response
const handled=await handleFactoryFrontendRunRequest({method:'POST',path:'/api/factory/workflows/wf/continue',readBody:async()=>({namespaceId:'ns'}),send:(status,body)=>{response={status,body}},runner:async(input)=>({status:'WAITING_HUMAN',...input})})
assert.equal(handled,true);assert.equal(response.status,200);assert.equal(response.body.data.workflowId,'wf')
await handleFactoryFrontendRunRequest({method:'POST',path:'/api/factory/workflows/wf/run',readBody:async()=>({}),send:(status,body)=>{response={status,body}},runner:async()=>{throw new Error('must not run')}})
assert.equal(response.status,400);assert.equal(response.body.error.code,'INVALID_RUN_REQUEST')
let logged
await handleFactoryFrontendRunRequest({method:'POST',path:'/api/factory/workflows/wf/run',readBody:async()=>({namespaceId:'ns'}),send:(status,body)=>{response={status,body}},runner:async()=>({status:'FAILED',code:'AGENT_PREFLIGHT_FAILED',details:`missing\n${'x'.repeat(2000)}`}),log:{error:(_message,data)=>{logged=data}}})
assert.equal(response.status,409);assert.equal(response.body.data.code,'AGENT_PREFLIGHT_FAILED');assert.equal(logged.details.includes('\n'),false);assert.equal(logged.details.length,1000)
