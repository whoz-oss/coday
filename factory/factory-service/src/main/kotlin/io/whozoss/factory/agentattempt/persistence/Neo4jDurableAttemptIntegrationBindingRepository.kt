package io.whozoss.factory.agentattempt.persistence

import io.whozoss.factory.agentattempt.domain.DurableAttemptIntegrationBinding
import io.whozoss.factory.agentattempt.domain.IdempotencyKeyCollisionException
import io.whozoss.factory.persistence.TenantScope
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class Neo4jDurableAttemptIntegrationBindingRepository(
    private val delegate: SpringDataNeo4jDurableAttemptIntegrationBindingRepository,
) : DurableAttemptIntegrationBindingRepository {
    @Transactional
    override fun bind(
        scope: TenantScope,
        binding: DurableAttemptIntegrationBinding,
    ): DurableAttemptIntegrationBinding {
        val node = delegate.bind(
            DurableAttemptIntegrationBindingNode.compositeId(scope, binding.attemptId, binding.serviceIdentityId),
            scope.organizationId,
            scope.workstreamId,
            binding.attemptId,
            binding.serviceIdentityId,
            binding.namespaceId,
            binding.workflowId,
            binding.stepId,
            binding.briefHash,
            binding.createdAt,
        )
        val persisted = node.toDomain()
        if (persisted != binding) {
            throw IdempotencyKeyCollisionException("Attempt integration binding conflicts with the persisted authorization")
        }
        return persisted
    }

    @Transactional(readOnly = true)
    override fun find(scope: TenantScope, attemptId: String, serviceIdentityId: String): DurableAttemptIntegrationBinding? =
        delegate.findById(DurableAttemptIntegrationBindingNode.compositeId(scope, attemptId, serviceIdentityId))
            .orElse(null)?.toDomain()
}
