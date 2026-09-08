package io.whozoss.agentos.agent

import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.ToolRequestEvent
import io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent

/** Which tool exchanges are replayed in full, and which keep their image attachments. */
data class ToolReplayPlan(
    private val detailedRequestIds: Set<String>,
    private val mediaRequestIds: Set<String>,
) {
    fun isDetailed(requestId: String): Boolean = requestId in detailedRequestIds

    fun hasAttachedMedia(requestId: String): Boolean = requestId in mediaRequestIds
}

/**
 * Newest-first budget walk over tool exchanges.
 *
 * A request is replayed in detail while its cumulated char cost fits [maxDetailedChars].
 * Its images are attached while they fit [maxAttachedImages], and only attached images
 * are charged [imageCharCost] against the budget. Responses whose images are not attached
 * keep their text summary plus a marker and cost only that text.
 *
 * Setting [maxDetailedChars] to [Int.MAX_VALUE] disables text compression entirely
 * (AgentSimple behaviour: all exchanges are detailed, only the image cap applies).
 */
class ToolReplayPlanner(
    private val maxDetailedChars: Int,
    private val maxAttachedImages: Int,
    private val imageCharCost: Int,
) {
    /**
     * Immutable state of the newest-first walk after considering one exchange.
     *
     * [fits] tells whether this exchange itself still fits the budget; once it is `false`,
     * the walk must stop (prefix policy: all older exchanges are dropped too), which is why
     * [plan] uses [Sequence.takeWhile] rather than a filter.
     */
    private data class Step(
        val requestId: String,
        val fits: Boolean,
        val withMedia: Boolean,
        val chars: Long,
        val images: Int,
    )

    fun plan(events: List<CaseEvent>): ToolReplayPlan {
        val responses = events.filterIsInstance<ToolResponseEvent>().associateBy { it.toolRequestId }

        val kept =
            events
                .filterIsInstance<ToolRequestEvent>()
                .asReversed()
                .asSequence()
                .map { it to responses[it.toolRequestId] }
                .runningFold(START) { acc, (request, response) -> acc.next(request, response) }
                .drop(1)
                .takeWhile { it.fits }
                .toList()

        return ToolReplayPlan(
            detailedRequestIds = kept.mapTo(mutableSetOf()) { it.requestId },
            mediaRequestIds = kept.filter { it.withMedia }.mapTo(mutableSetOf()) { it.requestId },
        )
    }

    /**
     * Pure transition from this [Step] to the next one, given the following exchange
     * (older, since the walk proceeds newest-first) in the sequence.
     */
    private fun Step.next(
        request: ToolRequestEvent,
        response: ToolResponseEvent?,
    ): Step {
        val responseImages = response?.images.orEmpty()
        val attachMedia = responseImages.isNotEmpty() && images + responseImages.size <= maxAttachedImages
        val cost = charCost(request, response, attachMedia)
        val fits = chars + cost <= maxDetailedChars
        return Step(
            requestId = request.toolRequestId,
            fits = fits,
            withMedia = fits && attachMedia,
            chars = if (fits) chars + cost else chars,
            images = if (fits && attachMedia) images + responseImages.size else images,
        )
    }

    /**
     * Char-equivalent cost for one request/response pair:
     * args length + response text length + image cost (only when [attachMedia] is true).
     */
    private fun charCost(
        request: ToolRequestEvent,
        response: ToolResponseEvent?,
        attachMedia: Boolean,
    ): Long {
        val argsCost = request.args?.length ?: 0
        val responseCost = response?.let { extractText(it.output).length } ?: 0
        val imagesCount = if (attachMedia) response?.images?.size ?: 0 else 0
        return argsCost.toLong() + responseCost.toLong() + imagesCount.toLong() * imageCharCost.toLong()
    }

    private fun extractText(content: MessageContent): String =
        when (content) {
            is MessageContent.Text -> content.content
            is MessageContent.Image -> "[image ${content.mimeType} ${content.width}x${content.height}]"
        }

    private companion object {
        val START = Step(requestId = "", fits = true, withMedia = false, chars = 0L, images = 0)
    }
}
