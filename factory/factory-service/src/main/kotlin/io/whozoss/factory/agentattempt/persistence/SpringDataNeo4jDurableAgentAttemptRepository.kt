package io.whozoss.factory.agentattempt.persistence

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query
import java.time.Instant

interface SpringDataNeo4jDurableAgentAttemptRepository : Neo4jRepository<DurableAgentAttemptNode, String> {
    @Query("""
        MERGE (a:DurableAgentAttempt {id:${'$'}id})
        ON CREATE SET a.organizationId=${'$'}organizationId,a.workstreamId=${'$'}workstreamId,a.namespaceId=${'$'}namespaceId,
          a.workflowId=${'$'}workflowId,a.stepId=${'$'}stepId,a.attemptId=${'$'}attemptId,a.caseId=${'$'}caseId,a.agentName=${'$'}agentName,
          a.attemptNumber=${'$'}attemptNumber,a.rootCaseId=${'$'}rootCaseId,a.parentCaseId=${'$'}parentCaseId,a.capabilityToken=${'$'}capabilityToken,
          a.turnCorrelation=${'$'}turnCorrelation,a.commandId=${'$'}commandId,a.brief=${'$'}brief,a.environmentRef=${'$'}environmentRef,
          a.expectedEnvironmentRevision=${'$'}expectedEnvironmentRevision,a.resumptionContext=${'$'}resumptionContext,
          a.contextEnvelope=${'$'}contextEnvelope,a.expectedAmendmentSeq=${'$'}expectedAmendmentSeq,a.status='pending',
          a.activeDurationMs=0,a.waitingDurationMs=0,a.revision=1,a.createdAt=${'$'}now,a.updatedAt=${'$'}now
        RETURN a
    """)
    fun register(id:String,organizationId:String,workstreamId:String,namespaceId:String,workflowId:String,stepId:String,attemptId:String,caseId:String,agentName:String,attemptNumber:Int,rootCaseId:String?,parentCaseId:String?,capabilityToken:String?,turnCorrelation:String?,commandId:String?,brief:String?,environmentRef:String?,expectedEnvironmentRevision:Int?,resumptionContext:String?,contextEnvelope:String?,expectedAmendmentSeq:Long?,now:Instant):DurableAgentAttemptNode

    @Query("""MATCH(a:DurableAgentAttempt) WHERE NOT a.status IN ['succeeded','failed','indeterminate','interrupted','superseded'] RETURN a ORDER BY a.updatedAt ASC LIMIT ${'$'}limit""")
    fun findNonTerminal(limit:Long):List<DurableAgentAttemptNode>
    @Query("""MATCH(a:DurableAgentAttempt) WHERE a.organizationId=${'$'}organizationId AND a.workstreamId=${'$'}workstreamId AND a.namespaceId=${'$'}namespaceId AND a.workflowId=${'$'}workflowId AND a.attemptId=${'$'}attemptId RETURN a""")
    fun findByAttemptId(organizationId:String,workstreamId:String,namespaceId:String,workflowId:String,attemptId:String):List<DurableAgentAttemptNode>
    @Query("""MATCH(a:DurableAgentAttempt) WHERE a.organizationId=${'$'}organizationId AND a.attemptId=${'$'}attemptId RETURN a""")
    fun findByOrganizationAndAttemptId(organizationId:String,attemptId:String):List<DurableAgentAttemptNode>
    @Query("""MATCH(a:DurableAgentAttempt) WHERE a.organizationId=${'$'}organizationId AND a.workstreamId=${'$'}workstreamId AND a.namespaceId=${'$'}namespaceId AND a.workflowId=${'$'}workflowId RETURN a""")
    fun findByWorkflowId(organizationId:String,workstreamId:String,namespaceId:String,workflowId:String):List<DurableAgentAttemptNode>
    @Query("""MATCH(a:DurableAgentAttempt) WHERE a.organizationId=${'$'}organizationId AND a.workstreamId=${'$'}workstreamId AND a.namespaceId=${'$'}namespaceId AND a.workflowId=${'$'}workflowId AND a.stepId=${'$'}stepId RETURN coalesce(max(a.attemptNumber),0) AS maxAttemptNumber""")
    fun maxAttemptNumber(organizationId:String,workstreamId:String,namespaceId:String,workflowId:String,stepId:String):Long

    @Query("""
      MATCH(a:DurableAgentAttempt{id:${'$'}id}) WHERE a.revision=${'$'}expectedRevision AND NOT a.status IN ['succeeded','failed','indeterminate','interrupted','superseded']
      WITH a,CASE WHEN a.status='running' AND a.activeSince IS NOT NULL THEN duration.between(a.activeSince,${'$'}now).seconds*1000 ELSE 0 END AS ad,
             CASE WHEN a.status='waiting_human' AND a.waitingSince IS NOT NULL THEN duration.between(a.waitingSince,${'$'}now).seconds*1000 ELSE 0 END AS wd
      SET a.status='interrupted',a.activeDurationMs=coalesce(a.activeDurationMs,0)+CASE WHEN ad<0 THEN 0 ELSE ad END,
          a.waitingDurationMs=coalesce(a.waitingDurationMs,0)+CASE WHEN wd<0 THEN 0 ELSE wd END,a.activeSince=null,a.waitingSince=null,
          a.ownerToken=${'$'}ownerToken,a.failureCode=${'$'}failureCode,a.completedAt=${'$'}now,a.updatedAt=${'$'}now,a.revision=a.revision+1 RETURN count(a) AS cancelled
    """)
    fun cancel(id:String,expectedRevision:Int,ownerToken:String,failureCode:String?,now:Instant):Long

    @Query("""
      MATCH(a:DurableAgentAttempt{id:${'$'}id}) WHERE a.revision=${'$'}expectedRevision AND a.status='waiting_human'
      WITH a,CASE WHEN a.waitingSince IS NOT NULL THEN duration.between(a.waitingSince,${'$'}now).seconds*1000 ELSE 0 END AS wd
      SET a.status='superseded',a.waitingDurationMs=coalesce(a.waitingDurationMs,0)+CASE WHEN wd<0 THEN 0 ELSE wd END,a.activeSince=null,a.waitingSince=null,
          a.ownerToken=${'$'}ownerToken,a.completedAt=${'$'}now,a.updatedAt=${'$'}now,a.revision=a.revision+1 RETURN count(a) AS superseded
    """)
    fun supersede(id:String,expectedRevision:Int,ownerToken:String,now:Instant):Long

    @Query("""
      MATCH(a:DurableAgentAttempt{id:${'$'}id}) WHERE a.status='pending' OR (a.status='claiming' AND a.ownerToken=${'$'}ownerToken) OR
        (a.leaseExpiresAt IS NOT NULL AND a.leaseExpiresAt<=${'$'}now AND NOT a.status IN ['succeeded','failed','indeterminate','interrupted','superseded'])
      WITH a,CASE WHEN a.status='running' AND a.activeSince IS NOT NULL THEN duration.between(a.activeSince,${'$'}now).seconds*1000 ELSE 0 END AS ad,
             CASE WHEN a.status='waiting_human' AND a.waitingSince IS NOT NULL THEN duration.between(a.waitingSince,${'$'}now).seconds*1000 ELSE 0 END AS wd
      SET a.status='claiming',a.activeDurationMs=coalesce(a.activeDurationMs,0)+CASE WHEN ad<0 THEN 0 ELSE ad END,
          a.waitingDurationMs=coalesce(a.waitingDurationMs,0)+CASE WHEN wd<0 THEN 0 ELSE wd END,a.activeSince=null,a.waitingSince=null,
          a.ownerToken=${'$'}ownerToken,a.leaseExpiresAt=${'$'}leaseExpiresAt,a.startedAt=coalesce(a.startedAt,${'$'}now),a.updatedAt=${'$'}now,a.revision=a.revision+1 RETURN count(a) AS claimed
    """)
    fun claim(id:String,ownerToken:String,leaseExpiresAt:Instant?,now:Instant):Long

    @Query("""
      MATCH(a:DurableAgentAttempt{id:${'$'}id}) WHERE a.ownerToken=${'$'}ownerToken AND NOT a.status IN ['succeeded','failed','indeterminate','interrupted','superseded']
      WITH a,a.status AS previousStatus,
        CASE WHEN a.status<>${'$'}status AND a.status='running' AND a.activeSince IS NOT NULL THEN duration.between(a.activeSince,${'$'}now).seconds*1000 ELSE 0 END AS ad,
        CASE WHEN a.status<>${'$'}status AND a.status='waiting_human' AND a.waitingSince IS NOT NULL THEN duration.between(a.waitingSince,${'$'}now).seconds*1000 ELSE 0 END AS wd
      SET a.status=${'$'}status,a.activeDurationMs=coalesce(a.activeDurationMs,0)+CASE WHEN ad<0 THEN 0 ELSE ad END,
          a.waitingDurationMs=coalesce(a.waitingDurationMs,0)+CASE WHEN wd<0 THEN 0 ELSE wd END,
          a.activeSince=CASE WHEN ${'$'}status='running' AND previousStatus<>'running' THEN ${'$'}now WHEN ${'$'}status='running' THEN a.activeSince ELSE null END,
          a.waitingSince=CASE WHEN ${'$'}status='waiting_human' AND previousStatus<>'waiting_human' THEN ${'$'}now WHEN ${'$'}status='waiting_human' THEN a.waitingSince ELSE null END,
          a.lastObservedEventId=coalesce(${'$'}lastObservedEventId,a.lastObservedEventId),a.updatedAt=${'$'}now,a.revision=a.revision+1 RETURN count(a) AS transitioned
    """)
    fun transition(id:String,ownerToken:String,status:String,lastObservedEventId:String?,now:Instant):Long

    @Query("""MATCH(a:DurableAgentAttempt{id:${'$'}id}) WHERE a.revision=${'$'}expectedRevision AND a.status IN ['running','waiting_human'] AND a.capabilityToken=${'$'}expectedCapabilityToken SET a.capabilityToken=${'$'}capabilityToken,a.updatedAt=${'$'}now,a.revision=a.revision+1 RETURN count(a) AS updated""")
    fun replaceCapability(id:String,expectedRevision:Int,expectedCapabilityToken:String,capabilityToken:String,now:Instant):Long

    @Query("""
      MATCH(a:DurableAgentAttempt{id:${'$'}id}) WHERE a.ownerToken=${'$'}ownerToken AND NOT a.status IN ['succeeded','failed','indeterminate','interrupted','superseded']
      WITH a,CASE WHEN a.status='running' AND a.activeSince IS NOT NULL THEN duration.between(a.activeSince,${'$'}now).seconds*1000 ELSE 0 END AS ad,
             CASE WHEN a.status='waiting_human' AND a.waitingSince IS NOT NULL THEN duration.between(a.waitingSince,${'$'}now).seconds*1000 ELSE 0 END AS wd
      SET a.status=${'$'}status,a.activeDurationMs=coalesce(a.activeDurationMs,0)+CASE WHEN ad<0 THEN 0 ELSE ad END,
          a.waitingDurationMs=coalesce(a.waitingDurationMs,0)+CASE WHEN wd<0 THEN 0 ELSE wd END,a.activeSince=null,a.waitingSince=null,
          a.failureCode=${'$'}failureCode,a.resultEvidenceId=${'$'}resultEvidenceId,a.lastObservedEventId=coalesce(${'$'}lastObservedEventId,a.lastObservedEventId),
          a.completedAt=${'$'}now,a.updatedAt=${'$'}now,a.revision=a.revision+1 RETURN count(a) AS finalized
    """)
    fun finalize(id:String,ownerToken:String,status:String,failureCode:String?,resultEvidenceId:String?,lastObservedEventId:String?,now:Instant):Long
}
