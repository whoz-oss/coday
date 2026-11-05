package io.whozoss.factory.adapter.agentos

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class VerdictDeriverRuntimeProofTest {
    private val context = VerdictDeriver.DerivationContext("case-1")
    private val positioned = HighWaterMark("2026-01-01T00:00:00Z", "base")
    private val empty = HighWaterMark(null, null)

    @Test fun `old idle covered by baseline yields no proof`() {
        val result = derive(listOf(status("old", "case-1", "2025-12-31T23:59:59Z", "IDLE")), positioned)
        assertThat(result.runtimeProof).isNull()
        assertThat(result.verdict).isNull()
    }

    @Test fun `terminal event of another case and transient event yield no proof`() {
        val result = derive(listOf(status("other", "case-2", "2026-01-01T00:00:01Z", "IDLE"), CaseEventView("transient","ThinkingEvent","case-1","2026-01-01T00:00:02Z",emptyMap())), positioned)
        assertThat(result.runtimeProof).isNull()
    }

    @Test fun `fresh idle proves only current case while business verdict remains indeterminate`() {
        val result = derive(listOf(status("fresh", "case-1", "2026-01-01T00:00:01Z", "IDLE")), positioned)
        assertThat(result.runtimeProof).isEqualTo(VerdictDeriver.RuntimeCaseTerminationProof("case-1","attempt-1","fresh","IDLE"))
        assertThat(result.verdict).isInstanceOf(AgentOsExecutionVerdict.Indeterminate::class.java)
        assertThat((result.verdict as AgentOsExecutionVerdict.Indeterminate).reason).isEqualTo(VerdictDeriver.AGENT_NO_STRUCTURED_RESULT)
    }

    @Test fun `absent baseline never yields proof while explicitly captured empty baseline does`() {
        val events=listOf(status("fresh","case-1","2026-01-01T00:00:01Z","IDLE"))
        assertThat(derive(events,null).runtimeProof).isNull()
        assertThat(derive(events,empty).runtimeProof).isNotNull()
    }

    @Test fun `terminal followed by running does not prove termination`() {
        val result=derive(listOf(status("idle","case-1","2026-01-01T00:00:01Z","IDLE"),status("running","case-1","2026-01-01T00:00:02Z","RUNNING")),positioned)
        assertThat(result.runtimeProof).isNull()
        assertThat(result.verdict).isNull()
    }

    @Test fun `legacy derive preserves business verdict`() {
        val events=listOf(status("fresh","case-1","2026-01-01T00:00:01Z","IDLE"))
        assertThat(VerdictDeriver.derive(events,context)).isEqualTo(derive(events,empty).verdict)
    }

    private fun derive(events:List<CaseEventView>,baseline:HighWaterMark?)=VerdictDeriver.deriveWithProof(events,context,"attempt-1",baseline)
    private fun status(id:String,caseId:String,timestamp:String,status:String)=CaseEventView(id,CaseEventView.CASE_STATUS_EVENT,caseId,timestamp,mapOf("status" to status))
}
