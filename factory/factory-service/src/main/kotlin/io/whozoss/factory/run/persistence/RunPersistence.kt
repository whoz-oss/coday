package io.whozoss.factory.run.persistence

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.run.domain.RunDecisionRecord
import io.whozoss.factory.run.domain.RunEscalationRecord
import io.whozoss.factory.run.domain.RunFindingRecord
import io.whozoss.factory.run.domain.RunFragmentRecord
import io.whozoss.factory.run.domain.RunRecord
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * Spring Data Neo4j projection of the parent `Run` entity (Lot 1, §4.1).
 */
@Node("FactoryRun")
data class RunNode(
    @Id
    val id: String,
    val organizationId: String,
    val workstreamId: String,
    val namespaceId: String,
    val runId: String,
    val runType: String,
    val selectorVersion: String,
    val title: String,
    val ticket: String? = null,
    val status: String,
    val revision: Int,
    val domainState: String = "{}",
    val domainRevision: Long = 0L,
    val amendmentSeq: Long = 0L,
    val rootCaseId: String? = null,
    val findings: String = "[]",
    val fragments: String = "[]",
    val loopCounts: String = "{}",
    val decisions: String = "{}",
    val flowVersions: String = "{}",
    val flowDefinitionHashes: String = "{}",
    val appliedValidationUpdates: String = "[]",
    val activeEscalation: String? = null,
    val maxTotalFragments: Int = RunRecord.DEFAULT_MAX_TOTAL_FRAGMENTS,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
) {
    fun toDomain(mapper: ObjectMapper): RunRecord = RunRecord(
        namespaceId = namespaceId,
        runId = runId,
        runType = runType,
        selectorVersion = selectorVersion,
        title = title,
        ticket = ticket,
        status = status,
        revision = revision,
        domainState = mapper.readValue(domainState, object : TypeReference<LinkedHashMap<String, Any?>>() {}),
        domainRevision = domainRevision,
        amendmentSeq = amendmentSeq,
        rootCaseId = rootCaseId,
        findings = mapper.readValue(findings, object : TypeReference<List<RunFindingRecord>>() {}),
        fragments = mapper.readValue(fragments, object : TypeReference<List<RunFragmentRecord>>() {}),
        loopCounts = mapper.readValue(loopCounts, object : TypeReference<LinkedHashMap<String, Int>>() {}),
        decisions = mapper.readValue(decisions, object : TypeReference<LinkedHashMap<String, RunDecisionRecord>>() {}),
        flowVersions = mapper.readValue(flowVersions, object : TypeReference<LinkedHashMap<String, String>>() {}),
        flowDefinitionHashes = mapper.readValue(flowDefinitionHashes, object : TypeReference<LinkedHashMap<String, String>>() {}),
        appliedValidationUpdates = mapper.readValue(appliedValidationUpdates, object : TypeReference<LinkedHashSet<String>>() {}),
        activeEscalation = activeEscalation?.takeIf { it.isNotBlank() }?.let {
            mapper.readValue(it, RunEscalationRecord::class.java)
        },
        maxTotalFragments = maxTotalFragments,
    )

    companion object {
        fun compositeId(
            organizationId: String,
            workstreamId: String,
            namespaceId: String,
            runId: String,
        ): String = "$organizationId|$workstreamId|$namespaceId|$runId"

        fun fromDomain(
            scope: TenantScope,
            record: RunRecord,
            mapper: ObjectMapper,
            createdAt: Instant = Instant.now(),
            updatedAt: Instant = Instant.now(),
        ): RunNode = RunNode(
            id = compositeId(scope.organizationId, scope.workstreamId, record.namespaceId, record.runId),
            organizationId = scope.organizationId,
            workstreamId = scope.workstreamId,
            namespaceId = record.namespaceId,
            runId = record.runId,
            runType = record.runType,
            selectorVersion = record.selectorVersion,
            title = record.title,
            ticket = record.ticket,
            status = record.status,
            revision = record.revision,
            domainState = mapper.writeValueAsString(record.domainState),
            domainRevision = record.domainRevision,
            amendmentSeq = record.amendmentSeq,
            rootCaseId = record.rootCaseId,
            findings = mapper.writeValueAsString(record.findings),
            fragments = mapper.writeValueAsString(record.fragments),
            loopCounts = mapper.writeValueAsString(record.loopCounts),
            decisions = mapper.writeValueAsString(record.decisions),
            flowVersions = mapper.writeValueAsString(record.flowVersions),
            flowDefinitionHashes = mapper.writeValueAsString(record.flowDefinitionHashes),
            appliedValidationUpdates = mapper.writeValueAsString(record.appliedValidationUpdates),
            activeEscalation = record.activeEscalation?.let { mapper.writeValueAsString(it) },
            maxTotalFragments = record.maxTotalFragments,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }
}

interface SpringDataNeo4jRunRepository : Neo4jRepository<RunNode, String> {

    @Query(
        """
        MATCH (r:FactoryRun)
        WHERE r.organizationId = ${'$'}organizationId
          AND r.workstreamId = ${'$'}workstreamId
          AND (${'$'}namespaceId IS NULL OR r.namespaceId = ${'$'}namespaceId)
        RETURN r
        ORDER BY r.createdAt DESC
        """,
    )
    fun findAllByScope(
        @Param("organizationId") organizationId: String,
        @Param("workstreamId") workstreamId: String,
        @Param("namespaceId") namespaceId: String?,
    ): List<RunNode>

    @Query(
        """
        MATCH (r:FactoryRun {id: ${'$'}id})
        WHERE r.revision = ${'$'}expectedRevision
        SET r.revision = ${'$'}revision,
            r.status = ${'$'}status,
            r.domainState = ${'$'}domainState,
            r.domainRevision = ${'$'}domainRevision,
            r.amendmentSeq = ${'$'}amendmentSeq,
            r.rootCaseId = COALESCE(${'$'}rootCaseId, r.rootCaseId),
            r.findings = ${'$'}findings,
            r.fragments = ${'$'}fragments,
            r.loopCounts = ${'$'}loopCounts,
            r.decisions = ${'$'}decisions,
            r.flowVersions = ${'$'}flowVersions,
            r.flowDefinitionHashes = ${'$'}flowDefinitionHashes,
            r.appliedValidationUpdates = ${'$'}appliedValidationUpdates,
            r.activeEscalation = ${'$'}activeEscalation,
            r.updatedAt = ${'$'}updatedAt
        RETURN count(r)
        """,
    )
    fun casUpdateRun(
        @Param("id") id: String,
        @Param("expectedRevision") expectedRevision: Int,
        @Param("revision") revision: Int,
        @Param("status") status: String,
        @Param("domainState") domainState: String,
        @Param("domainRevision") domainRevision: Long,
        @Param("amendmentSeq") amendmentSeq: Long,
        @Param("rootCaseId") rootCaseId: String?,
        @Param("findings") findings: String,
        @Param("fragments") fragments: String,
        @Param("loopCounts") loopCounts: String,
        @Param("decisions") decisions: String,
        @Param("flowVersions") flowVersions: String,
        @Param("flowDefinitionHashes") flowDefinitionHashes: String,
        @Param("appliedValidationUpdates") appliedValidationUpdates: String,
        @Param("activeEscalation") activeEscalation: String?,
        @Param("updatedAt") updatedAt: Instant,
    ): Long

    @Query(
        """
        MATCH (r:FactoryRun {id: ${'$'}id})
        WHERE r.rootCaseId IS NULL
        SET r.rootCaseId = ${'$'}rootCaseId,
            r.updatedAt = ${'$'}updatedAt
        RETURN count(r)
        """,
    )
    fun reserveRootCase(
        @Param("id") id: String,
        @Param("rootCaseId") rootCaseId: String,
        @Param("updatedAt") updatedAt: Instant,
    ): Long
}

/**
 * Persistence port for parent [RunRecord] entities.
 */
interface RunRepository {
    fun find(scope: TenantScope, namespaceId: String, runId: String): RunRecord?
    fun list(scope: TenantScope, namespaceId: String? = null): List<RunRecord>
    fun insert(scope: TenantScope, record: RunRecord): RunRecord
    fun update(scope: TenantScope, namespaceId: String, runId: String, expectedRevision: Int, next: RunRecord): Boolean
    fun reserveRootCase(scope: TenantScope, namespaceId: String, runId: String, candidateRootCaseId: String): String?
}

@Repository
class Neo4jRunRepository(
    private val runs: SpringDataNeo4jRunRepository,
    private val objectMapper: ObjectMapper,
) : RunRepository {

    override fun find(scope: TenantScope, namespaceId: String, runId: String): RunRecord? =
        runs.findById(RunNode.compositeId(scope.organizationId, scope.workstreamId, namespaceId, runId))
            .orElse(null)
            ?.takeIf { it.organizationId == scope.organizationId && it.workstreamId == scope.workstreamId }
            ?.toDomain(objectMapper)

    override fun list(scope: TenantScope, namespaceId: String?): List<RunRecord> =
        runs.findAllByScope(scope.organizationId, scope.workstreamId, namespaceId?.takeIf { it.isNotBlank() })
            .map { it.toDomain(objectMapper) }

    override fun insert(scope: TenantScope, record: RunRecord): RunRecord {
        val id = RunNode.compositeId(scope.organizationId, scope.workstreamId, record.namespaceId, record.runId)
        val existing = runs.findById(id).orElse(null)
        if (existing != null) {
            return existing.toDomain(objectMapper)
        }
        val saved = runs.save(RunNode.fromDomain(scope, record, objectMapper))
        return saved.toDomain(objectMapper)
    }

    override fun update(
        scope: TenantScope,
        namespaceId: String,
        runId: String,
        expectedRevision: Int,
        next: RunRecord,
    ): Boolean {
        val id = RunNode.compositeId(scope.organizationId, scope.workstreamId, namespaceId, runId)
        val count = runs.casUpdateRun(
            id = id,
            expectedRevision = expectedRevision,
            revision = next.revision,
            status = next.status,
            domainState = objectMapper.writeValueAsString(next.domainState),
            domainRevision = next.domainRevision,
            amendmentSeq = next.amendmentSeq,
            rootCaseId = next.rootCaseId,
            findings = objectMapper.writeValueAsString(next.findings),
            fragments = objectMapper.writeValueAsString(next.fragments),
            loopCounts = objectMapper.writeValueAsString(next.loopCounts),
            decisions = objectMapper.writeValueAsString(next.decisions),
            flowVersions = objectMapper.writeValueAsString(next.flowVersions),
            flowDefinitionHashes = objectMapper.writeValueAsString(next.flowDefinitionHashes),
            appliedValidationUpdates = objectMapper.writeValueAsString(next.appliedValidationUpdates),
            activeEscalation = next.activeEscalation?.let { objectMapper.writeValueAsString(it) },
            updatedAt = Instant.now(),
        )
        return count > 0
    }

    override fun reserveRootCase(
        scope: TenantScope,
        namespaceId: String,
        runId: String,
        candidateRootCaseId: String,
    ): String? {
        val id = RunNode.compositeId(scope.organizationId, scope.workstreamId, namespaceId, runId)
        runs.reserveRootCase(id, candidateRootCaseId, Instant.now())
        return find(scope, namespaceId, runId)?.rootCaseId
    }
}
