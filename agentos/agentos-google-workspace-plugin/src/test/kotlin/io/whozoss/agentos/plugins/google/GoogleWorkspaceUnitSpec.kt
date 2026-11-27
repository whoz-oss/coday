package io.whozoss.agentos.plugins.google

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.credential.Credential
import io.whozoss.agentos.sdk.credential.CredentialType
import java.util.UUID

class GoogleWorkspaceUnitSpec : StringSpec({
    val mapper = jacksonObjectMapper()
    val user = UUID.randomUUID()
    val context = ToolContext(UUID.randomUUID(), user, "user@example.test", emptyList(),
        credentialProvider = { Credential(userId = user, authSettingId = UUID.randomUUID(),
            credentialType = CredentialType.OAUTH_TOKENS, data = mapOf("accessToken" to "synthetic-token", "scope" to "https://www.googleapis.com/auth/drive.readonly https://www.googleapis.com/auth/calendar.readonly https://www.googleapis.com/auth/gmail.readonly")) },
        authSettingName = "GOOGLE")
    suspend fun call(operation: String, config: String, args: String, responder: (GoogleRequest) -> GoogleResponse) =
        GoogleWorkspaceToolProvider { GoogleTransport { request, _ -> responder(request) } }.provideTools(mapper.readTree(config), "GW", context)
            .single { it.name == "GW__$operation" }.executeWithJson(args, context)
    fun response(body: String, type: String = "application/json") = GoogleResponse(200, type, body)

    listOf("https://www.googleapis.com/auth/drive", "unknown", "", null).forEach { scope ->
        "actual OAuth grant scope=$scope is refused before any resource request" {
            var calls = 0
            val bound = context.copy(credentialProvider = {
                Credential(userId = user, authSettingId = UUID.randomUUID(), credentialType = CredentialType.OAUTH_TOKENS,
                    data = buildMap { put("accessToken", "synthetic-token"); if (scope != null) put("scope", scope) })
            })
            val result = GoogleWorkspaceToolProvider { GoogleTransport { _, _ -> calls++; response("""{"files":[]}""") } }
                .provideTools(mapper.readTree("""{"drive":{"allowMyDrive":true}}"""), "GW", bound)
                .single { it.name == "GW__drive_list" }.executeWithJson("{}", bound)
            result.errorType shouldBe "AUTH_MISSING"
            calls shouldBe 0
        }
    }
    "drive list filters denied metadata and hides raw totals and tokens" {
        val result = call("drive_list", """{"drive":{"allowedSharedDriveIds":["allowed"]}}""", "{}") {
            response("""{"files":[{"id":"good","driveId":"allowed","name":"Visible","mimeType":"text/plain"},{"id":"bad","driveId":"blocked","name":"SECRET","mimeType":"text/plain"}],"rawSecret":"SECRET"}""")
        }
        result.success shouldBe true
        result.output.contains("Visible") shouldBe true
        result.output.contains("SECRET") shouldBe false
    }
    "drive list fails closed if next page exceeds budget without returning first page" {
        val result = call("drive_list", """{"drive":{"allowMyDrive":true},"maxPages":1}""", "{}") {
            response("""{"files":[{"id":"good","ownedByMe":true,"name":"Visible","mimeType":"text/plain"}],"nextPageToken":"SECRET_CURSOR"}""")
        }
        result.success shouldBe false
        result.errorType shouldBe "LIMIT_EXCEEDED"
        result.output.contains("Visible") shouldBe false
        result.output.contains("SECRET_CURSOR") shouldBe false
    }
    "drive shortcut to denied target is refused including shortcut metadata" {
        val result = call("drive_metadata", """{"drive":{"allowedSharedDriveIds":["allowed"]}}""", """{"fileId":"shortcut"}""") {
            when (it.path) {
                "/drive/v3/files/shortcut" -> response("""{"id":"shortcut","driveId":"allowed","name":"Secret shortcut","mimeType":"application/vnd.google-apps.shortcut","shortcutDetails":{"targetId":"target"}}""")
                else -> response("""{"id":"target","driveId":"denied","mimeType":"text/plain"}""")
            }
        }
        result.errorType shouldBe "POLICY_DENIED"
        result.output.contains("Secret shortcut") shouldBe false
    }
    "drive reads allowed text only after metadata recheck" {
        val paths = mutableListOf<GoogleRequest>()
        val result = call("drive_read", """{"drive":{"allowMyDrive":true}}""", """{"fileId":"good"}""") {
            paths.add(it)
            if (it.query["alt"] == "media") response("Hello", "text/plain")
            else response("""{"id":"good","ownedByMe":true,"mimeType":"text/plain"}""")
        }
        result.success shouldBe true
        result.output.contains("Hello") shouldBe true
        paths.size shouldBe 3
    }
    "drive exports a native document only as plain text" {
        val result = call("drive_export", """{"drive":{"allowMyDrive":true}}""", """{"fileId":"good"}""") {
            if (it.path.endsWith("/export")) {
                it.query["mimeType"] shouldBe "text/plain"
                response("Exported", "text/plain")
            } else response("""{"id":"good","ownedByMe":true,"mimeType":"application/vnd.google-apps.document"}""")
        }
        result.success shouldBe true
        result.output.contains("Exported") shouldBe true
    }
    "drive incomplete search discards even governed results" {
        val result = call("drive_list", """{"drive":{"allowMyDrive":true}}""", "{}") {
            response("""{"files":[],"incompleteSearch":true}""")
        }
        result.errorType shouldBe "UPSTREAM_ERROR"
    }
    "calendar reads a bounded event list from explicitly allowed calendar only" {
        val result = call("calendar_events", """{"calendar":{"allowedCalendarIds":["team@example.test"]}}""",
            """{"calendarId":"team@example.test","timeMin":"2026-01-01T00:00:00Z","timeMax":"2026-01-03T00:00:00Z"}""") {
            response("""{"items":[{"id":"evt","summary":"Meeting","start":{"dateTime":"2026-01-01T10:00:00Z"},"end":{"dateTime":"2026-01-01T11:00:00Z"},"attendees":[{"email":"SECRET"}]}],"summary":"SECRET"}""")
        }
        result.success shouldBe true
        result.output.contains("Meeting") shouldBe true
        result.output.contains("SECRET") shouldBe false
    }
    listOf(
        "2026-02-01T00:00:00Z" to true,
        "2026-02-01T00:00:00.000000001Z" to false,
        "2026-02-01T01:00:00+01:00" to true,
        "2026-02-01T01:00:00.000000001+01:00" to false,
    ).forEach { (end, accepted) ->
        "calendar exact 31-day boundary end=$end accepted=$accepted" {
            var calls = 0
            val result = call("calendar_events", """{"calendar":{"allowedCalendarIds":["allowed"]}}""",
                """{"calendarId":"allowed","timeMin":"2026-01-01T00:00:00Z","timeMax":"$end"}""") {
                calls++; response("""{"items":[]}""")
            }
            result.success shouldBe accepted
            if (!accepted) result.errorType shouldBe "INVALID_INPUT"
            calls shouldBe if (accepted) 1 else 0
        }
    }
    "calendar denies an accessible unlisted calendar without network" {
        var calls = 0
        val result = call("calendar_event", """{"calendar":{"allowedCalendarIds":["allowed"]}}""", """{"calendarId":"denied","eventId":"evt"}""") {
            calls++; response("""{"id":"evt","summary":"SECRET"}""")
        }
        result.errorType shouldBe "POLICY_DENIED"
        calls shouldBe 0
    }
    "calendar refuses mismatched returned event ID" {
        val result = call("calendar_event", """{"calendar":{"allowedCalendarIds":["allowed"]}}""", """{"calendarId":"allowed","eventId":"evt"}""") {
            response("""{"id":"other","summary":"SECRET"}""")
        }
        result.errorType shouldBe "UPSTREAM_ERROR"
    }
    "gmail search filters message IDs before metadata retrieval and labels narrow fixed allowlist" {
        val paths = mutableListOf<String>()
        val result = call("gmail_search", """{"gmail":{"mailbox":"user@example.test","allowedMessageIds":["good"],"requiredLabelIds":["Label_1"]}}""", """{"query":"hello"}""") {
            paths.add(it.path)
            when {
                it.path.endsWith("/profile") -> response("""{"emailAddress":"user@example.test","messagesTotal":999}""")
                it.path.endsWith("/messages") -> response("""{"messages":[{"id":"good","threadId":"hidden"},{"id":"bad","threadId":"SECRET"}],"resultSizeEstimate":999}""")
                else -> response("""{"id":"good","labelIds":["Label_1"],"snippet":"SECRET","payload":{"headers":[{"name":"Subject","value":"Visible"}]}}""")
            }
        }
        result.success shouldBe true
        result.output.contains("Visible") shouldBe true
        result.output.contains("SECRET") shouldBe false
        paths.contains("/gmail/v1/users/me/messages/bad") shouldBe false
    }
    "gmail labels alone never authorize message reads" {
        var calls = 0
        val result = call("gmail_read", """{"gmail":{"mailbox":"user@example.test","requiredLabelIds":["Label_1"]}}""", """{"messageId":"bad"}""") {
            calls++; response("""{"id":"bad","labelIds":["Label_1"]}""")
        }
        result.errorType shouldBe "POLICY_DENIED"
        calls shouldBe 0
    }
    "gmail denies mismatched actual mailbox before message fetch" {
        var calls = 0
        val result = call("gmail_metadata", """{"gmail":{"mailbox":"user@example.test","allowedMessageIds":["good"]}}""", """{"messageId":"good"}""") {
            calls++; response("""{"emailAddress":"other@example.test"}""")
        }
        result.errorType shouldBe "POLICY_DENIED"
        calls shouldBe 1
    }
    "gmail reads only inline plain text of a selected message and rechecks labels" {
        val result = call("gmail_read", """{"gmail":{"mailbox":"user@example.test","allowedMessageIds":["good"]}}""", """{"messageId":"good"}""") {
            if (it.path.endsWith("/profile")) response("""{"emailAddress":"user@example.test"}""")
            else response("""{"id":"good","labelIds":["INBOX"],"payload":{"mimeType":"text/plain","body":{"data":"SGVsbG8"},"headers":[{"name":"Subject","value":"Greeting"}]}}""")
        }
        result.success shouldBe true
        result.output.contains("Hello") shouldBe true
    }
    "gmail rejects a selected message missing required labels" {
        val result = call("gmail_metadata", """{"gmail":{"mailbox":"user@example.test","allowedMessageIds":["good"],"requiredLabelIds":["Label_1"]}}""", """{"messageId":"good"}""") {
            if (it.path.endsWith("/profile")) response("""{"emailAddress":"user@example.test"}""")
            else response("""{"id":"good","labelIds":["INBOX"],"payload":{"headers":[]}}""")
        }
        result.errorType shouldBe "POLICY_DENIED"
    }
    "policy snapshot cannot be widened by mutating original configuration" {
        val config = mapper.readTree("""{"drive":{"allowedSharedDriveIds":["allowed"]}}""")
        val tools = GoogleWorkspaceToolProvider { GoogleTransport { _, _ -> response("""{"id":"bad","driveId":"denied","mimeType":"text/plain","name":"SECRET"}""") } }.provideTools(config, "GW", context)
        (config.get("drive").get("allowedSharedDriveIds") as com.fasterxml.jackson.databind.node.ArrayNode).add("denied")
        tools.single { it.name == "GW__drive_metadata" }.executeWithJson("""{"fileId":"bad"}""", context).errorType shouldBe "POLICY_DENIED"
    }
    "default deny refuses My Drive not owned by credential user" {
        call("drive_metadata", "{}", """{"fileId":"shared"}""") { response("""{"id":"shared","mimeType":"text/plain","ownedByMe":false}""") }.errorType shouldBe "POLICY_DENIED"
        call("drive_metadata", """{"drive":{"allowMyDrive":true}}""", """{"fileId":"shared"}""") { response("""{"id":"shared","mimeType":"text/plain","ownedByMe":false}""") }.errorType shouldBe "POLICY_DENIED"
    }
    listOf(
        Triple("null", "INVALID_CONFIG", "parameters"),
        Triple("[]", "INVALID_CONFIG", "parameters"),
        Triple("""{"drive":{"allowMyDrive":"true"}}""", "INVALID_CONFIG", "drive.allowMyDrive"),
        Triple("""{"maxPages":0}""", "INVALID_CONFIG", "maxPages"),
        Triple("""{"maxItems":1.0}""", "INVALID_CONFIG", "maxItems"),
        Triple("""{"drive":{"allowedSharedDriveIds":["a"],"blockedSharedDriveIds":["a"]}}""", "INVALID_CONFIG", "drive.blockedSharedDriveIds"),
        Triple("""{"gmail":{"allowedMessageIds":["msg"]}}""", "INVALID_CONFIG", "gmail.mailbox"),
        Triple("""{"baseUrl":"https://evil.test"}""", "INVALID_CONFIG", "parameters"),
        Triple("""{"tokenUrl":"https://evil.test/token"}""", "INVALID_CONFIG", "parameters"),
        Triple("""{"allowMutations":true}""", "INVALID_CONFIG", "parameters"),
        Triple("""{"drive":{"evilKey":true}}""", "INVALID_CONFIG", "drive"),
        Triple("""{"calendar":{"allowedCalendarIds":["team@example.test","primary"]}}""", "INVALID_INPUT", "calendar.allowedCalendarIds[1]"),
        Triple("""{"drive":{"allowedSharedDriveIds":["a/b"]}}""", "INVALID_INPUT", "drive.allowedSharedDriveIds[0]"),
        Triple("""{"gmail":{"mailbox":"user@example.test","requiredLabelIds":["Mon libellé"]}}""", "INVALID_INPUT", "gmail.requiredLabelIds[0]"),
    ).forEach { (config, code, field) ->
        "invalid configuration $config is refused as $code at $field, without leaking any value" {
            val refused = io.kotest.assertions.throwables.shouldThrow<GoogleConfigRefused> {
                GoogleWorkspaceToolProvider { error("No network") }.provideTools(mapper.readTree(config), "GW", context)
            }
            refused.code shouldBe code
            refused.field shouldBe field
            refused.cause shouldBe null
            refused.message!!.startsWith("Google Workspace configuration refused: $code at $field: ") shouldBe true
            refused.reason.isNullOrBlank() shouldBe false
            // "primary" is not checked: the constant reason of a bare-ID refusal names the refused aliases.
            listOf("evil", "a/b", "libellé", "team@").forEach { secret -> refused.message!!.contains(secret) shouldBe false }
        }
    }
    "an ID both allowed and blocked is refused with a reason an administrator can act on" {
        val refused = io.kotest.assertions.throwables.shouldThrow<GoogleConfigRefused> {
            GoogleWorkspaceToolProvider { error("No network") }.provideTools(
                mapper.readTree("""{"drive":{"allowedSharedDriveIds":["secretDrive"],"blockedSharedDriveIds":["secretDrive"]}}"""), "GW", context)
        }
        refused.message shouldBe "Google Workspace configuration refused: INVALID_CONFIG at drive.blockedSharedDriveIds: " +
            "a shared drive ID cannot be both allowed and blocked"
        refused.message!!.contains("secretDrive") shouldBe false
    }
    "drive and calendar work without any gmail section: the mailbox is only required for Gmail" {
        listOf("", ""","gmail":{"mailbox":""}""", ""","gmail":{"mailbox":null,"allowedMessageIds":[]}""").forEach { gmail ->
            GoogleWorkspaceToolProvider { error("No network") }
                .provideTools(mapper.readTree("""{"drive":{"allowedSharedDriveIds":["d"]},"calendar":{"allowedCalendarIds":["team@example.test"]}$gmail}"""), "GW", context)
                .size shouldBe 9
        }
    }
    "missing configuration and invalid configuration names are refused loudly" {
        io.kotest.assertions.throwables.shouldThrow<GoogleConfigRefused> {
            GoogleWorkspaceToolProvider { error("No network") }.provideTools(null, "GW", context)
        }.field shouldBe "parameters"
        listOf(null, "", "A__B", "bad name", "x".repeat(37)).forEach { name ->
            val refused = io.kotest.assertions.throwables.shouldThrow<GoogleConfigRefused> {
                GoogleWorkspaceToolProvider { error("No network") }.provideTools(mapper.readTree("{}"), name, context)
            }
            refused.code shouldBe "INVALID_CONFIG"
            refused.field shouldBe "name"
        }
    }
    "malformed input and attempted write method are refused without transport" {
        var calls = 0
        val tools = GoogleWorkspaceToolProvider { GoogleTransport { _, _ -> calls++; response("{}") } }.provideTools(mapper.readTree("{}"), "GW", context)
        val tool = tools.single { it.name == "GW__drive_metadata" }
        listOf("[]", "broken", """{"fileId":"../secret"}""", """{"fileId":"good","method":"DELETE"}""").forEach {
            tool.executeWithJson(it, context).success shouldBe false
        }
        calls shouldBe 0
        tools.size shouldBe 9
    }
    "binding rejects cross user and cross namespace execution and credentials" {
        var calls = 0
        val tools = GoogleWorkspaceToolProvider { GoogleTransport { _, _ -> calls++; response("{}") } }.provideTools(mapper.readTree("{}"), "GW", context)
        val tool = tools.single { it.name == "GW__drive_metadata" }
        tool.executeWithJson("""{"fileId":"good"}""", context.copy(userId = UUID.randomUUID())).errorType shouldBe "AUTH_MISSING"
        tool.executeWithJson("""{"fileId":"good"}""", context.copy(namespaceId = UUID.randomUUID())).errorType shouldBe "AUTH_MISSING"
        calls shouldBe 0
    }
    "raw errors transport exceptions and credential echoes never escape" {
        val config = """{"drive":{"allowMyDrive":true}}"""
        val args = """{"fileId":"good"}"""
        listOf(302, 401, 403, 429, 500).forEach { code ->
            val result = call("drive_metadata", config, args) { GoogleResponse(code, "application/json", "SECRET synthetic-token") }
            result.success shouldBe false
            result.output.contains("SECRET") shouldBe false
            result.output.contains("synthetic-token") shouldBe false
        }
        call("drive_metadata", config, args) { throw IllegalStateException("SECRET synthetic-token") }.output.contains("SECRET") shouldBe false
        call("drive_metadata", config, args) { response("""{"id":"good","ownedByMe":true,"mimeType":"text/plain","name":"synthetic-token"}""") }.success shouldBe false
    }
    "pagination loop or malformed later page discards prior results" {
        var calls = 0
        val result = call("drive_list", """{"drive":{"allowMyDrive":true}}""", "{}") {
            calls++
            response("""{"files":[{"id":"good","ownedByMe":true,"mimeType":"text/plain","name":"Visible"}],"nextPageToken":"loop"}""")
        }
        calls shouldBe 2
        result.errorType shouldBe "UPSTREAM_ERROR"
        result.output.contains("Visible") shouldBe false
    }
    "successful multi page drive list consumes cursor internally only" {
        var calls = 0
        val result = call("drive_list", """{"drive":{"allowMyDrive":true}}""", "{}") {
            calls++
            if (calls == 1) response("""{"files":[{"id":"good1","ownedByMe":true,"mimeType":"text/plain","name":"One"}],"nextPageToken":"PRIVATE_CURSOR"}""")
            else {
                it.query["pageToken"] shouldBe "PRIVATE_CURSOR"
                response("""{"files":[{"id":"good2","ownedByMe":true,"mimeType":"text/plain","name":"Two"}]}""")
            }
        }
        result.success shouldBe true
        result.output.contains("One") shouldBe true
        result.output.contains("Two") shouldBe true
        result.output.contains("PRIVATE_CURSOR") shouldBe false
    }
    "content move after read causes entire content to be discarded" {
        var metadataCalls = 0
        val result = call("drive_read", """{"drive":{"allowedSharedDriveIds":["allowed"]}}""", """{"fileId":"good"}""") {
            if (it.query["alt"] == "media") response("SECRET", "text/plain")
            else { metadataCalls++; response("""{"id":"good","driveId":"${if (metadataCalls == 1) "allowed" else "denied"}","mimeType":"text/plain"}""") }
        }
        result.errorType shouldBe "POLICY_DENIED"
        result.output.contains("SECRET") shouldBe false
    }
    "missing or cross user OAuth credentials refuse transport" {
        var calls = 0
        listOf(context.copy(userId = null), context.copy(credentialProvider = null), context.copy(credentialProvider = { null }),
            context.copy(credentialProvider = { Credential(userId = UUID.randomUUID(), authSettingId = UUID.randomUUID(), credentialType = CredentialType.OAUTH_TOKENS, data = mapOf("accessToken" to "synthetic-token", "scope" to "https://www.googleapis.com/auth/drive.readonly https://www.googleapis.com/auth/calendar.readonly https://www.googleapis.com/auth/gmail.readonly")) })).forEach { binding ->
            val tool = GoogleWorkspaceToolProvider { GoogleTransport { _, _ -> calls++; response("{}") } }.provideTools(mapper.readTree("{}"), "GW", binding).single { it.name == "GW__drive_metadata" }
            tool.executeWithJson("""{"fileId":"good"}""", binding).errorType shouldBe "AUTH_MISSING"
        }
        calls shouldBe 0
    }
    "gmail selected HTML and attachment reads are refused" {
        listOf("""{"mimeType":"text/html","body":{"data":"SGVsbG8"}}""", """{"mimeType":"text/plain","body":{"attachmentId":"SECRET"}}""").forEach { payload ->
            val result = call("gmail_read", """{"gmail":{"mailbox":"user@example.test","allowedMessageIds":["good"]}}""", """{"messageId":"good"}""") {
                if (it.path.endsWith("/profile")) response("""{"emailAddress":"user@example.test"}""")
                else response("""{"id":"good","labelIds":[],"payload":$payload}""")
            }
            result.errorType shouldBe "UNSUPPORTED"
            result.output.contains("SECRET") shouldBe false
        }
    }
    "gmail pagination exhaustion and metadata lookup error never return partial results" {
        val result = call("gmail_search", """{"gmail":{"mailbox":"user@example.test","allowedMessageIds":["good"]},"maxPages":1}""", "{}") {
            if (it.path.endsWith("/profile")) response("""{"emailAddress":"user@example.test"}""")
            else response("""{"messages":[{"id":"good"}],"nextPageToken":"SECRET"}""")
        }
        result.errorType shouldBe "LIMIT_EXCEEDED"
        result.output.contains("SECRET") shouldBe false
    }
    "allowed shortcut metadata does not expose target ID and target errors discard list" {
        val result = call("drive_metadata", """{"drive":{"allowedSharedDriveIds":["allowed"]}}""", """{"fileId":"shortcut"}""") {
            if (it.path.endsWith("shortcut")) response("""{"id":"shortcut","driveId":"allowed","name":"Visible","mimeType":"application/vnd.google-apps.shortcut","shortcutDetails":{"targetId":"target"}}""")
            else response("""{"id":"target","driveId":"allowed","mimeType":"text/plain"}""")
        }
        result.success shouldBe true
        result.output.contains("target") shouldBe false
    }
    "calendar invalid date window is a bounded input refusal" {
        call("calendar_events", """{"calendar":{"allowedCalendarIds":["allowed"]}}""", """{"calendarId":"allowed","timeMin":"2026-01-01T00:00:00Z","timeMax":"2026-12-01T00:00:00Z"}""") { error("No network") }.errorType shouldBe "INVALID_INPUT"
    }
    "malformed later Drive page discards already checked entries" {
        var count = 0
        val result = call("drive_list", """{"drive":{"allowMyDrive":true}}""", "{}") {
            count++
            if (count == 1) response("""{"files":[{"id":"good","ownedByMe":true,"mimeType":"text/plain","name":"SECRET"}],"nextPageToken":"next"}""")
            else response("""{"files":[{"id":"bad","name":"SECRET"}]}""")
        }
        result.errorType shouldBe "UPSTREAM_ERROR"
        result.output.contains("SECRET") shouldBe false
    }
    "shortcut target lookup failure discards whole list rather than treating partial check as denial" {
        val result = call("drive_list", """{"drive":{"allowedSharedDriveIds":["allowed"]}}""", "{}") {
            if (it.path == "/drive/v3/files") response("""{"files":[{"id":"good","driveId":"allowed","mimeType":"text/plain","name":"SECRET"},{"id":"shortcut","driveId":"allowed","mimeType":"application/vnd.google-apps.shortcut","shortcutDetails":{"targetId":"target"}}]}""")
            else GoogleResponse(500, "application/json", "SECRET")
        }
        result.errorType shouldBe "UPSTREAM_ERROR"
        result.output.contains("SECRET") shouldBe false
    }
    "gmail selected metadata lookup failure discards earlier selected messages" {
        val result = call("gmail_search", """{"gmail":{"mailbox":"user@example.test","allowedMessageIds":["good","bad"]}}""", "{}") {
            when {
                it.path.endsWith("/profile") -> response("""{"emailAddress":"user@example.test"}""")
                it.path.endsWith("/messages") -> response("""{"messages":[{"id":"good"},{"id":"bad"}]}""")
                it.path.endsWith("/good") -> response("""{"id":"good","labelIds":[],"payload":{"headers":[{"name":"Subject","value":"SECRET"}]}}""")
                else -> GoogleResponse(500, "application/json", "SECRET")
            }
        }
        result.errorType shouldBe "UPSTREAM_ERROR"
        result.output.contains("SECRET") shouldBe false
    }
    "direct get identity mismatch and missing drive facts refuse raw metadata" {
        val config = """{"drive":{"allowMyDrive":true}}"""
        val args = """{"fileId":"good"}"""
        call("drive_metadata", config, args) { response("""{"id":"other","ownedByMe":true,"mimeType":"text/plain","name":"SECRET"}""") }.errorType shouldBe "UPSTREAM_ERROR"
        call("drive_metadata", config, args) { response("""{"id":"good","name":"SECRET"}""") }.errorType shouldBe "UPSTREAM_ERROR"
    }
    "denies Google-accessible file in blocked shared drive before returning metadata" {
        val requests = mutableListOf<String>()
        val transport = GoogleTransport { request, _ ->
            requests.add(request.path)
            GoogleResponse(200, "application/json", """{"id":"file1","driveId":"blocked","name":"DO_NOT_LEAK","mimeType":"text/plain"}""")
        }
        val tools = GoogleWorkspaceToolProvider { transport }.provideTools(
            mapper.readTree("""{"drive":{"allowedSharedDriveIds":["allowed"],"blockedSharedDriveIds":["blocked"]}}"""), "GW", context)
        val result = tools.single { it.name == "GW__drive_metadata" }.executeWithJson("""{"fileId":"file1"}""", context)
        result.success shouldBe false
        result.errorType shouldBe "POLICY_DENIED"
        result.output.contains("DO_NOT_LEAK") shouldBe false
        requests shouldBe listOf("/drive/v3/files/file1")
    }
})
