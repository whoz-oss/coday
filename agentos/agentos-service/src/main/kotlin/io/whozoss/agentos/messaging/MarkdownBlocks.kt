package io.whozoss.agentos.messaging

/**
 * The block structure of the Markdown agents write, as the messaging adapters need it: code blocks, tables and
 * runs of text, and chunks short enough for one message of a platform.
 */
object MarkdownBlocks {
    val FENCE = Regex("""^\s*(```|~~~)\s*([\w+#.-]*)\s*$""")
    private val TABLE_ROW = Regex("""^\s*\|.*\|\s*$""")

    enum class Kind { TEXT, CODE, TABLE }

    data class Block(
        val kind: Kind,
        /** The block's Markdown, fences included for code. */
        val text: String,
        val language: String = "",
    )

    /**
     * Cuts [markdown] into chunks of at most [maxLength] characters, between blocks when it can, and never inside
     * a code block without closing and reopening it.
     */
    fun split(
        markdown: String,
        maxLength: Int,
    ): List<String> =
        blocks(markdown)
            .flatMap { block -> if (block.text.length > maxLength) cut(block, maxLength) else listOf(block.text) }
            .fold(mutableListOf<String>()) { chunks, block ->
                val last = chunks.lastOrNull()
                when {
                    last != null && last.length + block.length + 2 <= maxLength ->
                        chunks[chunks.lastIndex] = last + "\n\n" + block
                    else -> chunks.add(block)
                }
                chunks
            }.ifEmpty { mutableListOf("") }

    /** Groups lines into code blocks, tables and runs of text separated by blank lines. */
    fun blocks(markdown: String): List<Block> {
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
                    blocks.add(Block(Kind.CODE, code, language = fence.groupValues[2]))
                    index = closing + 1
                }
                TABLE_ROW.matches(line) -> {
                    val end = (index until lines.size).firstOrNull { !TABLE_ROW.matches(lines[it]) } ?: lines.size
                    blocks.add(Block(Kind.TABLE, lines.subList(index, end).joinToString("\n")))
                    index = end
                }
                line.isBlank() -> index++
                else -> {
                    val end =
                        (index until lines.size).firstOrNull {
                            lines[it].isBlank() || FENCE.matches(lines[it]) || TABLE_ROW.matches(lines[it])
                        } ?: lines.size
                    blocks.add(Block(Kind.TEXT, lines.subList(index, end).joinToString("\n")))
                    index = end
                }
            }
        }
        return blocks
    }

    /** Cuts a block longer than a chunk by lines, closing and reopening the fence of a code block. */
    private fun cut(
        block: Block,
        maxLength: Int,
    ): List<String> {
        val (open, body, close) =
            when (block.kind) {
                Kind.CODE -> {
                    val lines = block.text.lines()
                    val hasClosing = lines.size > 1 && FENCE.matches(lines.last())
                    Triple(lines.first(), lines.drop(1).dropLast(if (hasClosing) 1 else 0), lines.first().trim().take(3))
                }
                else -> Triple("", block.text.lines(), "")
            }
        val room = maxLength - open.length - close.length - 2
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
                    Kind.CODE -> (listOf(open) + piece + close).joinToString("\n")
                    else -> piece.joinToString("\n")
                }
            }
    }
}
