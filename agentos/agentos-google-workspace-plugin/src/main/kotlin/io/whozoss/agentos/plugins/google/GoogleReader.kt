package io.whozoss.agentos.plugins.google

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

internal class GoogleReader(private val policy: GooglePolicy, private val transport: GoogleTransport, private val token: String) {
    private val mapper = jacksonObjectMapper()
    private fun json(path: String, query: Map<String, String> = emptyMap()): JsonNode {
        val response = transport.get(GoogleRequest(path, query), token)
        requireGoverned(response.status == 200 && response.contentType.substringBefore(';') == "application/json" && response.body.length <= 262144, "UPSTREAM_ERROR")
        return mapper.readTree(response.body).also { requireGoverned(it.isObject, "UPSTREAM_ERROR") }
    }
    private fun projected(node: JsonNode, keys: List<String>): JsonNode = mapper.createObjectNode().apply {
        keys.forEach { key -> node.get(key)?.let { value ->
            requireGoverned(value.isValueNode, "UPSTREAM_ERROR")
            set<JsonNode>(key, value)
        } }
    }
    private fun text(node: JsonNode, key: String): String {
        requireGoverned(node.has(key) && node.get(key).isTextual && node.get(key).textValue().isNotBlank(), "UPSTREAM_ERROR")
        return node.get(key).textValue()
    }
    /** Full pagination is internal. No result escapes until all pages and checks succeed. */
    private fun pages(path: String, key: String, query: Map<String, String>): List<JsonNode> {
        val items = mutableListOf<JsonNode>()
        val seen = mutableSetOf<String>()
        var cursor: String? = null
        var count = 0
        do {
            requireGoverned(count++ < policy.maxPages, "LIMIT_EXCEEDED")
            val page = json(path, query + (cursor?.let { mapOf("pageToken" to it) } ?: emptyMap()))
            requireGoverned(!page.has("incompleteSearch") || (page.get("incompleteSearch").isBoolean && !page.get("incompleteSearch").booleanValue()), "UPSTREAM_ERROR")
            requireGoverned(!page.has(key) || page.get(key).isArray, "UPSTREAM_ERROR")
            page.get(key)?.forEach { item ->
                requireGoverned(item.isObject, "UPSTREAM_ERROR")
                items.add(item)
                requireGoverned(items.size <= policy.maxItems, "LIMIT_EXCEEDED")
            }
            cursor = page.get("nextPageToken")?.let { value ->
                requireGoverned(value.isTextual && value.textValue().length in 1..2048 && value.textValue().none { it.isISOControl() }, "UPSTREAM_ERROR")
                value.textValue().also { requireGoverned(seen.add(it), "UPSTREAM_ERROR") }
            }
        } while (cursor != null)
        return items
    }
    private val driveFields = "id,name,mimeType,driveId,ownedByMe,trashed,shortcutDetails(targetId,targetMimeType)"
    private fun driveAllowed(file: JsonNode): Boolean {
        text(file, "id"); text(file, "mimeType")
        requireGoverned(!file.has("trashed") || file.get("trashed").isBoolean, "UPSTREAM_ERROR")
        val drive = file.get("driveId")
        requireGoverned(drive == null || (drive.isTextual && drive.textValue().isNotBlank()), "UPSTREAM_ERROR")
        requireGoverned(!file.has("ownedByMe") || file.get("ownedByMe").isBoolean, "UPSTREAM_ERROR")
        return !file.path("trashed").asBoolean(false) && when (drive) {
            null -> policy.myDrive && file.path("ownedByMe").asBoolean(false)
            else -> drive.textValue() in policy.allowedDrives && drive.textValue() !in policy.blockedDrives
        }
    }
    private fun driveFile(id: String): JsonNode {
        GooglePolicy.id(id)
        val file = json("/drive/v3/files/$id", mapOf("fields" to driveFields, "supportsAllDrives" to "true"))
        requireGoverned(text(file, "id") == id, "UPSTREAM_ERROR")
        requireGoverned(driveAllowed(file))
        return file
    }
    private fun checkShortcut(file: JsonNode): Boolean = when (text(file, "mimeType")) {
        "application/vnd.google-apps.shortcut" -> {
            val details = file.get("shortcutDetails") ?: throw Refusal("UPSTREAM_ERROR")
            val targetId = text(details, "targetId")
            GooglePolicy.id(targetId)
            val target = driveFile(targetId)
            // Deliberately no recursive shortcut traversal or dereferenced content.
            requireGoverned(text(target, "mimeType") != "application/vnd.google-apps.shortcut", "UNSUPPORTED")
            true
        }
        else -> true
    }
    private fun driveMetadata(file: JsonNode) = projected(file, listOf("id", "name", "mimeType"))
    private fun driveList(args: JsonNode): JsonNode {
        requireGoverned(policy.myDrive || policy.allowedDrives.isNotEmpty())
        val q = args.get("query")?.textValue()?.let { name -> "trashed = false and name contains '${name.replace("\\", "\\\\").replace("'", "\\'")}'" } ?: "trashed = false"
        val files = pages("/drive/v3/files", "files", mapOf("q" to q, "pageSize" to "100", "spaces" to "drive", "corpora" to "user", "includeItemsFromAllDrives" to "true", "supportsAllDrives" to "true", "fields" to "nextPageToken,incompleteSearch,files($driveFields)"))
        val result = files.filter { file ->
            driveAllowed(file) && try { checkShortcut(file) } catch (e: Refusal) {
                when (e.code) { "POLICY_DENIED", "UNSUPPORTED" -> false; else -> throw e }
            }
        }.distinctBy { text(it, "id") }.map { driveMetadata(it) }
        return mapper.createObjectNode().set<JsonNode>("files", mapper.valueToTree(result))
    }
    private fun driveContent(id: String, export: Boolean): JsonNode {
        val before = driveFile(id)
        val mime = text(before, "mimeType")
        requireGoverned(mime == (if (export) "application/vnd.google-apps.document" else "text/plain"), "UNSUPPORTED")
        val response = transport.get(if (export) GoogleRequest("/drive/v3/files/$id/export", mapOf("mimeType" to "text/plain"))
            else GoogleRequest("/drive/v3/files/$id", mapOf("alt" to "media", "supportsAllDrives" to "true")), token)
        requireGoverned(response.status == 200 && response.contentType.substringBefore(';') == "text/plain", "UPSTREAM_ERROR")
        requireGoverned(response.body.length <= policy.maxOutputChars / 2, "LIMIT_EXCEEDED")
        val after = driveFile(id)
        requireGoverned(before == after, "UPSTREAM_ERROR")
        return mapper.createObjectNode().put("id", id).put("text", response.body)
    }
    private fun calendarEvent(node: JsonNode): JsonNode {
        GooglePolicy.id(text(node, "id"))
        return mapper.createObjectNode().apply {
            val flat = projected(node, listOf("id", "summary", "description", "location", "status"))
            flat.properties().forEach { (k, v) -> set<JsonNode>(k, v) }
            listOf("start", "end").forEach { key -> node.get(key)?.let {
                requireGoverned(it.isObject, "UPSTREAM_ERROR")
                set<JsonNode>(key, projected(it, listOf("date", "dateTime", "timeZone")))
            } }
        }
    }
    private fun calendar(operation: GoogleOperation, args: JsonNode): JsonNode {
        val calendarId = args.get("calendarId").textValue()
        requireGoverned(calendarId in policy.calendars)
        val path = "/calendar/v3/calendars/$calendarId/events"
        return when (operation) {
            GoogleOperation.CALENDAR_GET -> {
                val id = args.get("eventId").textValue()
                val event = json("$path/$id", mapOf("fields" to "id,summary,description,location,status,start,end"))
                requireGoverned(text(event, "id") == id, "UPSTREAM_ERROR")
                calendarEvent(event)
            }
            else -> {
                val min = java.time.OffsetDateTime.parse(args.get("timeMin").textValue()).toInstant()
                val max = java.time.OffsetDateTime.parse(args.get("timeMax").textValue()).toInstant()
                requireGoverned(max > min && java.time.Duration.between(min, max) <= java.time.Duration.ofDays(31), "INVALID_INPUT")
                val events = pages(path, "items", mapOf("timeMin" to min.toString(), "timeMax" to max.toString(), "maxResults" to "100", "singleEvents" to "true", "fields" to "nextPageToken,items(id,summary,description,location,status,start,end)"))
                mapper.createObjectNode().set<JsonNode>("events", mapper.valueToTree(events.map { calendarEvent(it) }.distinct()))
            }
        }
    }
    private fun mailbox() {
        requireGoverned(policy.mailbox != null && policy.messages.isNotEmpty())
        val profile = json("/gmail/v1/users/me/profile", mapOf("fields" to "emailAddress"))
        requireGoverned(text(profile, "emailAddress") == policy.mailbox)
    }
    private fun message(id: String, full: Boolean = false): JsonNode {
        requireGoverned(id in policy.messages)
        val message = json("/gmail/v1/users/me/messages/$id", mapOf("format" to if (full) "full" else "metadata", "fields" to if (full) "id,labelIds,payload" else "id,labelIds,payload(headers)"))
        requireGoverned(text(message, "id") == id, "UPSTREAM_ERROR")
        val labels = message.get("labelIds")
        requireGoverned(labels != null && labels.isArray && labels.all { it.isTextual }, "UPSTREAM_ERROR")
        requireGoverned(labels!!.map { it.textValue() }.containsAll(policy.labels))
        return message
    }
    private fun messageMetadata(node: JsonNode): JsonNode {
        val payload = node.get("payload") ?: throw Refusal("UPSTREAM_ERROR")
        requireGoverned(payload.isObject, "UPSTREAM_ERROR")
        val headers = payload.get("headers")
        requireGoverned(headers == null || headers.isArray, "UPSTREAM_ERROR")
        val result = mapper.createObjectNode().put("id", text(node, "id"))
        val safe = mapper.createObjectNode()
        headers?.forEach { header ->
            val name = text(header, "name").lowercase()
            if (name in setOf("subject", "from", "to", "date")) safe.put(name, text(header, "value"))
        }
        result.set<JsonNode>("headers", safe)
        // Label IDs are selection facts only; do not return unselected labels.
        return result
    }
    private fun gmail(operation: GoogleOperation, args: JsonNode): JsonNode {
        if (operation != GoogleOperation.GMAIL_SEARCH) requireGoverned(args.get("messageId").textValue() in policy.messages)
        mailbox()
        return when (operation) {
            GoogleOperation.GMAIL_SEARCH -> {
                val query = mapOf("maxResults" to "100", "fields" to "nextPageToken,messages(id)") + (args.get("query")?.let { mapOf("q" to it.textValue()) } ?: emptyMap())
                val candidates = pages("/gmail/v1/users/me/messages", "messages", query)
                val selected = candidates.map { text(it, "id") }.filter { it in policy.messages }.distinct().mapNotNull { id ->
                    try { messageMetadata(message(id)) } catch (e: Refusal) {
                        if (e.code == "POLICY_DENIED") null else throw e
                    }
                }
                mapper.createObjectNode().set<JsonNode>("messages", mapper.valueToTree(selected))
            }
            GoogleOperation.GMAIL_METADATA -> messageMetadata(message(args.get("messageId").textValue()))
            else -> {
                val id = args.get("messageId").textValue()
                val before = message(id, true)
                val payload = before.get("payload") ?: throw Refusal("UPSTREAM_ERROR")
                requireGoverned(text(payload, "mimeType") == "text/plain" && !payload.has("parts") && !payload.path("body").has("attachmentId"), "UNSUPPORTED")
                val encoded = text(payload.path("body"), "data")
                requireGoverned(encoded.length <= policy.maxOutputChars, "LIMIT_EXCEEDED")
                val bytes = java.util.Base64.getUrlDecoder().decode(encoded)
                val content = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                requireGoverned(content.length <= policy.maxOutputChars / 2, "LIMIT_EXCEEDED")
                val after = message(id, true)
                requireGoverned(before == after, "UPSTREAM_ERROR")
                (messageMetadata(before) as com.fasterxml.jackson.databind.node.ObjectNode).put("text", content)
            }
        }
    }
    fun execute(operation: GoogleOperation, args: JsonNode): JsonNode = when (operation) {
        GoogleOperation.DRIVE_LIST -> driveList(args)
        GoogleOperation.DRIVE_METADATA -> driveFile(args.get("fileId").textValue()).also { checkShortcut(it) }.let { driveMetadata(it) }
        GoogleOperation.DRIVE_READ -> driveContent(args.get("fileId").textValue(), false)
        GoogleOperation.DRIVE_EXPORT -> driveContent(args.get("fileId").textValue(), true)
        GoogleOperation.CALENDAR_LIST, GoogleOperation.CALENDAR_GET -> calendar(operation, args)
        GoogleOperation.GMAIL_SEARCH, GoogleOperation.GMAIL_METADATA, GoogleOperation.GMAIL_READ -> gmail(operation, args)
    }
}
