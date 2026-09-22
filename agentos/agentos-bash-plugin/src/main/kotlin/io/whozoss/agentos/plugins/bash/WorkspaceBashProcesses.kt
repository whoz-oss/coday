package io.whozoss.agentos.plugins.bash

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Tracks workspace shells and observed descendants without changing background command semantics.
 * Descendants that detach before polling may escape this registry; workspace deletion must also
 * check for live processes using the directory before removing it.
 */
internal object WorkspaceBashProcesses {
    private val processes = ConcurrentHashMap<String, MutableSet<ProcessHandle>>()
    fun track(workspace: String, process: Process) {
        val handles = processes.computeIfAbsent(workspace) { ConcurrentHashMap.newKeySet() }
        handles.add(process.toHandle())
        Thread.ofVirtual().start {
            while (process.isAlive) {
                process.descendants().use { children -> children.forEach(handles::add) }
                Thread.sleep(20)
            }
            handles.removeIf { !isRunning(it) }
        }
    }
    fun release(workspace: String) {
        val handles = processes[workspace] ?: return
        handles.toList().forEach { handle -> handle.descendants().use { children -> children.forEach(handles::add) } }
        // TERM first and give shutdown hooks time to run (a daemon flushing its state, a git
        // process releasing index.lock); only processes still alive afterwards are killed.
        handles.filter(::isRunning).forEach { it.destroy() }
        awaitExit(handles, TERMINATION_GRACE_SECONDS)
        handles.filter(::isRunning).forEach { it.destroyForcibly() }
        awaitExit(handles, KILL_WAIT_SECONDS)
        check(handles.none(::isRunning)) { "A workspace shell process is still alive" }
        processes.remove(workspace, handles)
    }

    private fun awaitExit(handles: Collection<ProcessHandle>, seconds: Long) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (handles.any(::isRunning) && System.nanoTime() < deadline) Thread.sleep(10)
    }

    private const val TERMINATION_GRACE_SECONDS = 5L
    private const val KILL_WAIT_SECONDS = 10L

    internal fun isRunning(handle: ProcessHandle): Boolean {
        if (!handle.isAlive) return false
        // With Java as PID 1, an orphan may remain a zombie until the container exits.
        // It has no address space or file descriptors and cannot write to the workspace.
        // ProcessHandle.onExit/isAlive still treats it as alive on Linux. Only a verified
        // zombie is exempted; missing/inaccessible procfs and other platforms fail closed.
        val zombie = runCatching {
            val stat = Files.readString(Path.of("/proc", handle.pid().toString(), "stat"))
            stat.substringAfterLast(") ").firstOrNull() == 'Z'
        }.getOrDefault(false)
        return !zombie
    }
}
