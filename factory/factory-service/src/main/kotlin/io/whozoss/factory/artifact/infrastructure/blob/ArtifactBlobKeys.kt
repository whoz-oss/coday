package io.whozoss.factory.artifact.infrastructure.blob

import java.nio.charset.StandardCharsets
import java.util.Base64

/** Unambiguous, organization-owned object prefixes; no raw identifiers become paths. */
object ArtifactBlobKeys {
    fun organizationPrefix(root: String, organizationId: String): String =
        root.trimEnd('/') + "/" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(organizationId.toByteArray(StandardCharsets.UTF_8)) + "/"
}
