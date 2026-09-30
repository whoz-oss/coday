package io.whozoss.agentos.plugins.http.config

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.whozoss.agentos.plugins.http.net.OutboundUrlPolicy

class HttpApiConfigParserUnitSpec : StringSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val mapper = jacksonObjectMapper()

    fun parse(json: String): HttpApiConfig = HttpApiConfigParser.parse(mapper.readTree(json))

    /** A valid inline-spec config with [extraFields] (JSON members) appended. */
    fun inlineConfig(vararg extraFields: String): String {
        val extra = extraFields.joinToString("") { ", $it" }
        return """{ "spec": { "inline": "openapi: 3.0.0" }, "baseUrl": "https://api.example.com"$extra }"""
    }

    "parses a minimal config with a spec url and applies defaults" {
        val config = parse(
            """
            { "spec": { "url": "https://api.example.com/openapi.yaml" }, "baseUrl": "https://api.example.com/v2" }
            """,
        )
        config.spec.url shouldBe "https://api.example.com/openapi.yaml"
        config.spec.inline shouldBe null
        config.spec.refreshMinutes shouldBe 60
        config.spec.maxBytes shouldBe 5L * 1024 * 1024
        config.baseUrl shouldBe "https://api.example.com/v2"
        config.includeTags shouldBe emptyList()
        config.includePathPrefixes shouldBe emptyList()
        config.includeOperations shouldBe emptyList()
        config.excludeOperations shouldBe emptyList()
        config.maxTools shouldBe 64
        config.operations shouldBe emptyList()
        config.auth.apiKeyIn shouldBe ApiKeyPlacement.HEADER
        config.auth.apiKeyName shouldBe "X-API-Key"
        config.defaultHeaders shouldBe emptyMap()
        config.responseFormat shouldBe ResponseFormat.JSON
        config.maxResponseChars shouldBe 20000
        config.timeoutSeconds shouldBe 30
        config.maxConcurrentCalls shouldBe 4
        config.allowMutations shouldBe false
    }

    "parses a full config" {
        val config = parse(
            """
            {
              "spec": { "inline": "openapi: 3.0.0", "refreshMinutes": 5, "maxBytes": 1024 },
              "baseUrl": "https://api.example.com",
              "includeTags": ["tickets"],
              "includePathPrefixes": ["/api/v2/tickets"],
              "includeOperations": ["List*"],
              "excludeOperations": ["ListSearchResults"],
              "maxTools": 10,
              "operations": [
                { "operationId": "ShowTicket", "description": "Show one ticket", "keepPaths": ["ticket.id"],
                  "ignorePaths": ["ticket.url"], "responseFormat": "YAML", "maxResponseChars": 1000 }
              ],
              "auth": { "apiKeyIn": "BEARER", "apiKeyName": "Authorization" },
              "defaultHeaders": { "Accept": "application/json" },
              "responseFormat": "YAML",
              "maxResponseChars": 5000,
              "timeoutSeconds": 10,
              "maxConcurrentCalls": 2,
              "allowMutations": true
            }
            """,
        )
        config.spec.inline shouldBe "openapi: 3.0.0"
        config.spec.refreshMinutes shouldBe 5
        config.spec.maxBytes shouldBe 1024L
        config.includeTags shouldBe listOf("tickets")
        config.includePathPrefixes shouldBe listOf("/api/v2/tickets")
        config.includeOperations shouldBe listOf("List*")
        config.excludeOperations shouldBe listOf("ListSearchResults")
        config.maxTools shouldBe 10
        config.operations shouldBe listOf(
            OperationOverride(
                operationId = "ShowTicket",
                description = "Show one ticket",
                keepPaths = listOf("ticket.id"),
                ignorePaths = listOf("ticket.url"),
                responseFormat = ResponseFormat.YAML,
                maxResponseChars = 1000,
            ),
        )
        config.auth shouldBe AuthConfig(apiKeyIn = ApiKeyPlacement.BEARER, apiKeyName = "Authorization")
        config.defaultHeaders shouldBe mapOf("Accept" to "application/json")
        config.responseFormat shouldBe ResponseFormat.YAML
        config.maxResponseChars shouldBe 5000
        config.timeoutSeconds shouldBe 10
        config.maxConcurrentCalls shouldBe 2
        config.allowMutations shouldBe true
    }

    "coerces empty strings sent by the UI form to absent values" {
        val config = parse(
            """
            {
              "spec": { "url": "https://api.example.com/openapi.json", "inline": "" },
              "baseUrl": "https://api.example.com",
              "includeTags": "",
              "defaultHeaders": "",
              "operations": [
                { "operationId": "ShowTicket", "description": "", "responseFormat": "", "maxResponseChars": "" }
              ]
            }
            """,
        )
        config.spec.inline shouldBe null
        config.includeTags shouldBe emptyList()
        config.defaultHeaders shouldBe emptyMap()
        config.operations.single().description shouldBe null
        config.operations.single().responseFormat shouldBe null
        config.operations.single().maxResponseChars shouldBe null
    }

    "falls back to the default api key name when the form sends it blank" {
        val bearer = parse(inlineConfig(""""auth": { "apiKeyIn": "BEARER", "apiKeyName": "" }"""))
        bearer.auth shouldBe AuthConfig(apiKeyIn = ApiKeyPlacement.BEARER, apiKeyName = AuthConfig.DEFAULT_API_KEY_NAME)
        val header = parse(inlineConfig(""""auth": { "apiKeyName": " " }"""))
        header.auth shouldBe AuthConfig(apiKeyIn = ApiKeyPlacement.HEADER, apiKeyName = AuthConfig.DEFAULT_API_KEY_NAME)
    }

    "keeps the numeric defaults when the form sends empty strings" {
        val config = parse(
            """
            { "spec": { "inline": "openapi: 3.0.0", "refreshMinutes": "", "maxBytes": "" },
              "baseUrl": "https://api.example.com",
              "maxTools": "", "maxResponseChars": "", "timeoutSeconds": "", "maxConcurrentCalls": "" }
            """,
        )
        config.spec.refreshMinutes shouldBe SpecConfig.DEFAULT_REFRESH_MINUTES
        config.spec.maxBytes shouldBe SpecConfig.DEFAULT_MAX_BYTES
        config.maxTools shouldBe HttpApiConfig.DEFAULT_MAX_TOOLS
        config.maxResponseChars shouldBe HttpApiConfig.DEFAULT_MAX_RESPONSE_CHARS
        config.timeoutSeconds shouldBe HttpApiConfig.DEFAULT_TIMEOUT_SECONDS
        config.maxConcurrentCalls shouldBe HttpApiConfig.DEFAULT_MAX_CONCURRENT_CALLS
    }

    "keeps the enum defaults when the form sends empty strings" {
        val config = parse(inlineConfig("\"responseFormat\": \"\"", "\"auth\": { \"apiKeyIn\": \"\" }"))
        config.responseFormat shouldBe ResponseFormat.JSON
        config.auth.apiKeyIn shouldBe ApiKeyPlacement.HEADER
    }

    "drops a default header whose value the form left blank" {
        val config = parse(inlineConfig(""""defaultHeaders": { "Accept": "application/json", "X-Empty": "" }"""))
        config.defaultHeaders shouldBe mapOf("Accept" to "application/json")
    }

    "treats an explicit null on a list or map field as no entries" {
        val config = parse(inlineConfig(""""includeTags": null""", """"defaultHeaders": null"""))
        config.includeTags shouldBe emptyList()
        config.defaultHeaders shouldBe emptyMap()
    }

    "rejects an unknown property naming it" {
        val ex = shouldThrow<IllegalArgumentException> { parse(inlineConfig(""""allowMutation": true""")) }
        ex.message shouldBe "HTTP API integration config: unknown key 'allowMutation'"
    }

    "rejects an unknown nested property with its path" {
        val ex = shouldThrow<IllegalArgumentException> {
            parse(inlineConfig(""""operations": [{ "operationId": "a", "keepPath": ["id"] }]"""))
        }
        ex.message shouldBe "HTTP API integration config: unknown key 'operations[0].keepPath'"
    }

    "rejects a config with neither spec url, inline nor file" {
        val ex = shouldThrow<IllegalArgumentException> {
            parse("""{ "spec": { "url": " " }, "baseUrl": "https://api.example.com" }""")
        }
        ex.message shouldContain "spec.url"
        ex.message shouldContain "spec.inline"
        ex.message shouldContain "spec.file"
    }

    listOf(
        """"url": "https://api.example.com/o.yaml", "inline": "openapi: 3.0.0"""",
        """"url": "https://api.example.com/o.yaml", "file": "/etc/specs/o.yaml"""",
        """"inline": "openapi: 3.0.0", "file": "/etc/specs/o.yaml"""",
    ).forEach { sources ->
        "rejects a config with several spec sources: $sources" {
            val ex = shouldThrow<IllegalArgumentException> {
                parse("""{ "spec": { $sources }, "baseUrl": "https://api.example.com" }""")
            }
            ex.message shouldContain "mutually exclusive"
        }
    }

    listOf("/etc/specs/o.json", "/etc/specs/o.yaml", "/etc/specs/o.yml", "/etc/specs/O.YAML").forEach { file ->
        "accepts the spec file $file without touching the filesystem" {
            parse("""{ "spec": { "file": "$file" }, "baseUrl": "https://api.example.com" }""").spec.file shouldBe file
        }
    }

    "rejects a spec file with another extension" {
        val ex = shouldThrow<IllegalArgumentException> {
            parse("""{ "spec": { "file": "/etc/specs/o.txt" }, "baseUrl": "https://api.example.com" }""")
        }
        ex.message shouldContain "'spec.file'"
        ex.message shouldContain ".json, .yaml or .yml"
    }

    "rejects a relative spec file path, hinting at the config path token" {
        val ex = shouldThrow<IllegalArgumentException> {
            parse("""{ "spec": { "file": "specs/o.yaml" }, "baseUrl": "https://api.example.com" }""")
        }
        ex.message shouldContain "'spec.file' must be an absolute path"
        ex.message shouldContain "{{NAMESPACE_CONFIG_PATH}}"
    }

    "rejects a missing spec block naming the field" {
        val ex = shouldThrow<IllegalArgumentException> { parse("""{ "baseUrl": "https://api.example.com" }""") }
        ex.message shouldContain "'spec' is required"
    }

    "accepts a config without baseUrl, resolved later from the document server URL" {
        parse("""{ "spec": { "inline": "openapi: 3.0.0" } }""").baseUrl shouldBe null
    }

    "rejects an http baseUrl" {
        val ex = shouldThrow<IllegalArgumentException> {
            parse("""{ "spec": { "inline": "openapi: 3.0.0" }, "baseUrl": "http://api.example.com" }""")
        }
        ex.message shouldContain "baseUrl"
        ex.message shouldContain "https"
    }

    listOf("https://corp.example.com/api?x=1", "https://corp.example.com/api#frag").forEach { url ->
        "rejects a baseUrl carrying a query string or fragment: $url" {
            val ex = shouldThrow<IllegalArgumentException> {
                parse("""{ "spec": { "inline": "openapi: 3.0.0" }, "baseUrl": "$url" }""")
            }
            ex.message shouldBe "HTTP API integration config: 'baseUrl' must not carry a query string or fragment"
        }
    }

    "rejects a baseUrl with a port above 65535 naming the port" {
        val ex = shouldThrow<IllegalArgumentException> {
            parse("""{ "spec": { "inline": "openapi: 3.0.0" }, "baseUrl": "https://api.example.com:99999" }""")
        }
        ex.message shouldContain "'baseUrl'"
        ex.message shouldContain "port"
        ex.message shouldContain "99999"
    }

    "rejects a spec url with a port above 65535 naming the port" {
        val ex = shouldThrow<IllegalArgumentException> {
            parse("""{ "spec": { "url": "https://h:99999/o.yaml" }, "baseUrl": "https://api.example.com" }""")
        }
        ex.message shouldContain "'spec.url'"
        ex.message shouldContain "port"
    }

    "rejects a private baseUrl host" {
        shouldThrow<IllegalArgumentException> {
            parse("""{ "spec": { "inline": "openapi: 3.0.0" }, "baseUrl": "https://10.0.0.1" }""")
        }
    }

    "accepts a loopback baseUrl only with the explicit lenient policy" {
        val json = mapper.readTree(
            """{ "spec": { "inline": "openapi: 3.0.0" }, "baseUrl": "https://127.0.0.1:8443" }""",
        )
        shouldThrow<IllegalArgumentException> { HttpApiConfigParser.parse(json) }
        val lenient = HttpApiConfigParser.parse(json, OutboundUrlPolicy(allowLoopbackForTests = true))
        lenient.baseUrl shouldBe "https://127.0.0.1:8443"
    }

    "rejects an http spec url" {
        val ex = shouldThrow<IllegalArgumentException> {
            parse("""{ "spec": { "url": "http://api.example.com/o.yaml" }, "baseUrl": "https://api.example.com" }""")
        }
        ex.message shouldContain "spec.url"
    }

    listOf("Authorization", "authorization", "Proxy-Authorization", "Cookie", "HOST").forEach { header ->
        "rejects reserved default header $header" {
            val ex = shouldThrow<IllegalArgumentException> {
                parse(inlineConfig(""""defaultHeaders": { "$header": "x" }"""))
            }
            ex.message shouldContain "defaultHeaders"
        }
    }

    "accepts a default header equal to the api key name: the effective placement is only known at load time" {
        val config = parse(
            inlineConfig(
                """"auth": { "apiKeyIn": "HEADER", "apiKeyName": "X-Token" }""",
                """"defaultHeaders": { "X-Token": "x" }""",
            ),
        )
        config.defaultHeaders shouldBe mapOf("X-Token" to "x")
    }

    "rejects duplicate operation overrides" {
        val ex = shouldThrow<IllegalArgumentException> {
            val overrides = """"operations": [ { "operationId": "ShowTicket" }, { "operationId": "ShowTicket" } ]"""
            parse(inlineConfig(overrides))
        }
        ex.message shouldContain "ShowTicket"
    }

    "rejects a blank operation override id naming the field" {
        val ex = shouldThrow<IllegalArgumentException> {
            parse(inlineConfig(""""operations": [ { "operationId": " " } ]"""))
        }
        ex.message shouldContain "'operations[0].operationId' is required"
    }

    "rejects an operation override maxResponseChars below the minimum" {
        shouldThrow<IllegalArgumentException> {
            parse(inlineConfig(""""operations": [ { "operationId": "ShowTicket", "maxResponseChars": 10 } ]"""))
        }
    }

    mapOf(
        "\"maxTools\": 0" to "maxTools",
        "\"maxTools\": 129" to "maxTools",
        "\"maxResponseChars\": 499" to "maxResponseChars",
        "\"timeoutSeconds\": 0" to "timeoutSeconds",
        "\"maxConcurrentCalls\": 0" to "maxConcurrentCalls",
        "\"spec\": { \"inline\": \"openapi: 3.0.0\", \"refreshMinutes\": -1 }" to "refreshMinutes",
        "\"spec\": { \"inline\": \"openapi: 3.0.0\", \"maxBytes\": 1023 }" to "maxBytes",
    ).forEach { (fragment, field) ->
        "rejects out-of-bounds $field" {
            val base = if (fragment.startsWith("\"spec\"")) "" else "\"spec\": { \"inline\": \"openapi: 3.0.0\" },"
            val ex = shouldThrow<IllegalArgumentException> {
                parse("""{ $base "baseUrl": "https://api.example.com", $fragment }""")
            }
            ex.message shouldContain field
        }
    }

    "accepts the numeric bounds" {
        val config = parse(
            """
            { "spec": { "inline": "openapi: 3.0.0", "refreshMinutes": 0, "maxBytes": 1024 },
              "baseUrl": "https://api.example.com",
              "maxTools": 128, "maxResponseChars": 500, "timeoutSeconds": 1, "maxConcurrentCalls": 1 }
            """,
        )
        config.spec.refreshMinutes shouldBe 0
        config.spec.maxBytes shouldBe SpecConfig.MIN_MAX_BYTES
        config.maxTools shouldBe 128
        config.maxResponseChars shouldBe 500
    }
})
