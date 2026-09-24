import { validateWorkflowTransitionRequest } from '../lib/workflow-transition-policy.mjs'
import { workflowProjectionStorageId } from '../lib/workflow-projection-store.mjs'
const sendError=(send,status,code,message)=>send(status,{error:{code,message}})
export async function handleWorkflowCodeTransitionRequest({method,path,readBody,send,store,evidenceStore,definitionRegistry,namespaceId,notifier,log=console}){
 const match=path.match(/^\/api\/factory\/workflows\/([^/]+)\/code-transitions$/);if(!match)return false
 if(method!=='POST'){sendError(send,405,'METHOD_NOT_ALLOWED','Only POST is supported.');return true}
 if(!namespaceId){sendError(send,503,'FACTORY_CONTROL_PLANE_UNAVAILABLE','A trusted Factory namespace is not configured.');return true}
 const workflowId=decodeURIComponent(match[1]),body=await readBody()
 if(!body||typeof body!=='object'||Array.isArray(body)||Object.keys(body).some(key=>key!=='transition')){sendError(send,400,'INVALID_REQUEST','Only transition is accepted.');return true}
 const validated=validateWorkflowTransitionRequest(body.transition,workflowId);if(!validated.ok){sendError(send,400,validated.error.code,'Transition request is invalid.');return true}
 const execution={namespaceId,runtimeId:'factory-dashboard',kind:'factory-oracle',agentId:'factory-oracle'}
 try{const lookup=await store.lookup(namespaceId,workflowId);if(lookup.state!=='existing'){sendError(send,lookup.state==='absent'?404:410,lookup.state==='absent'?'WORKFLOW_NOT_FOUND':`WORKFLOW_${lookup.state.toUpperCase()}`,'Workflow is unavailable.');return true}
  const definition=await definitionRegistry.get(lookup.snapshot.instance?.workflowType,lookup.snapshot.instance?.definitionVersion),evidence=await evidenceStore.list(namespaceId,workflowProjectionStorageId(namespaceId,workflowId)),result=await store.transition(namespaceId,validated.value,definition,evidence,execution)
  if(!result.ok){sendError(send,409,result.error.code,result.decision?.reason??'Transition rejected.');return true}if(result.changed)notifier?.publish(namespaceId,{workflowId,namespaceId,revision:result.snapshot.revision});send(200,{data:{workflowId,revision:result.snapshot.revision,changed:result.changed,idempotent:result.idempotent,projection:result.snapshot.projection}})
 }catch(error){log.error?.('Code transition failure',{workflowId,code:error?.code});sendError(send,500,'WORKFLOW_STORAGE_FAILURE','Transition storage is unavailable.')}return true
}
