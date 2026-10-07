package io.whozoss.factory.artifact.infrastructure.blob

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

/** Unit tests for the in-memory blob client used by local runs and offline tests. */
class InMemoryArtifactBlobClientTest {

    private val client = InMemoryArtifactBlobClient()

    @Test
    fun `put, head and get round-trip the payload`() {
        client.putObject("objects/a", "hello".toByteArray(), "text/plain")

        assertThat(client.headObject("objects/a")).isTrue()
        assertThat(client.headObject("objects/missing")).isFalse()
        assertThat(client.getObject("objects/a")!!.readBytes()).isEqualTo("hello".toByteArray())
        assertThat(client.getObject("objects/missing")).isNull()
        assertThat(client.contentTypeOf("objects/a")).isEqualTo("text/plain")
    }

    @Test
    fun `copyObject duplicates a payload and fails on a missing source`() {
        client.putObject("uploads/x.part", "payload".toByteArray(), "application/octet-stream")
        client.copyObject("uploads/x.part", "objects/x")

        assertThat(client.getObject("objects/x")!!.readBytes()).isEqualTo("payload".toByteArray())
        assertThatThrownBy { client.copyObject("uploads/missing.part", "objects/y") }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `deleteObject reports whether a deletion happened`() {
        client.putObject("objects/a", "hello".toByteArray())

        assertThat(client.deleteObject("objects/a")).isTrue()
        assertThat(client.deleteObject("objects/a")).isFalse()
        assertThat(client.headObject("objects/a")).isFalse()
    }

    @Test
    fun `listObjectKeys filters by prefix`() {
        client.putObject("uploads/1.part", ByteArray(0))
        client.putObject("uploads/2.part", ByteArray(0))
        client.putObject("objects/keep", ByteArray(0))

        assertThat(client.listObjectKeys("uploads/")).containsExactlyInAnyOrder("uploads/1.part", "uploads/2.part")
        assertThat(client.listObjectKeys("objects/")).containsExactly("objects/keep")
    }

    @Test
    fun `getSignedUrl mocks a presigned URL with the default TTL`() {
        val url = client.getSignedUrl("objects/abc", now = Instant.parse("2026-01-01T00:00:00Z"))
        assertThat(url).isEqualTo("http://localhost:9000/mock-bucket/objects/abc?X-Amz-Expires=900&X-Amz-Signature=in-memory")

        val explicit = client.getSignedUrl("objects/abc", expiresInSeconds = 60)
        assertThat(explicit).contains("X-Amz-Expires=60")
    }
}
