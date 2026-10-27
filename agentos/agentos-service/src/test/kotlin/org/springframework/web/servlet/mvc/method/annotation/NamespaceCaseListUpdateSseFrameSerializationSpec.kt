package org.springframework.web.servlet.mvc.method.annotation

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.caseFlow.CaseConfigProperties
import io.whozoss.agentos.namespace.NamespaceCaseListUpdateBroadcaster
import io.whozoss.agentos.namespace.NamespaceCaseListUpdateSseController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.springframework.http.MediaType
import java.util.UUID
import java.util.function.Consumer

/** Lives in Spring's package solely to exercise the package-private emitter serialization hook. */
class NamespaceCaseListUpdateSseFrameSerializationSpec : StringSpec({
    timeout = 5_000

    "serialized namespace case-list update contains an event and data field" {
        val broadcaster = NamespaceCaseListUpdateBroadcaster()
        val namespaceId = UUID.randomUUID()
        val controller =
            NamespaceCaseListUpdateSseController(
                broadcaster,
                CaseConfigProperties(sseHeartbeatIntervalMs = Long.MAX_VALUE),
            )
        val emitter = controller.streamCaseListUpdates(namespaceId)
        val sent = mutableListOf<ResponseBodyEmitter.DataWithMediaType>()
        emitter.initialize(
            object : ResponseBodyEmitter.Handler {
                override fun send(data: Any, mediaType: MediaType?) {
                    sent += ResponseBodyEmitter.DataWithMediaType(data, mediaType)
                }

                override fun send(items: Set<ResponseBodyEmitter.DataWithMediaType>) {
                    sent += items
                }

                override fun complete() = Unit

                override fun completeWithError(failure: Throwable) = Unit

                override fun onTimeout(callback: Runnable) = Unit

                override fun onError(callback: Consumer<Throwable>) = Unit

                override fun onCompletion(callback: Runnable) = Unit
            },
        )
        withTimeout(2_000) { broadcaster.events(namespaceId).subscriptionCount.first { it == 1 } }

        broadcaster.publishCaseCreated(namespaceId)

        withTimeout(2_000) {
            while (sent.none { it.data == NamespaceCaseListUpdateSseController.NEUTRAL_PAYLOAD }) delay(10)
        }
        sent.any { it.data.toString().contains("namespace-case-list-updated") } shouldBe true
        sent.any { it.data == "{}" } shouldBe true
        emitter.complete()
    }
})
