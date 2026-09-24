package io.whozoss.agentos.plugins.http

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class LastFailureRegistryUnitSpec : StringSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val registry = LastFailureRegistry()
    val namespace = UUID.randomUUID()
    val otherNamespace = UUID.randomUUID()

    "returns null for an unknown config" {
        registry.get(namespace, "ZENDESK") shouldBe null
    }

    "keeps the last recorded message per config" {
        registry.record(namespace, "ZENDESK", "first")
        registry.record(namespace, "ZENDESK", "second")
        registry.record(namespace, "OTHER", "other")
        registry.get(namespace, "ZENDESK") shouldBe "second"
        registry.get(namespace, "OTHER") shouldBe "other"
    }

    "clear forgets the config" {
        registry.record(namespace, "ZENDESK", "failure")
        registry.clear(namespace, "ZENDESK")
        registry.get(namespace, "ZENDESK") shouldBe null
    }

    "the same config name in another namespace is a distinct entry" {
        registry.record(namespace, "ZENDESK", "failure of A")
        registry.get(otherNamespace, "ZENDESK") shouldBe null
        registry.record(otherNamespace, "ZENDESK", "failure of B")
        registry.clear(otherNamespace, "ZENDESK")
        registry.get(namespace, "ZENDESK") shouldBe "failure of A"
    }

    "a missing namespace is its own scope" {
        registry.record(null, "ZENDESK", "no context")
        registry.get(namespace, "ZENDESK") shouldBe null
        registry.get(null, "ZENDESK") shouldBe "no context"
    }
})
