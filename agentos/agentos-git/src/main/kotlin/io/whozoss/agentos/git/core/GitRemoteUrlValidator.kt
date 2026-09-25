package io.whozoss.agentos.git.core

import mu.KLogging
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
class GitRemoteUrlValidator(
    private val properties: GitExecutionProperties,
) {
    /**
     * @throws InvalidGitRemoteException when [rawUrl] is not a remote this instance may clone from.
     */
    fun validate(rawUrl: String) {
        val trimmed = rawUrl.trim()

        if (trimmed.isEmpty()) {
            throw InvalidGitRemoteException("Repository URL is required")
        }
        if (trimmed.any { it.code < 0x20 || it.code == 0x7F }) {
            throw InvalidGitRemoteException("Repository URL must not contain control characters")
        }
        // A leading dash would be parsed as an option by any command the URL reaches.
        if (trimmed.startsWith("-")) {
            throw InvalidGitRemoteException("Repository URL must not start with '-'")
        }

        val uri =
            try {
                URI(trimmed)
            } catch (e: URISyntaxException) {
                throw InvalidGitRemoteException("Repository URL is not a valid URI: ${e.reason}")
            }

        if (!uri.isAbsolute) {
            throw InvalidGitRemoteException("Repository URL must be absolute, for example https://forge.example/org/project.git")
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme == null || scheme !in properties.allowedRemoteProtocols) {
            throw InvalidGitRemoteException(
                "Repository URL scheme '${uri.scheme}' is not allowed. " +
                    "Permitted: ${properties.allowedRemoteProtocols.sorted().joinToString()}",
            )
        }

        if (uri.userInfo != null) {
            throw InvalidGitRemoteException(
                "Repository URL must not embed credentials. Configure a service account auth setting instead.",
            )
        }

        // `file:` is only ever enabled for tests; it has no host to vet.
        if (scheme == "file") return

        val host =
            uri.host?.takeIf { it.isNotBlank() }
                ?: throw InvalidGitRemoteException("Repository URL must contain a host")

        assertHostAllowed(host)
    }

    private fun assertHostAllowed(host: String) {
        // Java resolves legacy numeric forms differently from Git/libcurl (octal, hexadecimal,
        // fewer than four components). Require an unambiguous spelling before either resolver.
        if (host.matches(Regex("(?i)(?:0x[0-9a-f]+|[0-9]+)(?:\\.(?:0x[0-9a-f]+|[0-9]+))*\\.?"))) {
            val parts = host.split('.')
            if (parts.size != 4 || parts.any { part ->
                    part.toIntOrNull()?.let { it !in 0..255 || it.toString() != part } != false
                }) {
                throw InvalidGitRemoteException("Repository IPv4 host must use canonical dotted decimal notation")
            }
        }
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

        val blocked = addresses.filter { address ->
            val bytes = address.address
            val uniqueLocalV6 = bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC
            val sharedV4 = bytes.size == 4 && bytes[0].toInt() == 100 && (bytes[1].toInt() and 0xC0) == 0x40
            address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress ||
                address.isAnyLocalAddress || address.isMulticastAddress || uniqueLocalV6 || sharedV4
        }
        if (blocked.isNotEmpty()) {
            throw InvalidGitRemoteException(
                "Repository host '$host' resolves to a private or loopback address. " +
                    "Set agentos.git.allow-private-remote-hosts=true if this instance genuinely " +
                    "targets a self-hosted forge on a private network.",
            )
        }
    }

    companion object : KLogging()
}

/** A remote this instance refuses to reach. The message is meant for the person who entered the URL. */
class InvalidGitRemoteException(message: String) : IllegalArgumentException(message)
