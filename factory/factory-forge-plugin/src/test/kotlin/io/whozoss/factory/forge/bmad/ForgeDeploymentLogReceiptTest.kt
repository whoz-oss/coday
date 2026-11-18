package io.whozoss.factory.forge.bmad

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ForgeDeploymentLogReceiptTest {
    @Test
    fun `reads the release and target actually emitted by the deployment job`() {
        val log = """
            deploy to workstream\tUNKNOWN STEP\t2026-11-18T18:44:23Z   RELEASE_TAG: integration-talent-portal_rc100
            deploy to workstream\tUNKNOWN STEP\t2026-11-18T18:44:23Z   TARGET_ENVIRONMENT: forge-talent-portal
        """.trimIndent().replace("\\t", "\t")

        assertThat(ForgeDeploymentLogReceipt.parse("deploy to workstream", "success", log)).isEqualTo(
            ForgeDeploymentLogReceipt.Receipt("integration-talent-portal_rc100", "forge-talent-portal"),
        )
    }

    @Test
    fun `missing ambiguous or oversized job logs do not yield a receipt`() {
        val base = "RELEASE_TAG: integration-talent-portal_rc100\nTARGET_ENVIRONMENT: forge-talent-portal"
        fun receipt(log: String) = ForgeDeploymentLogReceipt.parse("deploy to workstream", "success", log)
        assertThat(receipt("RELEASE_TAG: integration-talent-portal_rc100")).isNull()
        assertThat(receipt(base + "\nRELEASE_TAG: integration-talent-portal_rc101")).isNull()
        assertThat(receipt(base + "\nTARGET_ENVIRONMENT: forge-other")).isNull()
        assertThat(receipt("x".repeat(8 * 1024 * 1024 + 1))).isNull()
        assertThat(ForgeDeploymentLogReceipt.parse("deploy to workstream", "failure", base)).isNull()
        assertThat(ForgeDeploymentLogReceipt.parse("deploy to openstack", "success", base)).isNull()
    }
}
