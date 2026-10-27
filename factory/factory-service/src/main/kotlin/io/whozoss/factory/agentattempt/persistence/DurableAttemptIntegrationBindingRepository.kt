package io.whozoss.factory.agentattempt.persistence

import io.whozoss.factory.agentattempt.domain.DurableAttemptIntegrationBinding
import io.whozoss.factory.persistence.TenantScope

interface DurableAttemptIntegrationBindingRepository {
    fun bind(scope: TenantScope, binding: DurableAttemptIntegrationBinding): DurableAttemptIntegrationBinding

    fun find(
        scope: TenantScope,
        attemptId: String,
        serviceIdentityId: String,
    ): DurableAttemptIntegrationBinding?
}
