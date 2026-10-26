package io.whozoss.agentos.namespace

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.caseFlow.CaseConfigProperties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.UUID

class NamespaceCaseInvalidationSseControllerUnitSpec : StringSpec({
    timeout = 5_000

    "SSE protocol uses a stable payload-free invalidation event name" {
        NamespaceCaseInvalidationSseController.NAMESPACE_CASE_INVALIDATION_EVENT shouldBe
            "namespace-case-invalidation"
    }

    "broadcaster isolates namespace streams" {
        val broadcaster = NamespaceCaseInvalidationBroadcaster()
        val expectedNamespaceId = UUID.randomUUID()
        val otherNamespaceId = UUID.randomUUID()
        val scope = CoroutineScope(Dispatchers.IO)
        var expectedSignals = 0
        var otherSignals = 0

        val expectedCollector = scope.launch {
            broadcaster.events(expectedNamespaceId).first()
            expectedSignals++
        }
        val otherCollector = scope.launch {
            broadcaster.events(otherNamespaceId).first()
            otherSignals++
        }

        try {
            withTimeout(2_000) {
                broadcaster.events(expectedNamespaceId).subscriptionCount.first { it == 1 }
                broadcaster.events(otherNamespaceId).subscriptionCount.first { it == 1 }
            }

            broadcaster.publishCaseCreated(expectedNamespaceId)
            withTimeout(2_000) { expectedCollector.join() }

            expectedSignals shouldBe 1
            otherSignals shouldBe 0
            otherCollector.isActive shouldBe true
        } finally {
            otherCollector.cancel()
            scope.cancel()
        }
    }

    "controller subscribes to the requested namespace stream" {
        val broadcaster = NamespaceCaseInvalidationBroadcaster()
        val namespaceId = UUID.randomUUID()
        val controller =
            NamespaceCaseInvalidationSseController(
                broadcaster,
                CaseConfigProperties(sseHeartbeatIntervalMs = Long.MAX_VALUE),
            )

        controller.streamCaseInvalidations(namespaceId)

        withTimeout(2_000) {
            broadcaster.events(namespaceId).subscriptionCount.first { it == 1 }
        }
    }
})
