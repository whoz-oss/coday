const ROUTE = /^\/api\/factory\/workflows\/([^/]+)\/delivery(?:\/(deploy|verify|deployment\/reconcile|rollbacks)(?:\/([^/]+)\/(approve|execute|verify))?)$/
export async function handleDeliveryOperationRequest({method,path,readBody,send,identity,controller,log=console}){
 const match=path.match(ROUTE);if(!match)return false
 try{const trust=await identity();if(!trust){send(401,{error:{code:'TRUST_CONTEXT_UNAVAILABLE'}});return true}const workflowId=decodeURIComponent(match[1]),action=match[2],requestId=match[3],sub=match[4];let result
 if(method!=='POST')result={ok:false,status:405,error:{code:'METHOD_NOT_ALLOWED'}}
 else if(action==='deploy')result=await controller.deploy(trust,workflowId,await readBody())
 else if(action==='verify')result=await controller.verify(trust,workflowId,await readBody())
 else if(action==='deployment/reconcile')result=await controller.reconcile(trust,workflowId,await readBody())
 else if(action==='rollbacks'&&!requestId)result=await controller.requestRollback(trust,workflowId,await readBody())
 else if(sub==='approve')result=await controller.approveRollback(trust,workflowId,decodeURIComponent(requestId),await readBody())
 else if(sub==='execute')result=await controller.executeRollback(trust,workflowId,decodeURIComponent(requestId),await readBody())
 else if(sub==='verify')result=await controller.verifyRollback(trust,workflowId,decodeURIComponent(requestId),await readBody())
 else result={ok:false,status:405,error:{code:'METHOD_NOT_ALLOWED'}}
 send(result.status??(result.ok?200:409),result.ok?{data:result.data}:{error:result.error});return true
 }catch(error){log.error('Delivery operation control-plane failure',{code:error?.code??'UNEXPECTED'});send(500,{error:{code:'DELIVERY_OPERATION_CONTROL_PLANE_FAILURE'}});return true}
}
