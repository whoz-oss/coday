package io.whozoss.agentos.plugins.bash

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Workspace shells retain their background jobs until exit; cleanup also stops tracked descendants. */
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
            handles.removeIf { !it.isAlive }
        }
    }
    fun release(workspace: String) {
        val handles = processes[workspace] ?: return
        handles.toList().forEach { handle -> handle.descendants().use { children -> children.forEach(handles::add) } }
        handles.filter { it.isAlive }.forEach { it.destroy() }
        handles.filter { it.isAlive }.forEach { it.destroyForcibly() }
        handles.filter { it.isAlive }.forEach { it.onExit().get(10, TimeUnit.SECONDS) }
        check(handles.none { it.isAlive }) { "A workspace shell process is still alive" }
        processes.remove(workspace, handles)
    }
}
