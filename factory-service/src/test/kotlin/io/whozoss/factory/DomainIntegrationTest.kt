package io.whozoss.factory

import io.whozoss.factory.persistence.TenantScope
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Base class for the workunit/lease/worker/environment integration tests.
 *
 * Seeds the tenant rows the V6 composite foreign keys require
 * (`organizations` -> `workstreams`) and truncates the control-plane tables
 * before each test so every test starts from a clean, scoped slate.
 *
 * The seeded tenant is exactly the one the loopback-dev membership resolves to
 * (`org-local-dev` / `ws-default`), so HTTP tests need no extra credentials.
 */
abstract class DomainIntegrationTest : PostgresContainerSpec() {

    @Autowired
    protected lateinit var jdbcTemplate: JdbcTemplate

    protected val scope: TenantScope
        get() = TenantScope(ORGANIZATION_ID, WORKSTREAM_ID)

    @BeforeEach
    fun resetControlPlane() {
        jdbcTemplate.update("DELETE FROM work_unit_leases WHERE organization_id = ?", ORGANIZATION_ID)
        jdbcTemplate.update("DELETE FROM work_units WHERE organization_id = ?", ORGANIZATION_ID)
        jdbcTemplate.update("DELETE FROM work_environments WHERE organization_id = ?", ORGANIZATION_ID)
        jdbcTemplate.update("DELETE FROM workers WHERE organization_id = ?", ORGANIZATION_ID)
        jdbcTemplate.update(
            "INSERT INTO organizations (organization_id, name) VALUES (?, ?) ON CONFLICT DO NOTHING",
            ORGANIZATION_ID,
            "Local Dev",
        )
        jdbcTemplate.update(
            "INSERT INTO workstreams (organization_id, workstream_id, name) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
            ORGANIZATION_ID,
            WORKSTREAM_ID,
            "Default",
        )
    }

    companion object {
        const val ORGANIZATION_ID = "org-local-dev"
        const val WORKSTREAM_ID = "ws-default"
    }
}
