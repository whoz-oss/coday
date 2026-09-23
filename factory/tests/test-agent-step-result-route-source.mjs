import assert from 'node:assert/strict'
import {handleAgentStepResultRequest} from '../dashboard/agent-step-result-routes.mjs'
let response,submitted
const handled=await handleAgentStepResultRequest({method:'POST',path:'/api/factory/agent-step-results',headers:{authorization:'Bearer secret','x-agentos-case-id':'case','x-agentos-agent-name':'Worker'},readBody:async()=>({attemptId:'attempt',result:{status:'PASS',summary:'ok',claims:{modifiedFiles:[]}}}),send:(status,body)=>response={status,body},resultStore:{submit:async(token,result,identity)=>{submitted={token,result,identity};return{ok:true,idempotent:false,result:{resultId:'r',resultHash:'sha256:x'}}}}})
assert.equal(handled,true);assert.equal(response.status,201);assert.deepEqual(Object.keys(submitted.identity).sort(),['agentName','attemptId','caseId']);assert.equal('storageId' in submitted,false);assert.equal('namespaceId' in submitted,false)
response=null;await handleAgentStepResultRequest({method:'POST',path:'/api/factory/agent-step-results',headers:{},readBody:async()=>({attemptId:'a',storageId:'forbidden',result:{}}),send:(status,body)=>response={status,body},resultStore:{}});assert.equal(response.status,400)
