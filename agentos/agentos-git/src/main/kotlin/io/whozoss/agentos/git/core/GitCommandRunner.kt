package io.whozoss.agentos.git.core

import mu.KLogging
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.concurrent.TimeoutException

/**
 * Runs git server-side under an execution contract the repository contents cannot influence.
 *
 * ## Why this exists
 *
 * Every worktree of a managed clone shares one git directory: its `config`, its `hooks/` and the
 * per-worktree `gitdir` pointers all live in the same place, and an agent — which runs unsandboxed
 * by design — can write there. Running a bare `git -C <worktree> …` from the service therefore
 * means running with attacker-influenced configuration: a `hooks/reference-transaction` script
 * planted in the common directory executes under the **service account** on the next `fetch`, a
 * `credential.helper` entry captures the push token, and a rewritten `.git` pointer file makes a
 * cleanup inspection report a *different* worktree's state — which is how a destructive
 * `worktree remove --force` ends up deleting unpushed work.
 *
 * This runner closes those paths by construction. Every invocation:
 * - **clears the environment** before adding a minimal allow-list, so `AGENTOS_ENCRYPTION_KEY`,
 *   provider API keys and any other service secret are absent from git and from anything git spawns;
 * - **neutralises hooks** (`core.hooksPath` points at an empty directory), **credential helpers**
 *   (`credential.helper=` resets the list), the filesystem monitor, the pager and the editor,
 *   all through `-c` options, which outrank repository configuration;
 * - **restricts transports** to [GitExecutionProperties.allowedRemoteProtocols], which also
 *   disarms `ext::` command execution and `url.<base>.insteadOf` rewrites to exotic schemes;
 * - **isolates network commands** in a temporary Git directory that never reads shared local
 *   configuration; fetch and push publish their ref only after credentials have left the process;
 * - **ignores global and system configuration** (`GIT_CONFIG_GLOBAL` / `GIT_CONFIG_SYSTEM`);
 * - **pins the repository** with explicit `--git-dir` / `--work-tree` from [GitInvocation], so the
 *   worktree's own `.git` pointer file is never an authority.
 *
 * ## Residual risk, stated plainly
 *
 * This runner protects *server-side* git from repository configuration. It does not sandbox the
 * agent: a BASH tool still runs as the same OS user and can read and write shared files directly.
 * [assertNoHostileLocalConfig] remains a guard for local/destructive operations; credentialed
 * network operations do not rely on that denylist. Full isolation would
 * require either one clone per case family or a sandboxed shell; both are product decisions
 * recorded in `agentos/docs/git-workspaces.md`.
 */
class GitCommandRunner(
    private val properties: GitExecutionProperties,
) {
    /**
     * Private support directory holding the empty hooks sink, a scratch HOME and the askpass
     * helper. Created on first use with owner-only permissions and kept for the JVM lifetime.
     */
    private val supportDirectory: Path by lazy { createSupportDirectory() }

    private val hooksSink: Path by lazy { supportDirectory.resolve("hooks").also { Files.createDirectories(it) } }

    private val homeSink: Path by lazy { supportDirectory.resolve("home").also { Files.createDirectories(it) } }

    private val askpassHelper: Path by lazy { createAskpassHelper() }

    private val networkCommands: GitNetworkCommands by lazy {
        GitNetworkCommands(properties, supportDirectory, ::runProcess)
    }

    /**
     * Execute [invocation] and return its outcome. Never throws for a non-zero exit code.
     */
    fun run(invocation: GitInvocation): GitCommandResult =
        if (invocation.args.firstOrNull() in setOf("clone", "fetch", "ls-remote", "push")) {
            networkCommands.run(invocation)
        } else if (invocation.credentials !is GitCredentials.None) {
            GitCommandResult.Failed("Credentials are only permitted for managed network commands")
        } else {
            runProcess(invocation)
        }

    private fun runProcess(
        invocation: GitInvocation,
        extraEnvironment: Map<String, String> = emptyMap(),
    ): GitCommandResult {
        val command = buildCommand(invocation)
        val timeout = invocation.timeout ?: properties.defaultTimeout

        logger.debug { "Running: ${redactedCommandLine(command)}" }

        val builder = ProcessBuilder(command).redirectErrorStream(false)
        invocation.workingDirectory?.let { builder.directory(it.toFile()) }
        applyEnvironment(builder, invocation)
        builder.environment().putAll(extraEnvironment)

        val process =
            try {
                builder.start()
            } catch (e: IOException) {
                logger.error(e) { "Failed to start git: ${redactedCommandLine(command)}" }
                return GitCommandResult.Failed(e.message ?: e.javaClass.simpleName)
            }

        return try {
            val output = BoundedProcessOutput.await(process, timeout, properties.maxOutputChars)
            GitCommandResult.Completed(
                exitCode = output.exitCode,
                stdout = output.stdout,
                stderr = output.stderr,
                truncated = output.truncated,
            )
        } catch (_: TimeoutException) {
            logger.warn { "git timed out after $timeout: ${redactedCommandLine(command)}" }
            GitCommandResult.TimedOut(timeout)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            GitCommandResult.Failed("Git execution was interrupted")
        } catch (e: Exception) {
            logger.error(e) { "Failed to read git output: ${redactedCommandLine(command)}" }
            GitCommandResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Execute [invocation] and return trimmed stdout, raising [GitCommandException] unless git
     * exited with 0.
     */
    fun runOrThrow(invocation: GitInvocation): String =
        when (val result = run(invocation)) {
            is GitCommandResult.Completed ->
                when {
                    result.truncated -> throw GitCommandException("git output was incomplete; refusing to use a truncated result")
                    result.successful -> result.stdout.trim()
                    else -> throw GitCommandException(
                        "git ${invocation.args.firstOrNull() ?: ""} failed (exit ${result.exitCode}): " +
                            result.stderr.trim().ifBlank { result.stdout.trim() },
                    )
                }

            is GitCommandResult.TimedOut ->
                throw GitCommandException("git ${invocation.args.firstOrNull() ?: ""} timed out after ${result.timeout}")

            is GitCommandResult.Failed ->
                throw GitCommandException("git ${invocation.args.firstOrNull() ?: ""} could not run: ${result.message}")
        }

    /**
     * Reject local settings that can execute a program during checkout or file inspection.
     * Network configuration is harmless here: credentialed commands use a private repository.
     *
     * Worktree-specific files are inspected too: once `extensions.worktreeConfig` is enabled, a
     * worktree's `config.worktree` is read by every command running in it, including service
     * status and diff. Query only relevant keys, with includes disabled; matched values are
     * compared with [ALLOWED_EXECUTABLE_CONFIG] and never copied into failure messages.
     */
    fun assertNoHostileLocalConfig(gitDir: Path) {
        val common = commonGitDirectory(gitDir)
        assertNoHostileConfigEntries(listOf("--local"), gitDir)
        worktreeConfigFiles(common).forEach { file -> assertNoHostileConfigEntries(listOf("--file", file.toString()), gitDir) }
    }

    private fun assertNoHostileConfigEntries(
        scope: List<String>,
        gitDir: Path,
    ) {
        val result = run(
            GitInvocation(
                args = listOf("config") + scope + listOf("--no-includes", "-z", "--get-regexp", HOSTILE_CONFIG_PATTERN),
                gitDir = gitDir,
            ),
        )
        when {
            result is GitCommandResult.Completed && result.exitCode == 1 && !result.truncated -> Unit
            result is GitCommandResult.Completed && result.successful && !result.truncated &&
                configEntries(result.stdout).all { it in ALLOWED_EXECUTABLE_CONFIG } -> Unit
            result is GitCommandResult.Completed && result.successful -> throw GitCommandException(
                "Repository configuration contains executable filters, diff drivers or includes. " +
                    "Inspect the repository configuration before retrying.",
            )
            else -> throw GitCommandException("Cannot inspect the local Git configuration; refusing to proceed")
        }
    }

    /** `config -z` prints `name\nvalue\0`, or `name\0` for a key without a value. */
    private fun configEntries(output: String): List<Pair<String, String?>> =
        output.split('\u0000').filter { it.isNotEmpty() }.map { entry ->
            if ('\n' in entry) entry.substringBefore('\n') to entry.substringAfter('\n') else entry to null
        }

    /** A linked worktree's administrative directory names its common directory in `commondir`. */
    private fun commonGitDirectory(gitDir: Path): Path {
        val pointer = gitDir.resolve("commondir")
        return if (Files.isRegularFile(pointer)) gitDir.resolve(Files.readString(pointer).trim()).normalize() else gitDir
    }

    private fun worktreeConfigFiles(common: Path): List<Path> {
        val registrations = common.resolve("worktrees")
        val linked = if (Files.isDirectory(registrations)) {
            Files.newDirectoryStream(registrations).use { entries -> entries.map { it.resolve(WORKTREE_CONFIG) } }
        } else {
            emptyList()
        }
        return (listOf(common.resolve(WORKTREE_CONFIG)) + linked).filter { Files.exists(it) }
    }

    // -------------------------------------------------------------------------
    // Command assembly
    // -------------------------------------------------------------------------

    private fun buildCommand(invocation: GitInvocation): List<String> =
        buildList {
            add(properties.binary)
            addAll(hardeningOptions())
            // Absolute, always: a relative repository path would be resolved against the child's
            // working directory, which is frequently *not* the JVM's — the exchange mount root is
            // configured relative to the JVM cwd, so the same string means two different places.
            invocation.gitDir?.let { add("--git-dir=${it.absolute()}") }
            invocation.workTree?.let { add("--work-tree=${it.absolute()}") }
            addAll(invocation.args)
        }

    private fun Path.absolute(): Path = toAbsolutePath().normalize()

    /**
     * Options that must outrank whatever the shared repository configuration says. They are passed
     * on every invocation, including read-only ones, because a poisoned `hooks/` directory fires on
     * plumbing commands too (`reference-transaction` runs on any ref update).
     */
    private fun hardeningOptions(): List<String> =
        buildList {
            addConfig("core.hooksPath", hooksSink.toString())
            addConfig("credential.helper", "")
            addConfig("core.askPass", "")
            addConfig("core.fsmonitor", "false")
            addConfig("core.pager", "cat")
            addConfig("core.editor", "true")
            addConfig("protocol.allow", "never")
            properties.allowedRemoteProtocols.forEach { addConfig("protocol.$it.allow", "always") }
            addConfig("advice.detachedHead", "false")
            addConfig("gc.auto", "0")
            // Validate exactly the configured endpoint: redirects may bypass its host policy.
            addConfig("http.followRedirects", "false")
        }

    private fun MutableList<String>.addConfig(
        key: String,
        value: String,
    ) {
        add("-c")
        add("$key=$value")
    }

    private fun applyEnvironment(
        builder: ProcessBuilder,
        invocation: GitInvocation,
    ) {
        val env = builder.environment()

        // The service environment carries AGENTOS_ENCRYPTION_KEY, provider API keys and database
        // credentials. Nothing git (or a hook that somehow survived) does should be able to read
        // them, so the child starts from an empty environment rather than an inherited one.
        env.clear()
        env.putAll(buildEnvironment(invocation))
    }

    /**
     * The complete environment handed to git: an explicit allow-list, never the inherited one.
     *
     * Exposed for tests, which assert the absence of inherited service secrets directly — a
     * property a child process cannot demonstrate once hooks and external commands are disabled.
     */
    internal fun buildEnvironment(invocation: GitInvocation): Map<String, String> =
        buildMap {
            put("PATH", System.getenv("PATH") ?: DEFAULT_PATH)
            put("HOME", homeSink.toString())
            put("LANG", "C")
            put("LC_ALL", "C")
            put("GIT_CONFIG_GLOBAL", NULL_DEVICE)
            put("GIT_CONFIG_SYSTEM", NULL_DEVICE)
            put("GIT_TERMINAL_PROMPT", "0")
            put("GIT_FLUSH", "1")
            // An accepted git-lfs filter must never download content with the service identity.
            // Checkouts keep pointer files; the setup command decides whether to fetch LFS objects.
            put("GIT_LFS_SKIP_SMUDGE", "1")

            // Without a pinned git-dir, stop git from discovering a repository above the directory
            // we pointed it at.
            if (invocation.gitDir == null) {
                invocation.workingDirectory?.parent?.let { put("GIT_CEILING_DIRECTORIES", it.toString()) }
            }

            when (val credentials = invocation.credentials) {
                is GitCredentials.None -> Unit
                is GitCredentials.UsernamePassword -> {
                    put("GIT_ASKPASS", askpassHelper.toString())
                    put(ASKPASS_USERNAME_VAR, credentials.username)
                    put(ASKPASS_SECRET_VAR, credentials.secret)
                }
            }
        }

    // -------------------------------------------------------------------------
    // Support files
    // -------------------------------------------------------------------------

    private fun createSupportDirectory(): Path {
        val directory = Files.createTempDirectory("agentos-git-")
        restrictToOwner(directory)
        directory.toFile().deleteOnExit()
        logger.info { "Git execution support directory: $directory" }
        return directory
    }

    /**
     * Write the askpass helper git calls when it needs credentials. It echoes values taken from
     * the invocation's environment, which keeps the secret out of `argv` and out of the file.
     */
    private fun createAskpassHelper(): Path {
        val script = supportDirectory.resolve("askpass.sh")
        val body =
            """
            #!/bin/sh
            # Generated by AgentOS. Feeds git the credentials of the current invocation from the
            # process environment: no secret in argv, in .git/config, or in this file.
            case "${'$'}1" in
                Username*) printf '%s' "${'$'}$ASKPASS_USERNAME_VAR" ;;
                *)         printf '%s' "${'$'}$ASKPASS_SECRET_VAR" ;;
            esac
            """.trimIndent()
        Files.writeString(script, body + System.lineSeparator())
        makeOwnerExecutable(script)
        return script
    }

    private fun restrictToOwner(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
        }.onFailure {
            logger.debug { "POSIX permissions unsupported for $path; relying on default filesystem ACLs" }
        }
    }

    private fun makeOwnerExecutable(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
        }.onFailure {
            // Without the executable bit git cannot invoke the helper, so a credentialed call
            // would hang on a prompt. GIT_TERMINAL_PROMPT=0 turns that into a clean failure.
            logger.warn { "Could not mark $path executable; credentialed git calls will fail fast" }
        }
    }

    /** Command line with any credential-bearing value removed, safe for logs. */
    private fun redactedCommandLine(command: List<String>): String = command.joinToString(" ")

    companion object : KLogging() {
        private const val NULL_DEVICE = "/dev/null"
        private const val DEFAULT_PATH = "/usr/local/bin:/usr/bin:/bin"
        internal const val ASKPASS_USERNAME_VAR = "AGENTOS_GIT_USERNAME"
        internal const val ASKPASS_SECRET_VAR = "AGENTOS_GIT_SECRET"

        // Hooks, fsmonitor, editor and pager are overridden on every invocation. Aliases cannot
        // replace the built-in commands used here. Credential/URL/HTTP/protocol settings are read
        // only by the isolated network context. Includes remain forbidden because they can hide
        // a filter driver; filters run even during status or non-forced worktree removal.
        private const val HOSTILE_CONFIG_PATTERN =
            "^(include\\.path|includeif\\..*\\.path|filter\\..*\\.(clean|smudge|process)|diff\\.(external|.*\\.(command|textconv)))$"

        private const val WORKTREE_CONFIG = "config.worktree"

        /**
         * Exactly what `git lfs install` writes. The program is resolved through the service PATH;
         * any other value for these keys, even one that also mentions git-lfs, stays refused.
         */
        private val ALLOWED_EXECUTABLE_CONFIG: Set<Pair<String, String?>> =
            setOf(
                "filter.lfs.clean" to "git-lfs clean -- %f",
                "filter.lfs.smudge" to "git-lfs smudge -- %f",
                "filter.lfs.process" to "git-lfs filter-process",
            )
    }
}
