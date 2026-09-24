import { validateWorkflowEvidenceInput } from '../lib/workflow-evidence.mjs'
import { WorkflowEvidenceStoreError } from '../lib/workflow-evidence-store.mjs'
import { workflowProjectionStorageId } from '../lib/workflow-projection-store.mjs'
import { validateWorkflowNamespaceId, sanitizeWorkflowExecution } from './workflow-projection-routes.mjs'

const SAFE_ID=/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/
const sendError=(send,status,code,message)=>send(status,{error:{code,message}})
export async function handleWorkflowEvidenceRequest({method,path,url,readBody,send,projectionStore,evidenceStore,definitionRegistry,log=console}){
 const match=path.match(/^\/api\/factory\/workflows\/([^/]+)\/evidence$/); if(!match) return false
 const workflowId=decodeURIComponent(match[1])
 if(method==='GET'){
  const namespaceId=url.searchParams.get('namespaceId'),stepId=url.searchParams.get('stepId')??undefined
  if(!validateWorkflowNamespaceId(namespaceId)) { sendError(send,400,'INVALID_NAMESPACE_ID','A valid namespaceId query parameter is required.'); return true }
  if(stepId&&!SAFE_ID.test(stepId)){sendError(send,400,'INVALID_STEP_ID','stepId is invalid.');return true}
  const lookup=await projectionStore.lookup(namespaceId,workflowId); if(lookup.state!=='existing'){sendError(send,lookup.state==='absent'?404:410,lookup.state==='absent'?'WORKFLOW_NOT_FOUND':`WORKFLOW_${lookup.state.toUpperCase()}`,'Workflow is unavailable.');return true}
  send(200,{data:{namespaceId,workflowId,items:await evidenceStore.list(namespaceId,workflowProjectionStorageId(namespaceId,workflowId),{stepId})}});return true
 }
 if(method==='POST'){
  const body=await readBody(); if(!body||typeof body!=='object'||Array.isArray(body)||Object.keys(body).some(k=>!['evidence','execution'].includes(k))){sendError(send,400,'INVALID_REQUEST','Request body must contain evidence and execution.');return true}
  const execution=sanitizeWorkflowExecution(body.execution); if(!execution.ok){sendError(send,400,execution.code,'Execution attribution is invalid.');return true}
  const validation=validateWorkflowEvidenceInput(body.evidence,workflowId); if(!validation.ok){sendError(send,400,validation.error.code,'Evidence is invalid.');return true}
  if(['oracle-result','human-decision'].includes(validation.value.kind)){sendError(send,403,'FACTORY_ONLY_EVIDENCE',`${validation.value.kind} evidence is produced only by a trusted Factory control-plane route.`);return true}
  try { const lookup=await projectionStore.lookup(execution.namespaceId,workflowId); if(lookup.state!=='existing'){sendError(send,lookup.state==='absent'?404:410,lookup.state==='absent'?'WORKFLOW_NOT_FOUND':`WORKFLOW_${lookup.state.toUpperCase()}`,'Workflow is unavailable.');return true}; const snapshot=lookup.snapshot
   if(snapshot.governanceMode!=='governed'||!snapshot.instance){sendError(send,409,'DECLARATIVE_WORKFLOW','Evidence requires a governed workflow.');return true}
   const definition=await definitionRegistry.get(snapshot.instance.workflowType,snapshot.instance.definitionVersion); if(!definition){sendError(send,409,'WORKFLOW_DEFINITION_NOT_FOUND','The exact workflow definition is unavailable.');return true}
   if(definition.workflowType!==snapshot.instance.workflowType||definition.version!==snapshot.instance.definitionVersion||definition.definitionHash!==snapshot.instance.definitionHash){sendError(send,409,'WORKFLOW_DEFINITION_MISMATCH','The exact workflow definition identity does not match.');return true}
   if(!definition.steps.some(step=>step.id===validation.value.stepId)){sendError(send,400,'UNKNOWN_STEP','stepId is not part of the referenced definition.');return true}
   const result=await evidenceStore.record(execution.namespaceId,workflowProjectionStorageId(execution.namespaceId,workflowId),validation.value,execution.controllerExecution)
   send(result.created?201:200,{data:{namespaceId:execution.namespaceId,workflowId,created:result.created,idempotent:result.idempotent,evidence:result.evidence}})
  } catch(error){if(error instanceof WorkflowEvidenceStoreError&&error.code==='IDEMPOTENCY_KEY_COLLISION')sendError(send,409,error.code,'The idempotency key was already used for different evidence.');else{log.error('Workflow evidence storage failure',{workflowId,code:error?.code});sendError(send,500,'EVIDENCE_STORAGE_FAILURE','Evidence storage is unavailable.')}} return true
 }
 sendError(send,405,'METHOD_NOT_ALLOWED','Evidence is append-only.');return true
}
