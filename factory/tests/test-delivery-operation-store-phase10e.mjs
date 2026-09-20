import assert from 'node:assert/strict'
import { mkdtemp, mkdir, realpath, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { DeliveryStore } from '../lib/delivery-store.mjs'

const ns='11111111-1111-4111-8111-111111111111', caseId='22222222-2222-4222-8222-222222222222', env='33333333-3333-4333-8333-333333333333', sha='a'.repeat(40), digest=`sha256:${'b'.repeat(64)}`, targetHash=`sha256:${'c'.repeat(64)}`
const root=await mkdtemp(join(tmpdir(),'delivery-operations-'))
try {
 const repo=join(root,'repo'); await mkdir(repo); const path=await realpath(repo); const store=new DeliveryStore(join(root,'data')); await store.initialize()
 const now=new Date().toISOString(), snapshot={schemaVersion:'1',deliveryId:'delivery',namespaceId:ns,workflowId:'workflow',environmentId:env,environmentHash:digest,parentCaseId:caseId,runtimeId:'factory',worktreePath:path,branch:'feature/test',baseCommit:sha,headCommit:sha,definitionType:'factory-delivery',definitionVersion:'1.0.0',definitionHash:digest,stage:'release-approved',revision:3,evidenceIds:[],createdAt:now,updatedAt:now,git:{},artifact:{},release:{},deployment:{},verification:{},blockers:[]}
 assert.equal((await store.create(snapshot)).ok,true)
 const request={kind:'deployment',expectedRevision:3,idempotencyKey:'deploy-once',targetId:'prod',artifactRef:{digest,mediaType:'application/zip',producerRef:'build',buildRef:'build-1',sourceCommit:sha},releaseRef:{releaseId:'release-1',artifactDigest:digest,sourceCommit:sha,approvedEvidenceId:'approval'}}
 const args={namespaceId:ns,workflowId:'workflow',deliveryId:'delivery',caseId,runtimeId:'factory',request,targetRef:{targetId:'prod',targetHash,adapterId:'test',adapterTargetRef:'trusted'},execution:{kind:'factory-control-plane',actorId:'factory'}}
 const created=await store.createDeliveryOperation(args); assert.equal(created.operation.state,'pending'); assert.equal((await store.createDeliveryOperation(args)).changed,false)
 assert.equal((await store.createDeliveryOperation({...args,request:{...request,artifactRef:{...request.artifactRef,buildRef:'other'}}})).error.code,'IDEMPOTENCY_KEY_COLLISION')
 assert.equal((await store.createDeliveryOperation({...args,request:{...request,idempotencyKey:'stale',expectedRevision:2}})).error.code,'REVISION_CONFLICT')
 const started=await store.startDeliveryOperation(ns,'delivery',created.operation.operationId,'adapter-1'); assert.equal(started.operation.attempt,1)
 assert.equal((await store.startDeliveryOperation(ns,'delivery',created.operation.operationId,'adapter-1')).error.code,'INVALID_DELIVERY_OPERATION_TRANSITION')
 const indeterminate=await store.recordDeliveryOperation(ns,'delivery',created.operation.operationId,{state:'indeterminate',error:{code:'LOST_RESPONSE'}}); assert.equal(indeterminate.ok,true); assert.equal(await store.hasIndeterminateOperation(ns,'delivery'),true)
 const resolved=await store.reconcileDeliveryOperation(ns,'delivery',created.operation.operationId,{state:'succeeded',result:{deploymentId:'d1'}}); assert.equal(resolved.ok,true); assert.equal(await store.hasIndeterminateOperation(ns,'delivery'),false)
 const projection=await store.inspectDeliveryOperations(ns,'delivery'); assert.equal(projection.history.length,4); assert.equal(projection.operations[0].state,'succeeded')
 const read=await store.readWithOperations(ns,'delivery'); assert.equal(read.revision,3); assert.equal(read.deliveryOperations[0].state,'succeeded'); assert.equal((await store.read(ns,'delivery')).deliveryOperations,undefined)
 const concurrent=await Promise.all([store.createDeliveryOperation({...args,request:{...request,idempotencyKey:'parallel'}}),store.createDeliveryOperation({...args,request:{...request,idempotencyKey:'parallel'}})]); assert.equal(concurrent.filter(x=>x.changed).length,1)
} finally { await rm(root,{recursive:true,force:true}) }
