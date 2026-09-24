import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { handleWorkflowProjectionRequest } from '../dashboard/workflow-projection-routes.mjs'
import { WorkflowProjectionStore } from '../lib/workflow-projection-store.mjs'
import { WorkflowDefinitionRegistry } from '../lib/workflow-definition-registry.mjs'

const NS='11111111-1111-4111-8111-111111111111', OTHER='22222222-2222-4222-8222-222222222222'
const execution={namespaceId:NS,runtimeId:'agentos-test',kind:'agentos',agentId:'ProductEngineer',caseId:'case-1',actorId:'user-1'}
const workflow={workflowId:'WZ-1',workflowType:'bmad-story',title:'Story'}
const definitions = new WorkflowDefinitionRegistry(new URL('../workflows', import.meta.url).pathname)
await definitions.initialize()

async function request(store, path, body, registry = definitions) {
  let response
  const url = new URL(path, 'http://localhost')
  await handleWorkflowProjectionRequest({
    method: 'POST',
    path: url.pathname,
    url,
    store,
    definitionRegistry: registry,
    readBody: async () => body,
    send: (status, payload) => { response = { status, body: payload } },
    log: { error() {} },
  })
  return response
}

const root = await mkdtemp(join(tmpdir(), 'factory-start-api-'))
try {
 const store = new WorkflowProjectionStore(root)
 await store.initialize()

 let response = await request(store, '/api/factory/workflows/WZ-1/start', { workflow, execution })
 assert.equal(response.status, 201)
 assert.equal(response.body.data.created, true)
 assert.equal(response.body.data.governanceMode, 'governed')
 assert.equal(response.body.data.definitionVersion, '1.0.0')
 assert.deepEqual(response.body.data.relations, { rootWorkflowId: 'WZ-1' })
 assert.equal(
   response.body.data.projection.steps.find((step) => step.id === 'implementation').responsibility.name,
   'BmadBuilder',
 )

 response = await request(store, '/api/factory/workflows/WZ-1/start', { workflow, execution })
 assert.equal(response.status, 200)
 const childResponse = await request(store, '/api/factory/workflows/WZ-2/start', { workflow: { ...workflow, workflowId: 'WZ-2', relations: { parentWorkflowId: 'WZ-1', groupId: 'release-1' } }, execution })
 assert.equal(childResponse.status, 201)
 assert.deepEqual(childResponse.body.data.relations, { parentWorkflowId: 'WZ-1', groupId: 'release-1', rootWorkflowId: 'WZ-1' })
 const missingParent = await request(store, '/api/factory/workflows/orphan/start', { workflow: { ...workflow, workflowId: 'orphan', relations: { parentWorkflowId: 'not-visible' } }, execution })
 assert.equal(missingParent.status, 404)
 assert.equal(missingParent.body.error.code, 'PARENT_WORKFLOW_NOT_FOUND')
 assert.equal(response.body.data.idempotent, true)
 assert.equal((await request(store,'/api/factory/workflows/other/start',{workflow,execution})).body.error.code,'INVALID_START_REQUEST')
 assert.equal((await request(store,'/api/factory/workflows/new/start',{workflow:{...workflow,workflowId:'new'},execution:{...execution,namespaceId:OTHER,narrative:'bad'}})).body.error.code,'INVALID_EXECUTION'); assert.equal((await store.lookup(OTHER,'new')).state,'absent')
 assert.equal((await request(store,'/api/factory/workflows/missing/start',{workflow:{...workflow,workflowId:'missing',workflowType:'unknown'},execution})).body.error.code,'WORKFLOW_DEFINITION_NOT_FOUND'); assert.equal((await store.lookup(NS,'missing')).state,'absent')
 const ambiguous={resolveUnique:async()=>{throw Object.assign(new Error(),{code:'WORKFLOW_DEFINITION_AMBIGUOUS'})}}; assert.equal((await request(store,'/api/factory/workflows/amb/start',{workflow:{...workflow,workflowId:'amb'},execution},ambiguous)).body.error.code,'WORKFLOW_DEFINITION_AMBIGUOUS'); assert.equal((await store.lookup(NS,'amb')).state,'absent')
 const definition=await definitions.resolveUnique('bmad-story'); await store.publish(NS,{schemaVersion:'1',workflowId:'legacy',workflowType:'bmad-story',title:'Legacy',status:'ready',steps:[]},execution); assert.equal((await request(store,'/api/factory/workflows/legacy/start',{workflow:{...workflow,workflowId:'legacy'},execution})).body.error.code,'WORKFLOW_ALREADY_EXISTS')
 await store.start(NS,{...workflow,workflowId:'removed'},definition,execution); await store.remove(NS,'removed'); assert.equal((await request(store,'/api/factory/workflows/removed/start',{workflow:{...workflow,workflowId:'removed'},execution})).body.error.code,'WORKFLOW_REMOVED'); await store.purge(NS,'removed'); assert.equal((await request(store,'/api/factory/workflows/removed/start',{workflow:{...workflow,workflowId:'removed'},execution})).body.error.code,'WORKFLOW_REMOVED')
 assert.equal((await request(store,'/api/factory/workflows/no-execution/start',{workflow:{...workflow,workflowId:'no-execution'}})).body.error.code,'INVALID_EXECUTION'); assert.equal((await store.lookup(NS,'no-execution')).state,'absent')
} finally { await rm(root,{recursive:true,force:true}) }
