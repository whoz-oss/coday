package io.whozoss.agentos.plugins.http.cache

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.whozoss.agentos.plugins.http.config.ApiKeyPlacement
import io.whozoss.agentos.plugins.http.config.AuthConfig
import io.whozoss.agentos.plugins.http.config.HttpApiConfig
import io.whozoss.agentos.plugins.http.config.OperationOverride
import io.whozoss.agentos.plugins.http.config.ResponseFormat
import io.whozoss.agentos.plugins.http.config.SpecConfig
import io.whozoss.agentos.plugins.http.file.FileStamp

class HttpApiConfigHashUnitSpec : StringSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val base = HttpApiConfig(
        spec = SpecConfig(url = "https://api.example.com/openapi.yaml"),
        baseUrl = "https://api.example.com",
    )

    "is a stable lowercase SHA-256 hex string" {
        val hash = HttpApiConfigHash.of(base)
        hash shouldMatch Regex("[0-9a-f]{64}")
        HttpApiConfigHash.of(base.copy()) shouldBe hash
    }

    mapOf<String, (HttpApiConfig) -> HttpApiConfig>(
        "spec.url" to { it.copy(spec = SpecConfig(url = "https://api.example.com/other.yaml")) },
        "spec.inline" to { it.copy(spec = SpecConfig(inline = "openapi: 3.0.0")) },
        "spec.file" to { it.copy(spec = SpecConfig(file = "/etc/specs/a.yaml")) },
        "baseUrl" to { it.copy(baseUrl = "https://api.example.com/v2") },
        "includeTags" to { it.copy(includeTags = listOf("tickets")) },
        "includePathPrefixes" to { it.copy(includePathPrefixes = listOf("/api")) },
        "includeOperations" to { it.copy(includeOperations = listOf("List*")) },
        "excludeOperations" to { it.copy(excludeOperations = listOf("*Bulk*")) },
        "maxTools" to { it.copy(maxTools = 10) },
        "allowMutations" to { it.copy(allowMutations = true) },
        "auth" to { it.copy(auth = AuthConfig(apiKeyIn = ApiKeyPlacement.QUERY, apiKeyName = "k")) },
        "operations" to {
            it.copy(operations = listOf(OperationOverride(operationId = "ShowTicket", keepPaths = listOf("id"))))
        },
        "responseFormat" to { it.copy(responseFormat = ResponseFormat.YAML) },
        "maxResponseChars" to { it.copy(maxResponseChars = 1000) },
    ).forEach { (field, change) ->
        "changes when $field changes" {
            HttpApiConfigHash.of(change(base)) shouldNotBe HttpApiConfigHash.of(base)
        }
    }

    "two inline documents with different content have different hashes" {
        val a = base.copy(spec = SpecConfig(inline = "openapi: 3.0.0\ninfo: {title: A}"))
        val b = base.copy(spec = SpecConfig(inline = "openapi: 3.0.0\ninfo: {title: B}"))
        HttpApiConfigHash.of(a) shouldNotBe HttpApiConfigHash.of(b)
    }

    mapOf<String, (HttpApiConfig) -> HttpApiConfig>(
        "timeoutSeconds" to { it.copy(timeoutSeconds = 5) },
        "maxConcurrentCalls" to { it.copy(maxConcurrentCalls = 1) },
        "spec.refreshMinutes" to { it.copy(spec = it.spec.copy(refreshMinutes = 5)) },
    ).forEach { (field, change) ->
        "ignores $field, which does not affect the descriptors" {
            HttpApiConfigHash.of(change(base)) shouldBe HttpApiConfigHash.of(base)
        }
    }

    "the default header names are part of the key, their values are not" {
        val named = base.copy(defaultHeaders = mapOf("X-Trace" to "1"))
        HttpApiConfigHash.of(named) shouldNotBe HttpApiConfigHash.of(base)
        HttpApiConfigHash.of(named.copy(defaultHeaders = mapOf("X-Trace" to "2"))) shouldBe HttpApiConfigHash.of(named)
        val reordered = base.copy(defaultHeaders = mapOf("X-B" to "1", "X-A" to "1"))
        HttpApiConfigHash.of(base.copy(defaultHeaders = mapOf("X-A" to "1", "X-B" to "1"))) shouldBe
            HttpApiConfigHash.of(reordered)
    }

    "two spec files with different paths have different hashes" {
        val a = base.copy(spec = SpecConfig(file = "/etc/specs/a.yaml"))
        val b = base.copy(spec = SpecConfig(file = "/etc/specs/b.yaml"))
        HttpApiConfigHash.of(a) shouldNotBe HttpApiConfigHash.of(b)
    }

    "the last modification time and the size of a spec file are part of the key" {
        val file = base.copy(spec = SpecConfig(file = "/etc/specs/a.yaml"))
        val stamp = FileStamp(lastModifiedMillis = 1_000, size = 42)
        val hash = HttpApiConfigHash.of(file, fileStamp = stamp)
        HttpApiConfigHash.of(file, fileStamp = stamp) shouldBe hash
        HttpApiConfigHash.of(file, fileStamp = null) shouldNotBe hash
        HttpApiConfigHash.of(file, fileStamp = stamp.copy(lastModifiedMillis = 2_000)) shouldNotBe hash
        HttpApiConfigHash.of(file, fileStamp = stamp.copy(size = 43)) shouldNotBe hash
    }

    "list order matters" {
        val a = base.copy(includeTags = listOf("a", "b"))
        val b = base.copy(includeTags = listOf("b", "a"))
        HttpApiConfigHash.of(a) shouldNotBe HttpApiConfigHash.of(b)
    }

    "list boundaries are not ambiguous" {
        val a = base.copy(includeTags = listOf("a|b"))
        val b = base.copy(includeTags = listOf("a", "b"))
        HttpApiConfigHash.of(a) shouldNotBe HttpApiConfigHash.of(b)
    }
})
