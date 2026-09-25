package io.whozoss.agentos.git.core

import java.nio.file.Files
import java.nio.file.Path

/**
 * A network command must never read the agent-writable repository config, even indirectly through
 * includes or newly introduced Git options. A fresh bare directory supplies that boundary. Fetch
 * and push share only object storage, and publish one explicitly requested ref with a
 * compare-and-swap after the credentialed process exits. No repository configuration is copied
 * into this context.
 *
 * Fetch brings one branch into an origin tracking ref, a frozen case base or an observation ref;
 * push sends one local branch to the branch of the same name. Reject other forms rather than
 * interpreting remote names or options through untrusted repository configuration.
 */
internal class GitNetworkCommands(
    private val properties: GitExecutionProperties,
    private val supportDirectory: Path,
    private val execute: (GitInvocation, Map<String, String>) -> GitCommandResult,
) {
    fun run(invocation: GitInvocation): GitCommandResult {
        val temporary = Files.createTempDirectory(supportDirectory, "network-")
        return try {
            when (invocation.args.first()) {
                "clone" -> clone(invocation, temporary)
                "ls-remote" -> lsRemote(invocation, temporary)
                "fetch" -> fetch(invocation, temporary)
                "push" -> push(invocation, temporary)
                else -> error("Unsupported managed network command")
            }
        } catch (e: Exception) {
            GitCommandResult.Failed(e.message ?: "Cannot prepare the managed Git network context")
        } finally {
            temporary.toFile().deleteRecursively()
        }
    }

    private fun clone(invocation: GitInvocation, temporary: Path): GitCommandResult {
        val separator = invocation.args.indexOf("--")
        require(separator >= 0 && invocation.args.size == separator + 3) {
            "Managed clone requires an explicit URL and destination after --"
        }
        validateUrl(invocation.args[separator + 1])
        // No repository discovery at the caller's working directory, including for clone.
        val destination = Path.of(invocation.args.last()).toAbsolutePath().normalize().toString()
        return execute(
            invocation.copy(
                args = invocation.args.dropLast(1) + destination,
                gitDir = null,
                workTree = null,
                workingDirectory = temporary,
            ),
            emptyMap(),
        )
    }

    private fun lsRemote(invocation: GitInvocation, temporary: Path): GitCommandResult {
        require(invocation.args.size >= 2) { "Managed ls-remote requires an explicit URL" }
        validateUrl(invocation.args[1])
        val context = createContext(temporary)
        return execute(invocation.copy(gitDir = context, workTree = null, workingDirectory = temporary), emptyMap())
    }

    private fun fetch(invocation: GitInvocation, temporary: Path): GitCommandResult {
        val args = invocation.args.drop(1).let { if (it.firstOrNull() == "--quiet") it.drop(1) else it }
        require(args.size == 2) { "Managed fetch requires an explicit URL and one branch refspec" }
        validateUrl(args[0])
        val refspec = args[1].removePrefix("+").split(':')
        require(refspec.size == 2 && refspec[0].startsWith("refs/heads/")) { "Managed fetch requires a branch refspec" }
        val destination = refspec[1]
        require(MANAGED_FETCH_DESTINATIONS.any { destination.startsWith(it) }) {
            "Managed fetch may only update an origin tracking ref, a frozen case base or a workspace observation ref"
        }
        val common = requireNotNull(invocation.gitDir) { "Managed fetch requires a pinned common Git directory" }
            .toAbsolutePath().normalize()
        val format = output(execute(GitInvocation(listOf("rev-parse", "--show-object-format"), gitDir = common), emptyMap())).trim()
        require(format in setOf("sha1", "sha256")) { "Unsupported repository object format" }
        val context = createContext(temporary, format)
        val environment = mapOf("GIT_OBJECT_DIRECTORY" to common.resolve("objects").toString())
        refspec.forEach { ref -> output(execute(GitInvocation(listOf("check-ref-format", ref), gitDir = context), environment)) }
        require(refspec.none { '*' in it }) { "Managed fetch requires exact ref names" }

        val old = execute(GitInvocation(listOf("rev-parse", "--verify", "--quiet", destination), gitDir = common), emptyMap())
        val previous = when {
            old is GitCommandResult.Completed && old.successful -> output(old).trim()
            old is GitCommandResult.Completed && old.exitCode == 1 && !old.truncated -> "0".repeat(if (format == "sha256") 64 else 40)
            else -> throw GitCommandException("Cannot inspect the destination ref before fetch")
        }
        seedNegotiationRefs(common, context, format)
        if (previous.any { it != '0' }) {
            output(execute(GitInvocation(listOf("update-ref", destination, previous), gitDir = context), environment))
        }
        val fetched = execute(
            invocation.copy(
                args = listOf("fetch", "--no-tags", "--no-recurse-submodules", "--quiet", "--", args[0], args[1]),
                gitDir = context,
                workTree = null,
                workingDirectory = temporary,
            ),
            environment,
        )
        if (fetched !is GitCommandResult.Completed || !fetched.successful || fetched.truncated) return fetched
        val sha = output(execute(GitInvocation(listOf("rev-parse", "--verify", destination), gitDir = context), environment)).trim()
        // Local publication has no credentials. Update exactly this ref: an agent-created
        // symbolic tracking ref must not redirect publication into a local branch.
        output(execute(GitInvocation(listOf("update-ref", "--no-deref", destination, sha, previous), gitDir = common), emptyMap()))
        return fetched
    }

    /**
     * Push the commit of one local branch, resolved in the pinned common directory, to the branch
     * of the same name at an explicit URL. The only accepted option is a lease on an explicit
     * object ID (empty: the remote branch must not exist yet). After success the origin tracking
     * ref is published without credentials; if an agent moved it meanwhile, it is left alone.
     */
    private fun push(invocation: GitInvocation, temporary: Path): GitCommandResult {
        val separator = invocation.args.indexOf("--")
        require(separator >= 0 && invocation.args.size == separator + 3) {
            "Managed push requires an explicit URL and one branch refspec after --"
        }
        val options = invocation.args.subList(1, separator)
        require(options.size <= 1 && options.all { it.startsWith(LEASE_OPTION) }) {
            "Managed push accepts only an explicit lease option"
        }
        val url = invocation.args[separator + 1]
        validateUrl(url)
        val refspec = invocation.args[separator + 2].split(':')
        require(refspec.size == 2 && refspec[0] == refspec[1] && refspec[0].startsWith("refs/heads/")) {
            "Managed push sends refs/heads/<branch> to the branch of the same name"
        }
        val branch = refspec[0]
        require('*' !in branch) { "Managed push requires an exact branch name" }
        val common = requireNotNull(invocation.gitDir) { "Managed push requires a pinned common Git directory" }
            .toAbsolutePath().normalize()
        val format = output(execute(GitInvocation(listOf("rev-parse", "--show-object-format"), gitDir = common), emptyMap())).trim()
        require(format in setOf("sha1", "sha256")) { "Unsupported repository object format" }
        val expected = objectIdPattern(format)
        val lease = options.singleOrNull()?.removePrefix(LEASE_OPTION)?.split(':', limit = 2)?.let { parts ->
            require(parts.size == 2 && parts[0] == branch && (parts[1].isEmpty() || parts[1].matches(expected))) {
                "The lease must name the pushed branch and an explicit object ID"
            }
            "$LEASE_OPTION$branch:${parts[1]}"
        }
        val context = createContext(temporary, format)
        val environment = mapOf("GIT_OBJECT_DIRECTORY" to common.resolve("objects").toString())
        output(execute(GitInvocation(listOf("check-ref-format", branch), gitDir = context), environment))
        val sha = output(execute(GitInvocation(listOf("rev-parse", "--verify", "$branch^{commit}"), gitDir = common), emptyMap())).trim()
        require(sha.matches(expected)) { "Invalid object ID for the pushed branch" }
        val tracking = "refs/remotes/origin/" + branch.removePrefix("refs/heads/")
        val old = execute(GitInvocation(listOf("rev-parse", "--verify", "--quiet", tracking), gitDir = common), emptyMap())
        val previous = when {
            old is GitCommandResult.Completed && old.successful -> output(old).trim()
            else -> "0".repeat(if (format == "sha256") 64 else 40)
        }
        copyShallowBoundary(common, context, expected)
        output(execute(GitInvocation(listOf("update-ref", branch, sha), gitDir = context), environment))
        val pushed = execute(
            invocation.copy(
                args = listOf("push", "--porcelain", "--no-recurse-submodules") + listOfNotNull(lease) +
                    listOf("--", url, "$branch:$branch"),
                gitDir = context,
                workTree = null,
                workingDirectory = temporary,
            ),
            environment,
        )
        if (pushed !is GitCommandResult.Completed || !pushed.successful || pushed.truncated) return pushed
        // Local publication has no credentials. An agent-created symbolic tracking ref is replaced,
        // never followed, and a concurrent change is kept: the next fetch refreshes it.
        execute(GitInvocation(listOf("update-ref", "--no-deref", tracking, sha, previous), gitDir = common), emptyMap())
        return pushed
    }

    /**
     * Sharing objects alone does not advertise them to upload-pack. Give the private repository
     * recent commit tips so each new case can negotiate history it already has. Only validated
     * object IDs cross the boundary, never shared config, symbolic refs or agent ref names.
     */
    private fun seedNegotiationRefs(common: Path, context: Path, format: String) {
        val tips = output(execute(
            GitInvocation(
                listOf("for-each-ref", "--count=64", "--sort=-committerdate", "--format=%(objectname)",
                    "refs/heads/", "refs/remotes/origin/", "refs/agentos/base/"),
                gitDir = common,
            ),
            emptyMap(),
        )).lineSequence().filter { it.isNotBlank() }.distinct().toList()
        val expected = objectIdPattern(format)
        val directory = Files.createDirectories(context.resolve("refs/agentos/negotiation"))
        tips.forEachIndexed { index, sha ->
            require(sha.matches(expected)) { "Invalid negotiation object ID" }
            Files.writeString(directory.resolve(index.toString()), "$sha\n")
        }
        copyShallowBoundary(common, context, expected)
    }

    /**
     * An agent's `fetch --depth` leaves commits whose parents are absent from the shared object
     * store. Without their boundary, a tip seeded above advertises history the private repository
     * does not have, the server omits it, and every later case base fetch fails.
     */
    private fun copyShallowBoundary(common: Path, context: Path, expected: Regex) {
        val shallow = common.resolve("shallow")
        if (!Files.isRegularFile(shallow)) return
        val boundary = Files.readAllLines(shallow).filter { it.isNotBlank() }
        require(boundary.all { it.matches(expected) }) { "Invalid shallow object ID" }
        Files.writeString(context.resolve("shallow"), boundary.joinToString(separator = "") { "$it\n" })
    }

    private fun objectIdPattern(format: String): Regex = Regex(if (format == "sha256") "[a-f0-9]{64}" else "[a-f0-9]{40}")

    private fun createContext(temporary: Path, format: String = "sha1"): Path {
        val context = Files.createDirectory(temporary.resolve("repository.git"))
        Files.createDirectories(context.resolve("objects"))
        Files.createDirectories(context.resolve("refs"))
        Files.writeString(context.resolve("HEAD"), "ref: refs/heads/unused\n")
        val config = if (format == "sha256") {
            "[core]\nrepositoryformatversion = 1\nbare = true\n[extensions]\nobjectFormat = sha256\n"
        } else {
            "[core]\nrepositoryformatversion = 0\nbare = true\n"
        }
        Files.writeString(context.resolve("config"), config)
        return context
    }

    private fun validateUrl(url: String) = GitRemoteUrlValidator(properties).validate(url)

    private fun output(result: GitCommandResult): String {
        if (result is GitCommandResult.Completed && result.successful && !result.truncated) return result.stdout
        throw GitCommandException(
            when (result) {
                is GitCommandResult.Completed -> result.stderr.trim().ifEmpty { "Git command failed or returned incomplete output" }
                is GitCommandResult.Failed -> result.message
                is GitCommandResult.TimedOut -> "Git command timed out after ${result.timeout}"
            },
        )
    }

    private companion object {
        /**
         * Refs the service may publish after a credentialed fetch. Workspace observation uses its
         * own namespace so that it never moves the tracking refs an agent's
         * `push --force-with-lease` relies on.
         */
        val MANAGED_FETCH_DESTINATIONS = listOf("refs/remotes/origin/", "refs/agentos/base/", "refs/agentos/observed/")

        const val LEASE_OPTION = "--force-with-lease="
    }
}
