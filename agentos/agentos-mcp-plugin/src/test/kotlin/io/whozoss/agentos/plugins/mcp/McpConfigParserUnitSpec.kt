package io.whozoss.agentos.plugins.mcp

import ch.qos.logback.classic.Level
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class McpConfigParserUnitSpec : StringSpec({

    val mapper = jacksonObjectMapper()

    // ----- stdio -----

    "parses minimal stdio config" {
        val json = mapper.readTree("""{ "command": "docker" }""")
        val config = McpConfigParser.parse(json)
        config.transport shouldBe McpTransport.STDIO
        config.command shouldBe "docker"
        config.args shouldBe emptyList()
        config.env shouldBe emptyMap()
        config.cwd shouldBe null
        config.timeoutSeconds shouldBe DEFAULT_CONNECT_TIMEOUT_SECONDS
        config.toolCallTimeoutSeconds shouldBe DEFAULT_TOOL_CALL_TIMEOUT_SECONDS
        config.idleTimeoutMinutes shouldBe DEFAULT_IDLE_TIMEOUT_MINUTES
    }

    "parses full stdio config" {
        val json = mapper.readTree("""
            {
                "command": "docker",
                "args": ["run", "-i", "--rm", "ghcr.io/github/github-mcp-server"],
                "env": {"GITHUB_PERSONAL_ACCESS_TOKEN": "ghp_test"},
                "cwd": "/tmp",
                "timeoutSeconds": 15,
                "toolCallTimeoutSeconds": 120,
                "idleTimeoutMinutes": 5
            }
        """)
        val config = McpConfigParser.parse(json)
        config.transport shouldBe McpTransport.STDIO
        config.command shouldBe "docker"
        config.args shouldBe listOf("run", "-i", "--rm", "ghcr.io/github/github-mcp-server")
        config.env shouldBe mapOf("GITHUB_PERSONAL_ACCESS_TOKEN" to "ghp_test")
        config.cwd shouldBe "/tmp"
        config.timeoutSeconds shouldBe 15L
        config.toolCallTimeoutSeconds shouldBe 120L
        config.idleTimeoutMinutes shouldBe 5L
    }

    "rejects blank command" {
        val json = mapper.readTree("""{ "command": "  " }""")
        val ex = shouldThrow<IllegalArgumentException> { McpConfigParser.parse(json) }
        ex.message shouldContain "command"
    }

    "rejects args with blank entry" {
        val json = mapper.readTree("""{ "command": "docker", "args": ["run", ""] }""")
        val ex = shouldThrow<IllegalArgumentException> { McpConfigParser.parse(json) }
        ex.message shouldContain "args"
    }

    "ignores null cwd" {
        val json = mapper.readTree("""{ "command": "docker", "cwd": null }""")
        val config = McpConfigParser.parse(json)
        config.cwd shouldBe null
    }

    // ----- HTTP -----

    "parses minimal HTTP config" {
        val json = mapper.readTree("""{ "url": "https://mcp.example.com/sse" }""")
        val config = McpConfigParser.parse(json)
        config.transport shouldBe McpTransport.HTTP
        config.url shouldBe "https://mcp.example.com/sse"
        config.authToken shouldBe null
        config.timeoutSeconds shouldBe DEFAULT_CONNECT_TIMEOUT_SECONDS
        config.toolCallTimeoutSeconds shouldBe DEFAULT_TOOL_CALL_TIMEOUT_SECONDS
        config.idleTimeoutMinutes shouldBe DEFAULT_IDLE_TIMEOUT_MINUTES
    }

    "parses HTTP config with authToken" {
        val json = mapper.readTree("""
            {
                "url": "https://mcp.example.com/sse",
                "authToken": "secret-token",
                "timeoutSeconds": 10,
                "toolCallTimeoutSeconds": 30,
                "idleTimeoutMinutes": 3
            }
        """)
        val config = McpConfigParser.parse(json)
        config.transport shouldBe McpTransport.HTTP
        config.url shouldBe "https://mcp.example.com/sse"
        config.authToken shouldBe "secret-token"
        config.timeoutSeconds shouldBe 10L
        config.toolCallTimeoutSeconds shouldBe 30L
        config.idleTimeoutMinutes shouldBe 3L
    }

    "rejects blank url" {
        val json = mapper.readTree("""{ "url": "   " }""")
        val ex = shouldThrow<IllegalArgumentException> { McpConfigParser.parse(json) }
        ex.message shouldContain "url"
    }

    "rejects blank authToken" {
        val json = mapper.readTree("""{ "url": "https://mcp.example.com", "authToken": "" }""")
        val ex = shouldThrow<IllegalArgumentException> { McpConfigParser.parse(json) }
        ex.message shouldContain "authToken"
    }

    // ----- HTTP url validation -----

    "accepts https url with a public host" {
        val json = mapper.readTree("""{ "url": "https://mcp.atlassian.com/v1/mcp" }""")
        McpConfigParser.parse(json).url shouldBe "https://mcp.atlassian.com/v1/mcp"
    }

    listOf(
        "https://8.8.8.8/mcp" to "public IPv4",
        "https://100.128.0.1/mcp" to "IPv4 just above the shared address space (100.64.0.0/10)",
        "https://100.63.255.255/mcp" to "IPv4 just below the shared address space (100.64.0.0/10)",
        "https://[2001:db8::1]/mcp" to "global IPv6",
    ).forEach { (url, reason) ->
        "accepts $reason host in url '$url'" {
            val json = mapper.readTree("""{ "url": "$url" }""")
            McpConfigParser.parse(json).url shouldBe url
        }
    }

    "accepts plain http url with authToken but keeps it (cleartext is only warned)" {
        val json = mapper.readTree("""{ "url": "http://mcp.example.com/mcp", "authToken": "secret-token" }""")
        val config = McpConfigParser.parse(json)
        config.url shouldBe "http://mcp.example.com/mcp"
        config.authToken shouldBe "secret-token"
    }

    "warns once about cleartext http when authToken is set, without echoing the token" {
        val json = mapper.readTree("""{ "url": "http://mcp.example.com/mcp", "authToken": "secret-token" }""")
        val logs = LogCapture.capturing { McpConfigParser.parse(json) }
        val warnings = logs.messagesAt(Level.WARN)
        warnings shouldHaveSize 1
        warnings.single() shouldContain "cleartext"
        warnings.single() shouldContain "mcp.example.com"
        logs.messages.forEach { it shouldNotContain "secret-token" }
    }

    "does not warn about cleartext for https url with authToken" {
        val json = mapper.readTree("""{ "url": "https://mcp.example.com/mcp", "authToken": "secret-token" }""")
        val logs = LogCapture.capturing { McpConfigParser.parse(json) }
        logs.messagesAt(Level.WARN).shouldBeEmpty()
    }

    "does not warn about cleartext for http url without authToken" {
        val json = mapper.readTree("""{ "url": "http://mcp.example.com/mcp" }""")
        val logs = LogCapture.capturing { McpConfigParser.parse(json) }
        logs.messagesAt(Level.WARN).shouldBeEmpty()
    }

    listOf(
        "http://127.0.0.1/mcp" to "loopback",
        "http://localhost:8080/mcp" to "localhost",
        "http://10.0.0.1/mcp" to "private",
        "http://169.254.169.254/latest/meta-data" to "link-local",
        "http://100.64.0.1/mcp" to "shared address space (CGNAT)",
        "http://100.127.255.254/mcp" to "shared address space upper bound",
        "http://0.0.0.0/mcp" to "wildcard",
        "http://[::1]/mcp" to "loopback",
        "http://[fe80::1%25eth0]/mcp" to "link-local with zone id",
        "http://[fd00::1]/mcp" to "IPv6 unique-local",
    ).forEach { (url, reason) ->
        "rejects $reason host in url '$url'" {
            val json = mapper.readTree("""{ "url": "$url", "authToken": "secret-token" }""")
            val ex = shouldThrow<IllegalArgumentException> { McpConfigParser.parse(json) }
            ex.message shouldContain "url"
            ex.message shouldContain "host"
            ex.message shouldNotContain "secret-token"
        }
    }

    "rejects unsupported url scheme" {
        val json = mapper.readTree("""{ "url": "ftp://mcp.example.com/mcp", "authToken": "secret-token" }""")
        val ex = shouldThrow<IllegalArgumentException> { McpConfigParser.parse(json) }
        ex.message shouldContain "url"
        ex.message shouldContain "scheme"
        ex.message shouldNotContain "secret-token"
    }

    "rejects relative url" {
        val json = mapper.readTree("""{ "url": "relative/path", "authToken": "secret-token" }""")
        val ex = shouldThrow<IllegalArgumentException> { McpConfigParser.parse(json) }
        ex.message shouldContain "url"
        ex.message shouldContain "absolute"
        ex.message shouldNotContain "secret-token"
    }

    "rejects url with embedded userinfo" {
        val json = mapper.readTree("""{ "url": "https://user:pw@mcp.example.com/mcp", "authToken": "secret-token" }""")
        val ex = shouldThrow<IllegalArgumentException> { McpConfigParser.parse(json) }
        ex.message shouldContain "url"
        ex.message shouldContain "userinfo"
        ex.message shouldNotContain "secret-token"
        ex.message shouldNotContain "user:pw"
    }

    "rejects url whose authority is not a valid host name and says so" {
        val json = mapper.readTree("""{ "url": "http://my_host/mcp", "authToken": "secret-token" }""")
        val ex = shouldThrow<IllegalArgumentException> { McpConfigParser.parse(json) }
        ex.message shouldContain "url"
        ex.message shouldContain "valid host"
        ex.message shouldContain "my_host"
        ex.message shouldNotContain "secret-token"
    }

    "rejects url with userinfo and an invalid host without echoing the userinfo" {
        // An invalid host name makes java.net.URI keep the whole authority raw (userInfo == null),
        // so the host check must not echo the authority verbatim.
        val json = mapper.readTree("""{ "url": "http://user:pw@my_host/mcp", "authToken": "secret-token" }""")
        val ex = shouldThrow<IllegalArgumentException> { McpConfigParser.parse(json) }
        ex.message shouldContain "url"
        ex.message shouldNotContain "pw"
        ex.message shouldNotContain "secret-token"
    }

    listOf(
        "http://user:p@ss@mcp.example.com/mcp" to listOf("p@ss", "ss@"),
        "http://user@name:pw@my_host/mcp" to listOf("name:pw", "pw@"),
    ).forEach { (url, secretFragments) ->
        "rejects url with a multi-'@' userinfo without echoing any fragment of it ('$url')" {
            // RFC 3986 delimits userinfo at the LAST '@'; an unencoded '@' inside the password makes
            // java.net.URI keep the whole authority raw, so no part of it may be echoed.
            val json = mapper.readTree("""{ "url": "$url", "authToken": "secret-token" }""")
            val ex = shouldThrow<IllegalArgumentException> { McpConfigParser.parse(json) }
            ex.message shouldContain "url"
            ex.message shouldContain "userinfo"
            secretFragments.forEach { ex.message shouldNotContain it }
            ex.message shouldNotContain "secret-token"
        }
    }

    "rejects syntactically invalid url" {
        val json = mapper.readTree("""{ "url": "https://mcp.example.com/a b" }""")
        val ex = shouldThrow<IllegalArgumentException> { McpConfigParser.parse(json) }
        ex.message shouldContain "url"
    }

    // ----- empty string coercion (frontend sends "" for optional fields) -----

    "treats empty string env as empty map" {
        val json = mapper.readTree("""{ "command": "docker", "env": "" }""")
        val config = McpConfigParser.parse(json)
        config.env shouldBe emptyMap()
    }

    "treats empty string args as empty list" {
        val json = mapper.readTree("""{ "command": "docker", "args": "" }""")
        val config = McpConfigParser.parse(json)
        config.args shouldBe emptyList()
    }

    // ----- mutual exclusion / missing transport -----

    "rejects config with neither command nor url" {
        val json = mapper.readTree("""{ "args": ["run"] }""")
        val ex = shouldThrow<IllegalArgumentException> { McpConfigParser.parse(json) }
        ex.message shouldContain "command"
        ex.message shouldContain "url"
    }

    "rejects config with both command and url" {
        val json = mapper.readTree("""{ "command": "docker", "url": "https://mcp.example.com" }""")
        val ex = shouldThrow<IllegalArgumentException> { McpConfigParser.parse(json) }
        ex.message shouldContain "mutually exclusive"
    }

    // ----- shared timeouts -----

    "rejects non-positive timeoutSeconds" {
        val json = mapper.readTree("""{ "command": "docker", "timeoutSeconds": 0 }""")
        val ex = shouldThrow<IllegalArgumentException> { McpConfigParser.parse(json) }
        ex.message shouldContain "timeoutSeconds"
    }

    "rejects non-positive toolCallTimeoutSeconds" {
        val json = mapper.readTree("""{ "command": "docker", "toolCallTimeoutSeconds": -1 }""")
        val ex = shouldThrow<IllegalArgumentException> { McpConfigParser.parse(json) }
        ex.message shouldContain "toolCallTimeoutSeconds"
    }

    "rejects non-positive idleTimeoutMinutes" {
        val json = mapper.readTree("""{ "command": "docker", "idleTimeoutMinutes": 0 }""")
        val ex = shouldThrow<IllegalArgumentException> { McpConfigParser.parse(json) }
        ex.message shouldContain "idleTimeoutMinutes"
    }
})
