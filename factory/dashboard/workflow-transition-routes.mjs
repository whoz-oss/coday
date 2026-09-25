import { validateWorkflowTransitionRequest } from '../lib/workflow-transition-policy.mjs'
import { sanitizeWorkflowExecution } from './workflow-projection-routes.mjs'
import { workflowProjectionStorageId } from '../lib/workflow-projection-store.mjs'
import { sendError } from './http-utils.mjs'
const statusFor=(code)=>code==='WORKFLOW_NOT_FOUND'?404:['WORKFLOW_REMOVED','WORKFLOW_PURGED'].includes(code)?410:['INVALID_TRANSITION_REQUEST','UNTRUSTED_REQUEST_ID'].includes(code)?400:409
export async function handleWorkflowTransitionRequest({method,path,readBody,send,store,evidenceStore,definitionRegistry,notifier,log=console}){
 const match=path.match(/^\/api\/factory\/workflows\/([^/]+)\/transitions$/);if(!match)return false
 if(method!=='POST'){sendError(send,405,'METHOD_NOT_ALLOWED','Only POST is supported for workflow transitions.');return true}
 const workflowId=decodeURIComponent(match[1]),body=await readBody()
 if(!body||typeof body!=='object'||Array.isArray(body)||Object.keys(body).some(k=>!['transition','execution'].includes(k))){sendError(send,400,'INVALID_REQUEST','Request body must contain transition and execution.');return true}
 const execution=sanitizeWorkflowExecution(body.execution);if(!execution.ok){sendError(send,400,execution.code,'Execution attribution is invalid.');return true}
 const validated=validateWorkflowTransitionRequest(body.transition,workflowId);if(!validated.ok){sendError(send,400,validated.error.code,'Transition request is invalid.');return true}
 try{
  const lookup=await store.lookup(execution.namespaceId,workflowId)
  if(lookup.state!=='existing'){sendError(send,lookup.state==='absent'?404:410,lookup.state==='absent'?'WORKFLOW_NOT_FOUND':`WORKFLOW_${lookup.state.toUpperCase()}`,'Workflow is not active.');return true}
  const definition=await definitionRegistry.get(lookup.snapshot.instance?.workflowType,lookup.snapshot.instance?.definitionVersion)
  const evidence=await evidenceStore.list(execution.namespaceId,workflowProjectionStorageId(execution.namespaceId,workflowId))
  const result=await store.transition(execution.namespaceId,validated.value,definition,evidence,execution.controllerExecution)
  if(!result.ok){sendError(send,statusFor(result.error.code),result.error.code,result.decision?.reason??'Transition rejected.');return true}
  if(result.changed)notifier?.publish(execution.namespaceId,{workflowId,namespaceId:execution.namespaceId,revision:result.snapshot.revision})
  send(200,{data:{workflowId,requestId:result.requestId,revision:result.snapshot.revision,changed:result.changed,idempotent:result.idempotent,projection:result.snapshot.projection}})
 }catch(error){log.error?.('Workflow transition storage failure',{workflowId,code:error?.code??'UNEXPECTED'});sendError(send,500,'WORKFLOW_STORAGE_FAILURE','Workflow transition storage is unavailable.')}
 return true
}
