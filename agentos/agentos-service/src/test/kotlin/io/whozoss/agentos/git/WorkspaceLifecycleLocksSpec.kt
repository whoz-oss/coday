package io.whozoss.agentos.git

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WorkspaceLifecycleLocksSpec : StringSpec({
    "a request thread does not wait for a workspace another thread is working on" {
        val rootId = UUID.randomUUID()
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val owner = executor.submit { WorkspaceLifecycleLocks.withRoot(rootId) { held.countDown(); release.await(5, TimeUnit.SECONDS) } }
            held.await(5, TimeUnit.SECONDS) shouldBe true

            WorkspaceLifecycleLocks.tryWithRoot(rootId, onBusy = { "busy" }) { "acquired" } shouldBe "busy"
            WorkspaceLifecycleLocks.tryWithRoot(UUID.randomUUID(), onBusy = { "busy" }) { "acquired" } shouldBe "acquired"

            release.countDown()
            owner.get(5, TimeUnit.SECONDS)
            WorkspaceLifecycleLocks.tryWithRoot(rootId, onBusy = { "busy" }) { "acquired" } shouldBe "acquired"
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
})
