import assert from 'node:assert/strict'
import { mkdtemp } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { DeliveryStore } from '../lib/delivery-store.mjs'

const root=await mkdtemp(join(tmpdir(),'factory-lot2-'))
const store=new DeliveryStore(root);await store.initialize()
const namespaceId='11111111-1111-4111-8111-111111111111',caseId='22222222-2222-4222-8222-222222222222',environmentId='33333333-3333-4333-8333-333333333333',deliveryId='wf-delivery',workflowId='wf',runtimeId='factory-dashboard',hash='sha256:'+'a'.repeat(64),commit='b'.repeat(40)
await store.create({schemaVersion:'1',deliveryId,namespaceId,workflowId,environmentId,environmentHash:hash,parentCaseId:caseId,runtimeId,worktreePath:'/tmp/w',branch:'feature/test',baseCommit:commit,headCommit:commit,definitionType:'delivery',definitionVersion:'1',definitionHash:hash,stage:'deployed',revision:1,evidenceIds:[],createdAt:new Date().toISOString(),updatedAt:new Date().toISOString(),git:{},artifact:{},release:{},deployment:{},verification:{},blockers:[]})
const request={rollbackRequestId:'rrq_123',expectedRevision:1,idempotencyKey:'rollback-1',targetId:'prod',targetHash:hash,deploymentRef:{operationId:'dop_1'},priorArtifactRef:{digest:hash},priorReleaseRef:{releaseId:'old'},reasonCode:'bad-release',reason:'bounded explanation',scopeHash:hash,semanticHash:'sha256:'+'c'.repeat(64)}
const execution={kind:'factory-control-plane',namespaceId,workflowId,caseId,runtimeId,actorId:'alice'}
let result=await store.createRollbackRequest({namespaceId,deliveryId,workflowId,caseId,runtimeId,request,execution});assert.equal(result.changed,true);assert.equal(result.request.status,'requested')
result=await store.createRollbackRequest({namespaceId,deliveryId,workflowId,caseId,runtimeId,request,execution});assert.equal(result.idempotent,true)
result=await store.createRollbackRequest({namespaceId,deliveryId,workflowId,caseId,runtimeId,request:{...request,semanticHash:hash},execution});assert.equal(result.error.code,'IDEMPOTENCY_KEY_COLLISION')
result=await store.approveRollbackRequest(namespaceId,deliveryId,request.rollbackRequestId,{expectedRevision:1,idempotencyKey:'approve-1',execution});assert.equal(result.request.status,'approved');assert.equal(result.request.approvedBy.actorId,'alice')
result=await store.approveRollbackRequest(namespaceId,deliveryId,request.rollbackRequestId,{expectedRevision:1,idempotencyKey:'approve-1',execution});assert.equal(result.idempotent,true)
result=await store.approveRollbackRequest(namespaceId,deliveryId,request.rollbackRequestId,{expectedRevision:1,idempotencyKey:'approve-2',execution});assert.equal(result.error.code,'ROLLBACK_REQUEST_ALREADY_DECIDED')
const projection=await store.inspectDeliveryOperations(namespaceId,deliveryId);assert.equal(projection.rollbackRequests[0].status,'approved');assert.equal(projection.rollbackRequestHistory.length,2);assert.equal((await store.read(namespaceId,deliveryId)).revision,1)
console.log('Lot 2 durable rollback source tests passed')
