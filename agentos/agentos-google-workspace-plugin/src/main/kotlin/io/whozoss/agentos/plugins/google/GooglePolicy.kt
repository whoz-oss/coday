package io.whozoss.agentos.plugins.google

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.Collections

/**
 * Copied, validated policy snapshot; no JsonNode or mutable collection crosses into execution.
 *
 * Every configuration refusal names the offending field as a path built only from constant keys of the
 * schema and array indexes (e.g. `calendar.allowedCalendarIds[0]`), plus a constant human reason. Neither is
 * ever built from a configuration value or an unknown key chosen by the author: the refusal tells an
 * administrator where to look and what rule was broken, without carrying content. The same refusal is shown
 * at save time (host-side validation) and at tool resolution (load failure).
 */
internal class GooglePolicy private constructor(
    val allowedDrives: Set<String>, val blockedDrives: Set<String>, val myDrive: Boolean,
    val calendars: Set<String>, val mailbox: String?, val messages: Set<String>, val labels: Set<String>,
    val maxPages: Int, val maxItems: Int, val maxOutputChars: Int,
) {
    fun validate() {
        requireGoverned(maxPages in 1..10, "INVALID_CONFIG", "maxPages", "must be an integer between 1 and 10")
        requireGoverned(maxItems in 1..100, "INVALID_CONFIG", "maxItems", "must be an integer between 1 and 100")
        requireGoverned(maxOutputChars in 1024..65536, "INVALID_CONFIG", "maxOutputChars", "must be an integer between 1024 and 65536")
        requireGoverned((allowedDrives intersect blockedDrives).isEmpty(), "INVALID_CONFIG", "drive.blockedSharedDriveIds",
            "a shared drive ID cannot be both allowed and blocked")
        (allowedDrives + blockedDrives + calendars + messages + labels).forEach { id(it) }
        requireGoverned(mailbox == null || mailbox.matches(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")), "INVALID_CONFIG", "gmail.mailbox",
            "must be the exact email address of the connected Google account")
        requireGoverned(mailbox != null || (messages.isEmpty() && labels.isEmpty()), "INVALID_CONFIG", "gmail.mailbox",
            "is required as soon as Gmail message or label IDs are listed; leave the whole gmail section empty to keep Gmail disabled")
    }
    companion object {
        private val mapper = jacksonObjectMapper()
        private const val ROOT = "parameters"
        private const val BARE_ID = "must be a bare ID (letters, digits and _ @ . + - only, 1 to 254 characters, not primary or me), not a URL or a display name"
        fun id(value: String, field: String? = null) =
            requireGoverned(value.length in 1..254 && value.matches(Regex("[A-Za-z0-9_@.+-]+")) && value !in setOf(".", "..", "primary", "me"), "INVALID_INPUT", field, BARE_ID)
        private fun obj(node: JsonNode, keys: Set<String>, path: String) {
            requireGoverned(node.isObject && node.fieldNames().asSequence().all { it in keys }, "INVALID_CONFIG", path,
                "must be an object with only these keys: ${keys.sorted().joinToString(", ")}")
        }
        private fun ids(node: JsonNode?, path: String): Set<String> {
            requireGoverned(node == null || (node.isArray && node.size() <= 100 && node.all { it.isTextual }), "INVALID_CONFIG", path,
                "must be a list of at most 100 text IDs")
            val values = node?.mapIndexed { index, item -> item.textValue().also { value -> id(value, "$path[$index]") } } ?: emptyList()
            requireGoverned(values.distinct().size == values.size, "INVALID_CONFIG", path, "must not contain the same ID twice")
            return Collections.unmodifiableSet(values.toSet())
        }
        fun parse(node: JsonNode?): GooglePolicy {
            requireGoverned(node != null, "INVALID_CONFIG", ROOT, "are missing; save at least an empty object")
            obj(node!!, setOf("drive", "calendar", "gmail", "maxPages", "maxItems", "maxOutputChars"), ROOT)
            val d = node.get("drive") ?: mapper.createObjectNode()
            val c = node.get("calendar") ?: mapper.createObjectNode()
            val g = node.get("gmail") ?: mapper.createObjectNode()
            obj(d, setOf("allowedSharedDriveIds", "blockedSharedDriveIds", "allowMyDrive"), "drive")
            obj(c, setOf("allowedCalendarIds"), "calendar")
            obj(g, setOf("mailbox", "allowedMessageIds", "requiredLabelIds"), "gmail")
            requireGoverned(!d.has("allowMyDrive") || d.get("allowMyDrive").isBoolean, "INVALID_CONFIG", "drive.allowMyDrive", "must be true or false")
            requireGoverned(!g.has("mailbox") || g.get("mailbox").isTextual || g.get("mailbox").isNull, "INVALID_CONFIG", "gmail.mailbox", "must be text")
            // A form leaves an untouched text field as "" or null: both mean "Gmail not configured", not an invalid address.
            val mailbox = g.get("mailbox")?.takeIf { it.isTextual }?.textValue()?.trim()?.takeIf { it.isNotEmpty() }
            fun limit(key: String, default: Int): Int {
                requireGoverned(!node.has(key) || node.get(key).isInt, "INVALID_CONFIG", key, "must be an integer")
                return node.get(key)?.intValue() ?: default
            }
            return GooglePolicy(ids(d.get("allowedSharedDriveIds"), "drive.allowedSharedDriveIds"), ids(d.get("blockedSharedDriveIds"), "drive.blockedSharedDriveIds"), d.get("allowMyDrive")?.booleanValue() ?: false,
                ids(c.get("allowedCalendarIds"), "calendar.allowedCalendarIds"), mailbox, ids(g.get("allowedMessageIds"), "gmail.allowedMessageIds"), ids(g.get("requiredLabelIds"), "gmail.requiredLabelIds"),
                limit("maxPages", 3), limit("maxItems", 50), limit("maxOutputChars", 16384)).also { it.validate() }
        }
        val schema: JsonNode = mapper.readTree("""{
          "type":"object","additionalProperties":false,
          "description":"Each service (Drive, Calendar, Gmail) is denied until you list what the agent may read. Leave a section empty to keep that service disabled. To forbid a service entirely, also leave its read-only scope out of the bound Auth Setting: Google then refuses it whatever this configuration says.",
          "properties":{
            "drive":{"type":"object","title":"Drive","additionalProperties":false,
              "description":"Optional. Disabled unless a shared drive is allowed or allowMyDrive is true.",
              "properties":{
              "allowedSharedDriveIds":{"type":"array","title":"Allowed shared drive IDs","description":"Bare IDs (the last segment of the shared drive URL). Only files of these shared drives are visible.","maxItems":100,"uniqueItems":true,"items":{"type":"string"}},
              "blockedSharedDriveIds":{"type":"array","title":"Blocked shared drive IDs","description":"Explicitly denied shared drives. An ID cannot appear in both lists.","maxItems":100,"uniqueItems":true,"items":{"type":"string"}},
              "allowMyDrive":{"type":"boolean","title":"Allow My Drive","description":"When true, files you own in your own My Drive are visible (not files shared with you).","default":false}}},
            "calendar":{"type":"object","title":"Calendar","additionalProperties":false,
              "description":"Optional. Disabled unless at least one calendar is listed.",
              "properties":{
              "allowedCalendarIds":{"type":"array","title":"Allowed calendar IDs","description":"Exact calendar IDs (an email-like address such as team@group.calendar.google.com, or your own email for your main calendar). The aliases primary and me are refused.","maxItems":100,"uniqueItems":true,"items":{"type":"string"}}}},
            "gmail":{"type":"object","title":"Gmail","additionalProperties":false,
              "description":"Optional and disabled by default: leave this whole section empty and no email can ever be read. Gmail only works when mailbox AND at least one message ID are set, and then only those exact messages are readable.",
              "properties":{
              "mailbox":{"type":"string","title":"Mailbox (safety check)","description":"Not a grant: the exact email of the Google account expected behind the connection. Every Gmail call first checks that the connected account is this one, so a token of another account can never be used. Required only when message IDs are listed."},
              "allowedMessageIds":{"type":"array","title":"Allowed message IDs","description":"The fixed list of messages the agent may read. Nothing else in the mailbox is readable, whatever the search.","maxItems":100,"uniqueItems":true,"items":{"type":"string"}},
              "requiredLabelIds":{"type":"array","title":"Required label IDs","description":"Optional extra filter: a listed message is readable only if it also carries all these labels. Labels never grant access on their own.","maxItems":100,"uniqueItems":true,"items":{"type":"string"}}}},
            "maxPages":{"type":"integer","title":"Max pages per list","minimum":1,"maximum":10,"default":3},
            "maxItems":{"type":"integer","title":"Max items per list","minimum":1,"maximum":100,"default":50},
            "maxOutputChars":{"type":"integer","title":"Max output characters","minimum":1024,"maximum":65536,"default":16384}
          }}""")
    }
}
