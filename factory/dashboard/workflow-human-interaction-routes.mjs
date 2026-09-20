import { createHash } from 'node:crypto'
import { workflowProjectionStorageId } from '../lib/workflow-projection-store.mjs'
import { WorkflowHumanInteractionError } from '../lib/workflow-human-interaction-store.mjs'
import { validateWorkflowEvidenceInput } from '../lib/workflow-evidence.mjs'
import { validateWorkflowTransitionRequest } from '../lib/workflow-transition-policy.mjs'
import { validateWorkflowNamespaceId } from './workflow-projection-routes.mjs'
const SAFE_ACTOR=/^[A-Za-z0-9][A-Za-z0-9._:@-]{0,255}$/
const sendError=(send,status,code,message)=>send(status,{error:{code,message}})
const interactionStatus=code=>code==='INTERACTION_NOT_FOUND'||code==='WORKFLOW_NOT_FOUND'?404:code==='UNAUTHENTICATED_ACTOR'?401:['INVALID_REPLY','ACTION_NOT_ALLOWED'].includes(code)?400:409
export async function handleWorkflowHumanInteractionRequest({method,path,url,readBody,send,projectionStore,interactionStore,evidenceStore,definitionRegistry,identity,notifier,log=console}){
 const list=path.match(/^\/api\/factory\/workflows\/([^/]+)\/interactions$/),reply=path.match(/^\/api\/factory\/workflows\/([^/]+)\/interactions\/([^/]+)\/reply$/);if(!list&&!reply)return false
 const workflowId=decodeURIComponent((list??reply)[1]),namespaceId=url.searchParams.get('namespaceId');if(!validateWorkflowNamespaceId(namespaceId)){sendError(send,400,'INVALID_NAMESPACE_ID','A valid namespaceId is required.');return true}
 let storageId;try{storageId=workflowProjectionStorageId(namespaceId,workflowId)}catch{sendError(send,400,'INVALID_WORKFLOW_ID','workflowId is invalid.');return true}
 if(list&&method==='GET')try{const lookup=await projectionStore.lookup(namespaceId,workflowId);if(lookup.state!=='existing'){sendError(send,lookup.state==='absent'?404:410,lookup.state==='absent'?'WORKFLOW_NOT_FOUND':`WORKFLOW_${lookup.state.toUpperCase()}`,'Workflow is unavailable.');return true}send(200,{data:{namespaceId,workflowId,items:await interactionStore.list(namespaceId,storageId,{openOnly:url.searchParams.get('state')!=='all'})}});return true}catch(cause){log.error?.('Human interaction read failure',{workflowId,code:cause?.code});sendError(send,500,'HUMAN_INTERACTION_FAILURE','Human interactions could not be read.');return true}
 if(reply&&method==='POST')try{
  const actorId=await identity.actorId();if(!SAFE_ACTOR.test(actorId??''))throw new WorkflowHumanInteractionError('UNAUTHENTICATED_ACTOR')
  const body=await readBody();if(!body||typeof body!=='object'||Array.isArray(body)||Object.keys(body).some(key=>!['expectedRevision','actionId','text'].includes(key))||!Number.isSafeInteger(body.expectedRevision)||body.expectedRevision<1||typeof body.actionId!=='string'||!body.actionId||(body.text!==undefined&&(typeof body.text!=='string'||body.text.length>2000)))throw new WorkflowHumanInteractionError('INVALID_REPLY')
  const interactionId=decodeURIComponent(reply[2]);const transaction=await interactionStore.transact(namespaceId,storageId,interactionId,async interaction=>{
   if(interaction.workflowId!==workflowId)throw new WorkflowHumanInteractionError('INTERACTION_SCOPE_MISMATCH');if(interaction.expectedRevision!==body.expectedRevision)throw new WorkflowHumanInteractionError('REVISION_CONFLICT')
   const action=interaction.actions.find(item=>item.id===body.actionId);if(!action)throw new WorkflowHumanInteractionError('ACTION_NOT_ALLOWED')
   const lookup=await projectionStore.lookup(namespaceId,workflowId);if(lookup.state!=='existing')throw new WorkflowHumanInteractionError('WORKFLOW_NOT_FOUND');const snapshot=lookup.snapshot
   if(snapshot.revision!==body.expectedRevision||snapshot.governanceMode!=='governed'||!snapshot.instance)throw new WorkflowHumanInteractionError('REVISION_CONFLICT')
   const step=snapshot.instance.steps.find(item=>item.id===interaction.stepId);if(step?.status!=='waiting_human')throw new WorkflowHumanInteractionError('INTERACTION_STALE')
   const validatedEvidence=validateWorkflowEvidenceInput({workflowId,stepId:interaction.stepId,kind:'human-decision',outcome:'pass',facts:{interactionId,actionId:body.actionId,...(body.text?{decisionTextHash:`sha256:${createHash('sha256').update(body.text).digest('hex')}`}:{})},idempotencyKey:`human:${interactionId}`},workflowId);if(!validatedEvidence.ok)throw new WorkflowHumanInteractionError('INVALID_REPLY')
   const source={kind:'factory-human',runtimeId:'factory-dashboard',actorId},recorded=await evidenceStore.record(namespaceId,storageId,validatedEvidence.value,source)
   const definition=await definitionRegistry.get(snapshot.instance.workflowType,snapshot.instance.definitionVersion);if(!definition||definition.definitionHash!==snapshot.instance.definitionHash)throw new WorkflowHumanInteractionError('WORKFLOW_DEFINITION_MISMATCH')
   const validatedTransition=validateWorkflowTransitionRequest({workflowId,stepId:interaction.stepId,expectedRevision:body.expectedRevision,requestedStatus:action.requestedStatus,evidenceIds:[recorded.evidence.evidenceId],idempotencyKey:`human-transition:${interactionId}`},workflowId);if(!validatedTransition.ok)throw new WorkflowHumanInteractionError('INVALID_INTERACTION')
   const transition=await projectionStore.transition(namespaceId,validatedTransition.value,definition,await evidenceStore.list(namespaceId,storageId),source);if(!transition.ok){const cause=new WorkflowHumanInteractionError(transition.error.code);cause.decision=transition.decision;throw cause}
   return{actorId,evidenceId:recorded.evidence.evidenceId,reply:{actionId:body.actionId,...(body.text?{text:body.text}:{})},transition}
  })
  if(transaction.transition.changed)notifier?.publish(namespaceId,{workflowId,namespaceId,revision:transaction.transition.snapshot.revision});send(200,{data:{workflowId,interactionId,actorId:transaction.interaction.actorId,evidenceId:transaction.interaction.evidenceId,revision:transaction.transition.snapshot.revision,projection:transaction.transition.snapshot.projection,runtimeNotification:'not-configured'}});return true
 }catch(cause){if(cause instanceof WorkflowHumanInteractionError){sendError(send,interactionStatus(cause.code),cause.code,cause.decision?.reason??cause.code);return true}log.error?.('Human interaction failure',{workflowId,code:cause?.code});sendError(send,500,'HUMAN_INTERACTION_FAILURE','Human interaction could not be processed.');return true}
 sendError(send,405,'METHOD_NOT_ALLOWED','Unsupported interaction operation.');return true
}
