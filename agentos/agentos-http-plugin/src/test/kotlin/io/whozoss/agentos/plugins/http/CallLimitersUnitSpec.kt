package io.whozoss.agentos.plugins.http

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.util.UUID

class CallLimitersUnitSpec : StringSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val limiters = CallLimiters()
    val namespace = UUID.randomUUID()

    "the same config identity and limit share one semaphore" {
        val first = limiters.limiterFor(namespace, "ZENDESK", maxConcurrentCalls = 2)
        val second = limiters.limiterFor(namespace, "ZENDESK", maxConcurrentCalls = 2)
        second shouldBeSameInstanceAs first
        first.availablePermits shouldBe 2
    }

    "a changed limit gets a fresh semaphore sized to the new value" {
        val before = limiters.limiterFor(namespace, "ZENDESK", maxConcurrentCalls = 2)
        val after = limiters.limiterFor(namespace, "ZENDESK", maxConcurrentCalls = 3)
        after shouldNotBe before
        after.availablePermits shouldBe 3
    }

    "another namespace or config name has its own semaphore" {
        val base = limiters.limiterFor(namespace, "ZENDESK", maxConcurrentCalls = 1)
        limiters.limiterFor(UUID.randomUUID(), "ZENDESK", maxConcurrentCalls = 1) shouldNotBe base
        limiters.limiterFor(namespace, "GITHUB", maxConcurrentCalls = 1) shouldNotBe base
        limiters.limiterFor(null, "ZENDESK", maxConcurrentCalls = 1) shouldNotBe base
    }
})
