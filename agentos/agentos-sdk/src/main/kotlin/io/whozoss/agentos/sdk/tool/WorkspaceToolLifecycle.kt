package io.whozoss.agentos.sdk.tool

/** Optional plugin capability; avoids changing the ToolContext constructor or requiring Git in plugins. */
interface WorkspaceToolLifecycle {
    /** Close this workspace's persistent processes before its directory is removed. */
    fun releaseWorkspace(workspaceId: String, directory: String)
}
