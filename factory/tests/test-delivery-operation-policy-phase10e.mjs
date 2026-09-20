import assert from 'node:assert/strict'
import { evaluateDeliveryOperationPolicy, resolveDeliveryVerificationRequest } from '../lib/delivery-operation-policy.mjs'
const d=`sha256:${'a'.repeat(64)}`, old=`sha256:${'b'.repeat(64)}`, c='c'.repeat(40), target={targetHash:`sha256:${'d'.repeat(64)}`,supportsRollback:true,verificationSuiteId:'smoke',verificationSuiteHash:d}, snapshot={revision:3,stage:'release-approved',headCommit:c}
const artifactRef={digest:d,sourceCommit:c},releaseRef={sourceCommit:c},deploymentRef={state:'succeeded',targetHash:target.targetHash,sourceCommit:c,artifactDigest:d}
const base={expectedRevision:3,kind:'deployment',artifactRef,releaseRef};assert.equal(evaluateDeliveryOperationPolicy({request:base,snapshot,target,identity:{targetHash:target.targetHash}}).allowed,true)
assert.equal(evaluateDeliveryOperationPolicy({request:{...base,expectedRevision:2},snapshot,target,identity:{targetHash:target.targetHash}}).code,'REVISION_CONFLICT')
assert.equal(evaluateDeliveryOperationPolicy({request:base,snapshot:{...snapshot,stage:'artifact-ready'},target,identity:{targetHash:target.targetHash}}).code,'RELEASE_NOT_APPROVED')
assert.equal(evaluateDeliveryOperationPolicy({request:base,snapshot,target,identity:{targetHash:'wrong'}}).code,'DELIVERY_TARGET_HASH_MISMATCH')
assert.equal(evaluateDeliveryOperationPolicy({request:base,snapshot,target,identity:{targetHash:target.targetHash},existingOperations:[{state:'indeterminate'}]}).code,'DELIVERY_OPERATION_INDETERMINATE')
const rollback={kind:'rollback',expectedRevision:3,deploymentRef,priorArtifactRef:{digest:old},approvedEvidenceId:'approval'};assert.equal(evaluateDeliveryOperationPolicy({request:rollback,snapshot,target,identity:{targetHash:target.targetHash}}).allowed,true)
assert.equal(evaluateDeliveryOperationPolicy({request:{...rollback,priorArtifactRef:{digest:d}},snapshot,target,identity:{targetHash:target.targetHash}}).code,'ROLLBACK_RELEASE_UNCHANGED')
assert.equal(evaluateDeliveryOperationPolicy({request:rollback,snapshot,target:{...target,supportsRollback:false},identity:{targetHash:target.targetHash}}).code,'ROLLBACK_NOT_SUPPORTED')
assert.equal(evaluateDeliveryOperationPolicy({request:{...rollback,approvedEvidenceId:undefined},snapshot,target,identity:{targetHash:target.targetHash}}).code,'ROLLBACK_APPROVAL_REQUIRED')
const verify={kind:'production-verification',expectedRevision:3,deploymentRef};assert.equal(evaluateDeliveryOperationPolicy({request:verify,snapshot:{...snapshot,stage:'deployed'},target,identity:{targetHash:target.targetHash}}).allowed,true);assert.equal(resolveDeliveryVerificationRequest(verify,target).value.verificationSuiteRef.suiteId,'smoke')
console.log('Phase 10E.1 delivery operation policy tests passed')
