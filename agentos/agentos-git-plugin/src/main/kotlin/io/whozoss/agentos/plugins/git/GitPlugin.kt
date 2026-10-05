package io.whozoss.agentos.plugins.git

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.git.core.GitCommandRunner
import io.whozoss.agentos.git.core.GitExecutionProperties
import io.whozoss.agentos.git.core.GitHubApi
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolPlugin
import mu.KLogging
import org.pf4j.Extension
import org.pf4j.Plugin
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

class GitPlugin : Plugin() {
    override fun start() {
        logger.info { "Git Plugin started!" }
    }

    override fun stop() {
        logger.info { "Git Plugin stopped!" }
    }

    companion object : KLogging()
}

/**
 * Tool provider for the GIT integration.
 *
 * Loading this plugin makes Git available on the instance: the service then offers the namespace
 * repository association and equips new case families with a worktree it manages itself. This
 * provider never offers an agent a tool to create or remove a worktree, nor a free Git command. In
 * an equipped family its tools work in the worktree the service designates for each run. Elsewhere
 * the integration is an ordinary one: its tools work in the repository its configuration names.
 * Either way they act with the credentials of the user running the case, from the auth setting
 * bound to the integration.
 */
@Extension
class GitToolProvider : ToolPlugin {
    override val integrationType: String = INTEGRATION_TYPE

    override val configSchema: JsonNode = CONFIG_SCHEMA

    /** One runner per execution policy: each keeps its private support directory for the JVM lifetime. */
    private val runners = ConcurrentHashMap<Boolean, GitCommandRunner>()

    override fun provideTools(config: JsonNode?, configName: String?, context: ToolContext?): List<StandardTool<*>> {
        // A case Git workspace injects its whole context. Elsewhere the configuration names the repository.
        val injected = GitWorkspaceContext.from(config)
        val directory = injected?.workingDirectory ?: configuredDirectory(config, configName) ?: return emptyList()
        val allowPrivateRemoteHosts = config?.path("allowPrivateRemoteHosts")?.asBoolean(false) ?: false
        val runner = runners.computeIfAbsent(allowPrivateRemoteHosts) {
            GitCommandRunner(GitExecutionProperties(allowPrivateRemoteHosts = it))
        }
        val workspace =
            if (injected != null) GitWorkspace(injected, runner)
            else GitWorkspace({ GitWorkspaceContext.discover(directory, config, runner) }, runner)
        val gitHub = GitHubApi()
        val access = GitForgeAccess(context?.credentialProvider, context?.userExternalId, gitHub)
        return gitTools(configName ?: INTEGRATION_TYPE, workspace, access, gitHub)
    }

    private fun configuredDirectory(
        config: JsonNode?,
        configName: String?,
    ): Path? {
        val directory = GitWorkspaceContext.configuredDirectory(config) ?: run {
            logger.debug { "GIT integration '$configName': no Git workspace and no configured workingDirectory" }
            return null
        }
        if (!directory.isAbsolute) {
            logger.error { "GIT integration '$configName': workingDirectory must be an absolute path, no tools registered" }
            return null
        }
        if (GitWorkspaceContext.configuredRepositoryUrl(config) == null) {
            logger.error { "GIT integration '$configName': repositoryUrl is required with workingDirectory, no tools registered" }
            return null
        }
        return directory
    }

    companion object : KLogging() {
        const val INTEGRATION_TYPE = "GIT"

        private val CONFIG_SCHEMA: JsonNode = jacksonObjectMapper().readTree(
            """
            {
                "type": "object",
                "title": "Git Integration Configuration",
                "description": "Git tools for agents. In a case's Git workspace they work in its worktree, whatever is configured here. Elsewhere they work in the repository named by workingDirectory. Bind an auth setting holding each user's forge token: agents act with the identity of the user running the case.",
                "properties": {
                    "workingDirectory": {
                        "type": "string",
                        "title": "Repository directory",
                        "description": "Absolute path of the root of a non-bare repository the tools work in outside a case Git workspace."
                    },
                    "repositoryUrl": {
                        "type": "string",
                        "title": "Repository URL",
                        "description": "Required with workingDirectory: HTTPS remote the tools fetch from, push to and open pull requests on. Never read from the repository, whose configuration an agent can change."
                    },
                    "mainBranch": {
                        "type": "string",
                        "title": "Main branch",
                        "description": "Branch pull requests target, never pushed by the tools. Defaults to main."
                    },
                    "allowPrivateRemoteHosts": {
                        "type": "boolean",
                        "title": "Allow private remote hosts",
                        "description": "Allow push and fetch to a forge on a private network. Keep it aligned with agentos.git.allow-private-remote-hosts.",
                        "default": false
                    }
                },
                "additionalProperties": false
            }
            """.trimIndent(),
        )
    }
}

/** The tools of one GIT integration in one workspace. */
internal fun gitTools(
    prefix: String,
    workspace: GitWorkspace,
    access: GitForgeAccess,
    gitHub: GitHubApi,
): List<StandardTool<*>> =
    listOf(
        GitStatusTool(prefix, workspace),
        GitCreateBranchTool(prefix, workspace),
        GitCommitTool(prefix, workspace, access),
        GitFetchTool(prefix, workspace, access),
        GitPushTool(prefix, workspace, access),
        GitCreatePullRequestTool(prefix, workspace, access, gitHub),
    )
