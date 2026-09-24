import { countTaskOutcomes } from '../lib/oracle.mjs'
import { executeOracle, oracleArtifact, oracleRootIdentity, validateOracleRoot } from '../lib/oracle-executor.mjs'
import { hashOracleDefinition } from '../lib/oracle-definition.mjs'
import { createHash } from 'node:crypto'
import { workflowProjectionStorageId } from '../lib/workflow-projection-store.mjs'
const sendError=(send,status,code,message)=>send(status,{error:{code,message}})
export async function handleWorkflowOracleRequest({method,path,readBody,send,projectionStore,evidenceStore,definitionRegistry,oracleRegistry,repoRoot,log=console}){
 const m=path.match(/^\/api\/factory\/workflows\/([^/]+)\/steps\/([^/]+)\/oracles\/([^/]+)\/runs$/);if(!m)return false
 if(method!=='POST'){sendError(send,405,'METHOD_NOT_ALLOWED','Only POST is supported.');return true}
 const workflowId=decodeURIComponent(m[1]),stepId=decodeURIComponent(m[2]),oracleId=decodeURIComponent(m[3]),body=await readBody()
 if(!body||typeof body!=='object'||Array.isArray(body)||Object.keys(body).some(k=>!['namespaceId','idempotencyKey'].includes(k))||typeof body.namespaceId!=='string'||(body.idempotencyKey!==undefined&&(typeof body.idempotencyKey!=='string'||!body.idempotencyKey||body.idempotencyKey.length>128))){sendError(send,400,'INVALID_ORACLE_RUN_REQUEST','Only namespaceId and optional idempotencyKey are accepted.');return true}
 try{
  if(!oracleRegistry){sendError(send,503,'ORACLE_REGISTRY_UNAVAILABLE','No trusted oracle registry is configured.');return true}
  let normalizedRoot;try{normalizedRoot=await validateOracleRoot(repoRoot)}catch{sendError(send,503,'INVALID_ORACLE_ROOT','The trusted oracle root is absent or invalid.');return true}
  const lookup=await projectionStore.lookup(body.namespaceId,workflowId);if(lookup.state!=='existing'){sendError(send,lookup.state==='absent'?404:410,lookup.state==='absent'?'WORKFLOW_NOT_FOUND':`WORKFLOW_${lookup.state.toUpperCase()}`,'Workflow is unavailable.');return true}const snapshot=lookup.snapshot
  if(snapshot.governanceMode!=='governed'||!snapshot.instance){sendError(send,409,'WORKFLOW_NOT_GOVERNED','Oracle runs require a governed workflow.');return true}
  const definition=await definitionRegistry.get(snapshot.instance.workflowType,snapshot.instance.definitionVersion),step=definition?.steps.find(s=>s.id===stepId),oracle=oracleRegistry.get(oracleId)
  if(!definition||definition.definitionHash!==snapshot.instance.definitionHash){sendError(send,409,'WORKFLOW_DEFINITION_MISMATCH','Exact definition unavailable.');return true}
  if(!step){sendError(send,404,'STEP_NOT_FOUND','Step not found.');return true}if(step.responsibility.kind!=='code'){sendError(send,409,'STEP_NOT_CODE','Only code steps are eligible.');return true}
  if(!oracle){sendError(send,404,'ORACLE_NOT_FOUND','Oracle not found.');return true}if(step.responsibility.name!==oracle.id||!oracle.applicable.workflowTypes.includes(definition.workflowType)||!oracle.applicable.stepIds.includes(stepId)){sendError(send,409,'ORACLE_NOT_APPLICABLE','Oracle is not applicable to this step.');return true}
  const oracleHash=hashOracleDefinition(oracle),rootHash=oracleRootIdentity(normalizedRoot),result=await executeOracle(oracle,{repoRoot:normalizedRoot,countTaskOutcomes}),artifact=oracleArtifact(result),facts={oracleId:oracle.id,oracleVersion:oracle.version,oracleHash,commandId:`${oracle.id}@${oracle.version}`,cwdId:rootHash,exitCode:result.exitCode??-1,signal:result.signal??'none',timedOut:result.timedOut,durationMs:result.durationMs,classification:result.classification,executed:result.counts.executed,fromCache:result.counts.fromCache,upToDate:result.counts.upToDate,skipped:result.counts.skipped,outputHash:artifact.hash,outputTruncated:result.stdout.truncated||result.stderr.truncated}
  const source={runtimeId:'factory-dashboard',kind:'factory-oracle',agentId:oracle.id},stored=await evidenceStore.record(body.namespaceId,workflowProjectionStorageId(body.namespaceId,workflowId),{workflowId,stepId,kind:'oracle-result',outcome:result.outcome,facts,...(body.idempotencyKey?{idempotencyKey:`oracle-run:${createHash('sha256').update(`${body.idempotencyKey}\0${oracleHash}\0${rootHash}`).digest('hex')}`}:{})},source)
  send(stored.created?201:200,{data:{workflowId,stepId,evidenceId:stored.evidence.evidenceId,created:stored.created,idempotent:stored.idempotent,outcome:result.outcome,classification:result.classification}})
 }catch(error){log.error?.('Oracle run failure',{workflowId,stepId,oracleId,code:error?.code});sendError(send,error?.code==='IDEMPOTENCY_KEY_COLLISION'?409:500,error?.code??'ORACLE_RUN_FAILURE','Oracle run failed.')}return true
}
