import assert from 'node:assert/strict'
import { handleDeliveryOperationRequest } from '../dashboard/delivery-operation-routes.mjs'
import { DeliveryController } from '../lib/delivery-controller.mjs'

const identity=async()=>({namespaceId:'11111111-1111-4111-8111-111111111111',caseId:'22222222-2222-4222-8222-222222222222',actorId:'operator'})
const calls=[]
const controller=new Proxy({}, {get:(_target,name)=>async(...args)=>{calls.push([name,...args]);return{ok:false,status:503,error:{code:'DELIVERY_ADAPTER_NOT_CONFIGURED'}}}})
const routes=[['/deploy','deploy'],['/deployment/reconcile','reconcile'],['/verify','verify'],['/rollbacks','requestRollback'],['/rollbacks/rrq_1/approve','approveRollback'],['/rollbacks/rrq_1/execute','executeRollback'],['/rollbacks/rrq_1/verify','verifyRollback']]
for(const [suffix,method] of routes){let reply;assert.equal(await handleDeliveryOperationRequest({method:'POST',path:`/api/factory/workflows/wf/delivery${suffix}`,readBody:async()=>({}),send:(status,body)=>reply={status,body},identity,controller}),true);assert.equal(calls.at(-1)[0],method);assert.equal(reply.status,503)}
let reply
await handleDeliveryOperationRequest({method:'GET',path:'/api/factory/workflows/wf/delivery/deploy',readBody:async()=>({}),send:(status,body)=>reply={status,body},identity,controller})
assert.equal(reply.status,405)
assert.equal(await handleDeliveryOperationRequest({method:'POST',path:'/api/factory/workflows/wf/delivery/promote',readBody:async()=>({}),send(){},identity,controller}),false)

const evidenceController=Object.create(DeliveryController.prototype)
evidenceController.resolve=async()=>{throw new Error('must reject before mutation')}
for(const [kind,sourceKind] of [['deployment-result','factory-build'],['smoke-result','factory-smoke'],['rollback-result','factory-deploy']]){const result=await evidenceController.recordEvidence({},'wf',{kind},sourceKind);assert.equal(result.status,403);assert.equal(result.error.code,'DELIVERY_EVIDENCE_AUTHORITY_FORBIDDEN')}
console.log('Lot 2 delivery operation API source tests passed')
