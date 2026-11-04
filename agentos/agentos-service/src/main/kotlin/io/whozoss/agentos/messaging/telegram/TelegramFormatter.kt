package io.whozoss.agentos.messaging.telegram

/**
 * Turns the Markdown agents write into what Telegram renders: its HTML subset
 * (https://core.telegram.org/bots/api#html-style), in messages under its 4096-character limit.
 *
 * The conversion covers what agents use: fenced code, inline code, bold, italic, strikethrough, links,
 * headings (bold), quotes, lists and tables (kept as preformatted text, Telegram has no table). Anything else
 * goes through as escaped text. It is lenient by design: a message Telegram still refuses is sent again as
 * plain text by [TelegramAdapter].
 */
object TelegramFormatter {
    /** Telegram's limit on the text of one message. */
    const val MAX_MESSAGE_LENGTH = 4096

    /** Room left for the tags and escapes the conversion adds to a chunk of Markdown. */
    private const val MAX_CHUNK_LENGTH = 3500

    private val FENCE = Regex("""^\s*(```|~~~)\s*([\w+#.-]*)\s*$""")
    private val HEADING = Regex("""^\s{0,3}#{1,6}\s+(.*)$""")
    private val QUOTE = Regex("""^\s{0,3}>\s?(.*)$""")
    private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
    private val TABLE_ROW = Regex("""^\s*\|.*\|\s*$""")
    private val HORIZONTAL_RULE = Regex("""^\s{0,3}([-*_])(\s*\1){2,}\s*$""")

    private val INLINE_CODE = Regex("""`([^`\n]+)`""")
    private val LINK = Regex("""\[([^\]\n]+)]\((https?://[^)\s]+)\)""")
    private val BOLD = Regex("""\*\*(?=\S)(.+?)(?<=\S)\*\*|__(?=\S)(.+?)(?<=\S)__""")
    private val ITALIC =
        Regex("""(?<![\w*])\*(?=\S)([^*\n]+?)(?<=\S)\*(?![\w*])|(?<![\w_])_(?=\S)([^_\n]+?)(?<=\S)_(?![\w_])""")
    private val STRIKE = Regex("""~~(?=\S)(.+?)(?<=\S)~~""")

    /** Private-use characters, absent from agent text: they hold the place of inline code while the rest is converted. */
    private const val PLACEHOLDER_START = ''
    private const val PLACEHOLDER_END = ''

    /**
     * Cuts [markdown] into chunks of at most [MAX_CHUNK_LENGTH] characters, between blocks when it can, and
     * never inside a code block without closing and reopening it.
     */
    fun split(markdown: String): List<String> =
        blocks(markdown)
            .flatMap { block -> if (block.text.length > MAX_CHUNK_LENGTH) cut(block) else listOf(block.text) }
            .fold(mutableListOf<String>()) { chunks, block ->
                val last = chunks.lastOrNull()
                when {
                    last != null && last.length + block.length + 2 <= MAX_CHUNK_LENGTH ->
                        chunks[chunks.lastIndex] = last + "\n\n" + block
                    else -> chunks.add(block)
                }
                chunks
            }.ifEmpty { mutableListOf("") }

    /** Converts one chunk of Markdown to Telegram HTML. */
    fun toHtml(markdown: String): String =
        blocks(markdown).joinToString("\n\n") { block ->
            when (block.kind) {
                BlockKind.CODE -> codeToHtml(block)
                BlockKind.TABLE -> "<pre>${escape(block.text)}</pre>"
                BlockKind.TEXT -> block.text.lines().joinToString("\n", transform = ::lineToHtml)
            }
        }

    /** Escapes the three characters Telegram's HTML parser reads. */
    fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private enum class BlockKind { TEXT, CODE, TABLE }

    private data class Block(
        val kind: BlockKind,
        /** The block's Markdown, fences included for code. */
        val text: String,
        val language: String = "",
    )

    /** Groups lines into code blocks, tables and runs of text separated by blank lines. */
    private fun blocks(markdown: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val lines = markdown.replace("\r\n", "\n").lines()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val fence = FENCE.matchEntire(line)
            when {
                fence != null -> {
                    val marker = fence.groupValues[1]
                    val closing = (index + 1 until lines.size).firstOrNull { lines[it].trim() == marker } ?: lines.size
                    val code = lines.subList(index, minOf(closing + 1, lines.size)).joinToString("\n")
                    blocks.add(Block(BlockKind.CODE, code, language = fence.groupValues[2]))
                    index = closing + 1
                }
                TABLE_ROW.matches(line) -> {
                    val end = (index until lines.size).firstOrNull { !TABLE_ROW.matches(lines[it]) } ?: lines.size
                    blocks.add(Block(BlockKind.TABLE, lines.subList(index, end).joinToString("\n")))
                    index = end
                }
                line.isBlank() -> index++
                else -> {
                    val end =
                        (index until lines.size).firstOrNull {
                            lines[it].isBlank() || FENCE.matches(lines[it]) || TABLE_ROW.matches(lines[it])
                        } ?: lines.size
                    blocks.add(Block(BlockKind.TEXT, lines.subList(index, end).joinToString("\n")))
                    index = end
                }
            }
        }
        return blocks
    }

    /** Cuts a block longer than a chunk by lines, closing and reopening the fence of a code block. */
    private fun cut(block: Block): List<String> {
        val (open, body, close) =
            when (block.kind) {
                BlockKind.CODE -> {
                    val lines = block.text.lines()
                    val hasClosing = lines.size > 1 && FENCE.matches(lines.last())
                    Triple(lines.first(), lines.drop(1).dropLast(if (hasClosing) 1 else 0), lines.first().trim().take(3))
                }
                else -> Triple("", block.text.lines(), "")
            }
        val room = MAX_CHUNK_LENGTH - open.length - close.length - 2
        return body
            .flatMap { line -> line.chunked(room) }
            .fold(mutableListOf<MutableList<String>>()) { pieces, line ->
                val last = pieces.lastOrNull()
                when {
                    last != null && last.sumOf { it.length + 1 } + line.length <= room -> last.add(line)
                    else -> pieces.add(mutableListOf(line))
                }
                pieces
            }.map { piece ->
                when (block.kind) {
                    BlockKind.CODE -> (listOf(open) + piece + close).joinToString("\n")
                    else -> piece.joinToString("\n")
                }
            }
    }

    private fun codeToHtml(block: Block): String {
        val lines = block.text.lines()
        val hasClosing = lines.size > 1 && FENCE.matches(lines.last())
        val code = escape(lines.drop(1).dropLast(if (hasClosing) 1 else 0).joinToString("\n"))
        return when {
            block.language.isNotEmpty() ->
                """<pre><code class="language-${escapeAttribute(block.language)}">$code</code></pre>"""
            else -> "<pre>$code</pre>"
        }
    }

    private fun lineToHtml(line: String): String {
        val heading = HEADING.matchEntire(line)
        val quote = QUOTE.matchEntire(line)
        val bullet = BULLET.matchEntire(line)
        return when {
            HORIZONTAL_RULE.matches(line) -> "──────────"
            heading != null -> "<b>${inlineToHtml(heading.groupValues[1])}</b>"
            quote != null -> "<blockquote>${inlineToHtml(quote.groupValues[1])}</blockquote>"
            bullet != null -> "${bullet.groupValues[1]}• ${inlineToHtml(bullet.groupValues[2])}"
            else -> inlineToHtml(line)
        }
    }

    /** Converts inline Markdown: code spans are set aside first, so that nothing inside them is read as Markdown. */
    private fun inlineToHtml(text: String): String {
        val spans = mutableListOf<String>()
        val withoutCode =
            INLINE_CODE.replace(text) { match ->
                spans.add("<code>${escape(match.groupValues[1])}</code>")
                "$PLACEHOLDER_START${spans.lastIndex}$PLACEHOLDER_END"
            }
        val converted =
            escape(withoutCode)
                // The URL is escaped with the rest of the line already: only its quotes are left to escape.
                .let { LINK.replace(it) { m -> """<a href="${m.groupValues[2].replace("\"", "&quot;")}">${m.groupValues[1]}</a>""" } }
                .let { BOLD.replace(it) { m -> "<b>${m.groupValues[1].ifEmpty { m.groupValues[2] }}</b>" } }
                .let { ITALIC.replace(it) { m -> "<i>${m.groupValues[1].ifEmpty { m.groupValues[2] }}</i>" } }
                .let { STRIKE.replace(it) { m -> "<s>${m.groupValues[1]}</s>" } }
        return Regex("$PLACEHOLDER_START(\\d+)$PLACEHOLDER_END").replace(converted) { spans[it.groupValues[1].toInt()] }
    }

    private fun escapeAttribute(value: String): String = escape(value).replace("\"", "&quot;")
}
