import assert from 'node:assert/strict'
import { mkdtemp, mkdir, realpath, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { DeliveryStore } from '../lib/delivery-store.mjs'

const ns='11111111-1111-4111-8111-111111111111', caseId='22222222-2222-4222-8222-222222222222', env='33333333-3333-4333-8333-333333333333', sha='a'.repeat(40), digest=`sha256:${'b'.repeat(64)}`, targetHash=`sha256:${'c'.repeat(64)}`
const root=await mkdtemp(join(tmpdir(),'delivery-operation-recovery-'))
try {
 const repo=join(root,'repo'); await mkdir(repo); const path=await realpath(repo), data=join(root,'data'); const seed=new DeliveryStore(data); await seed.initialize(); const now=new Date().toISOString()
 await seed.create({schemaVersion:'1',deliveryId:'delivery',namespaceId:ns,workflowId:'workflow',environmentId:env,environmentHash:digest,parentCaseId:caseId,runtimeId:'factory',worktreePath:path,branch:'feature/test',baseCommit:sha,headCommit:sha,definitionType:'factory-delivery',definitionVersion:'1.0.0',definitionHash:digest,stage:'release-approved',revision:1,evidenceIds:[],createdAt:now,updatedAt:now,git:{},artifact:{},release:{},deployment:{},verification:{},blockers:[]})
 const request={kind:'deployment',expectedRevision:1,idempotencyKey:'recoverable',targetId:'prod',artifactRef:{digest,mediaType:'application/zip',producerRef:'build',buildRef:'build-1',sourceCommit:sha},releaseRef:{releaseId:'release-1',artifactDigest:digest,sourceCommit:sha,approvedEvidenceId:'approval'}}
 const args={namespaceId:ns,workflowId:'workflow',deliveryId:'delivery',caseId,runtimeId:'factory',request,targetRef:{targetId:'prod',targetHash},execution:{kind:'factory-control-plane'}}
 let crashed=false; const faulted=new DeliveryStore(data,{fault:async point=>{if(point==='after-delivery-operation-write')throw new Error('CRASH')}})
 try { await faulted.createDeliveryOperation(args) } catch { crashed=true } assert.equal(crashed,true)
 const recovered=new DeliveryStore(data); await recovered.initialize(); const projection=await recovered.inspectDeliveryOperations(ns,'delivery'); assert.equal(projection.operations[0].state,'pending'); assert.equal((await recovered.createDeliveryOperation(args)).changed,false)
 await recovered.startDeliveryOperation(ns,'delivery',projection.operations[0].operationId,'adapter-correlation'); assert.equal((await new DeliveryStore(data).inspectDeliveryOperations(ns,'delivery')).operations[0].state,'running')
 assert.equal((await recovered.createDeliveryOperation({...args,request:{...request,idempotencyKey:'blocked'}})).ok,true)
 await recovered.recordDeliveryOperation(ns,'delivery',projection.operations[0].operationId,{state:'indeterminate',error:{code:'CRASH_WINDOW'}}); assert.equal(await new DeliveryStore(data).hasIndeterminateOperation(ns,'delivery'),true)
 assert.equal((await recovered.createDeliveryOperation({...args,request:{...request,idempotencyKey:'must-reconcile'}})).error.code,'DELIVERY_OPERATION_INDETERMINATE')
} finally { await rm(root,{recursive:true,force:true}) }
