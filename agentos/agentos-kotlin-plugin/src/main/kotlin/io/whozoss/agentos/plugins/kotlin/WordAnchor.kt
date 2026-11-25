package io.whozoss.agentos.plugins.kotlin

/**
 * Finds a symbol name on a line of text, so that agents point at code with a name
 * instead of counting columns.
 */
internal object WordAnchor {
    /**
     * 0-based column of the first whole-word occurrence of [word] in [lineText] at or after [fromColumn],
     * `null` when there is none. `load` is not found in `reload` nor `loader`.
     */
    fun find(
        lineText: String,
        word: String,
        fromColumn: Int = 0,
    ): Int? =
        if (word.isBlank()) {
            null
        } else {
            generateSequence(occurrenceAtOrAfter(lineText = lineText, word = word, from = fromColumn.coerceAtLeast(0))) { previous ->
                occurrenceAtOrAfter(lineText = lineText, word = word, from = previous + 1)
            }.firstOrNull { column -> isWholeWord(lineText = lineText, column = column, length = word.length) }
        }

    private fun occurrenceAtOrAfter(
        lineText: String,
        word: String,
        from: Int,
    ): Int? = lineText.indexOf(word, startIndex = from).takeIf { it >= 0 }

    private fun isWholeWord(
        lineText: String,
        column: Int,
        length: Int,
    ): Boolean {
        val before = lineText.getOrNull(column - 1)
        val after = lineText.getOrNull(column + length)
        return !isIdentifierPart(before) && !isIdentifierPart(after)
    }

    private fun isIdentifierPart(character: Char?): Boolean = character != null && Character.isJavaIdentifierPart(character)
}
