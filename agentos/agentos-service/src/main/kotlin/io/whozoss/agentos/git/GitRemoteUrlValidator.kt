package io.whozoss.agentos.git

import io.whozoss.agentos.exception.BadRequestException
import mu.KLogging
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException

/**
 * Validates the remote URL a namespace may be pointed at.
 *
 * The URL is handed to a `git clone` that runs server-side under the service account, so it is an
 * SSRF and command-injection surface rather than a cosmetic field. This validator enforces what
 * [GitCommandRunner]'s transport restriction cannot express: which hosts are reachable, and that
 * the URL carries no embedded credentials.
 *
 * Deliberately not covered: DNS rebinding. Git resolves the hostname itself, in its own process,
 * after this check — a name that answers with a public address here can answer with a private one
 * there. Treat [GitExecutionProperties.allowPrivateRemoteHosts] as the real boundary and keep the
 * instance's egress rules as the outer one.
 */
@Component
class GitRemoteUrlValidator(
    private val properties: GitExecutionProperties,
) {
    /**
     * @throws BadRequestException when [rawUrl] is not a remote this instance may clone from.
     */
    fun validate(rawUrl: String) {
        val trimmed = rawUrl.trim()

        if (trimmed.isEmpty()) {
            throw BadRequestException("Repository URL is required")
        }
        if (trimmed.any { it.code < 0x20 || it.code == 0x7F }) {
            throw BadRequestException("Repository URL must not contain control characters")
        }
        // A leading dash would be parsed as an option by any command the URL reaches.
        if (trimmed.startsWith("-")) {
            throw BadRequestException("Repository URL must not start with '-'")
        }

        val uri =
            try {
                URI(trimmed)
            } catch (e: URISyntaxException) {
                throw BadRequestException("Repository URL is not a valid URI: ${e.reason}")
            }

        if (!uri.isAbsolute) {
            throw BadRequestException("Repository URL must be absolute, for example https://forge.example/org/project.git")
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme == null || scheme !in properties.allowedRemoteProtocols) {
            throw BadRequestException(
                "Repository URL scheme '${uri.scheme}' is not allowed. " +
                    "Permitted: ${properties.allowedRemoteProtocols.sorted().joinToString()}",
            )
        }

        if (uri.userInfo != null) {
            throw BadRequestException(
                "Repository URL must not embed credentials. Configure a service account auth setting instead.",
            )
        }

        // `file:` is only ever enabled for tests; it has no host to vet.
        if (scheme == "file") return

        val host =
            uri.host?.takeIf { it.isNotBlank() }
                ?: throw BadRequestException("Repository URL must contain a host")

        assertHostAllowed(host)
    }

    private fun assertHostAllowed(host: String) {
        if (properties.allowPrivateRemoteHosts) return

        val addresses =
            try {
                InetAddress.getAllByName(host).toList()
            } catch (e: UnknownHostException) {
                // Do not make saving a configuration depend on DNS being answerable right now:
                // an unresolvable host simply fails later, with a clearer message, at clone time.
                logger.info { "Could not resolve '$host' while validating a repository URL: ${e.message}" }
                return
            }

        val blocked = addresses.filter { it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress || it.isAnyLocalAddress }
        if (blocked.isNotEmpty()) {
            throw BadRequestException(
                "Repository host '$host' resolves to a private or loopback address. " +
                    "Set agentos.git.allow-private-remote-hosts=true if this instance genuinely " +
                    "targets a self-hosted forge on a private network.",
            )
        }
    }

    companion object : KLogging()
}
