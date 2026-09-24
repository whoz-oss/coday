import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { handleAgentStepResultRequest } from '../dashboard/agent-step-result-routes.mjs'
import { AgentStepResultStore } from '../lib/agent-step-result-store.mjs'
import { createFactoryFrontendRunner } from '../lib/factory-frontend-composition.mjs'

const source=await readFile(new URL('../lib/factory-frontend-composition.mjs',import.meta.url),'utf8')
assert.match(source,/definition\.trustedExecution\?\.allowedPaths/)
assert.match(source,/FRONTEND_SCOPE_NOT_CONFIGURED/)
assert.doesNotMatch(source,/apps\/client/)
assert.doesNotMatch(source,/environment\.environment\.allowedPaths/)
assert.doesNotMatch(source,/new AgentStepResultStore/)
assert.throws(()=>createFactoryFrontendRunner({}),/FACTORY_FRONTEND_RESULT_STORE_REQUIRED/)

const root=await mkdtemp(join(tmpdir(),'factory-shared-result-store-'))
try{
 const resultStore=new AgentStepResultStore(root)
 createFactoryFrontendRunner({dataRoot:root,workflowRoot:root,resultStore})
 const namespaceId='11111111-1111-4111-8111-111111111111',storageId='workflow-storage',attemptId='attempt-1',workflowId='workflow-1',stepId='step-1',caseId='case-1',agentName='Worker'
 const capability=await resultStore.issue(namespaceId,storageId,{attemptId,workflowId,stepId,namespaceId,caseId,agentName,briefHash:`sha256:${'a'.repeat(64)}`})
 let response
 const handled=await handleAgentStepResultRequest({method:'POST',path:'/api/factory/agent-step-results',headers:{authorization:`Bearer ${capability.token}`,'x-agentos-case-id':caseId,'x-agentos-agent-name':agentName},readBody:async()=>({attemptId,result:{status:'PASS',summary:'ok',claims:{modifiedFiles:[]}}}),send:(status,body)=>response={status,body},resultStore,log:{warn(){},error(){}}})
 assert.equal(handled,true)
 assert.equal(response.status,201)
 assert.equal((await resultStore.getByAttempt(namespaceId,storageId,attemptId)).summary,'ok')
}finally{await rm(root,{recursive:true,force:true})}
