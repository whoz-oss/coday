const deny=(code,reason)=>({allowed:false,code,reason}), pass=()=>({allowed:true})
export function evaluateDeliveryOperationPolicy({request,snapshot,target,identity,existingOperations=[]}){
 if(!snapshot)return deny('DELIVERY_NOT_FOUND','delivery_not_found')
 if(snapshot.revision!==request.expectedRevision)return deny('REVISION_CONFLICT','stale_delivery_revision')
 if(!target)return deny('DELIVERY_TARGET_NOT_FOUND','trusted_target_missing')
 if(identity?.targetHash!==target.targetHash)return deny('DELIVERY_TARGET_HASH_MISMATCH','target_binding_mismatch')
 if(existingOperations.some(o=>o.state==='indeterminate'&&!o.resolvedOperationId))return deny('DELIVERY_OPERATION_INDETERMINATE','reconciliation_required')
 const head=snapshot.headCommit
 if(request.artifactRef&&request.artifactRef.sourceCommit!==head)return deny('SOURCE_COMMIT_MISMATCH','artifact_not_at_head')
 if(request.releaseRef&&request.releaseRef.sourceCommit!==head)return deny('SOURCE_COMMIT_MISMATCH','release_not_at_head')
 if(request.kind==='deployment'&&snapshot.stage!=='release-approved')return deny('RELEASE_NOT_APPROVED','release_approved_stage_required')
 if(request.kind==='production-verification'){if(snapshot.stage!=='deployed'||request.deploymentRef.state!=='succeeded')return deny('SUCCESSFUL_DEPLOYMENT_REQUIRED','linked_deployment_required');if(request.deploymentRef.targetHash!==target.targetHash||request.deploymentRef.sourceCommit!==head)return deny('DEPLOYMENT_SCOPE_MISMATCH','deployment_binding_mismatch');if(!target.verificationSuiteId||!target.verificationSuiteHash)return deny('VERIFICATION_SUITE_NOT_CONFIGURED','trusted_suite_required')}
 if(request.kind==='rollback'){if(request.deploymentRef.state!=='succeeded')return deny('SUCCESSFUL_DEPLOYMENT_REQUIRED','linked_deployment_required');if(!target.supportsRollback)return deny('ROLLBACK_NOT_SUPPORTED','target_disallows_rollback');if(!request.approvedEvidenceId)return deny('ROLLBACK_APPROVAL_REQUIRED','approval_evidence_required');if(request.priorArtifactRef.digest===request.deploymentRef.artifactDigest)return deny('ROLLBACK_RELEASE_UNCHANGED','prior_release_must_differ');if(request.deploymentRef.targetHash!==target.targetHash||request.deploymentRef.sourceCommit!==head)return deny('DEPLOYMENT_SCOPE_MISMATCH','deployment_binding_mismatch')}
 if(request.kind==='rollback-verification'){if(request.rollbackRef.state!=='succeeded')return deny('SUCCESSFUL_ROLLBACK_REQUIRED','linked_rollback_required');if(request.rollbackRef.targetHash!==target.targetHash)return deny('ROLLBACK_SCOPE_MISMATCH','rollback_binding_mismatch');if(!target.verificationSuiteId||!target.verificationSuiteHash)return deny('VERIFICATION_SUITE_NOT_CONFIGURED','trusted_suite_required')}
 return pass()
}
export function resolveDeliveryVerificationRequest(request,target){if(!target?.verificationSuiteId||!target?.verificationSuiteHash)return{ok:false,error:{code:'VERIFICATION_SUITE_NOT_CONFIGURED'}};return{ok:true,value:Object.freeze({...request,verificationSuiteRef:Object.freeze({suiteId:target.verificationSuiteId,suiteHash:target.verificationSuiteHash})})}}
