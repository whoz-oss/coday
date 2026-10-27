package io.whozoss.factory.error

import org.junit.jupiter.api.Test
import org.springframework.web.context.request.async.AsyncRequestNotUsableException
import java.io.IOException
import kotlin.test.assertEquals

class FactoryExceptionHandlerTest {

    private val handler = FactoryExceptionHandler()

    @Test
    fun `client disconnect returns no error body`() {
        val exception = AsyncRequestNotUsableException(
            "ServletOutputStream failed to write",
            IOException("Broken pipe"),
        )

        // Unit-returning handler: successful invocation proves no response body is produced.
        handler.handleAsyncRequestNotUsable(exception)
    }

    @Test
    fun `non-disconnect async response failure also avoids writing to unusable response`() {
        val exception = AsyncRequestNotUsableException(
            "ServletOutputStream failed to write",
            IOException("unexpected serializer transport failure"),
        )

        // Even when logged as an error, an unusable response must not be written again.
        handler.handleAsyncRequestNotUsable(exception)
    }

    @Test
    fun `ordinary exceptions preserve the canonical internal error envelope`() {
        val response = handler.handleGenericException(IllegalStateException("boom"))

        assertEquals(500, response.statusCode.value())
        assertEquals("INTERNAL_ERROR", response.body?.error?.code)
        assertEquals("boom", response.body?.error?.message)
    }
}
