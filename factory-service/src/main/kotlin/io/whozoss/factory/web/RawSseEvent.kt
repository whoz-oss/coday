package io.whozoss.factory.web

import org.springframework.http.MediaType
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * An [SseEmitter.SseEventBuilder] that writes a pre-framed raw SSE frame
 * verbatim (no Spring `event:`/`data:` re-formatting), so the wire bytes are
 * exactly the Node control plane's.
 */
class RawSseEvent(private val frame: String) : SseEmitter.SseEventBuilder {
    override fun id(id: String): SseEmitter.SseEventBuilder = this
    override fun name(eventName: String): SseEmitter.SseEventBuilder = this
    override fun reconnectTime(reconnectTime: Long): SseEmitter.SseEventBuilder = this
    override fun comment(comment: String): SseEmitter.SseEventBuilder = this
    override fun data(`object`: Any): SseEmitter.SseEventBuilder = this
    override fun data(`object`: Any, mediaType: MediaType?): SseEmitter.SseEventBuilder = this

    override fun build(): MutableSet<ResponseBodyEmitter.DataWithMediaType> =
        mutableSetOf(ResponseBodyEmitter.DataWithMediaType(frame, MediaType.TEXT_PLAIN))
}
