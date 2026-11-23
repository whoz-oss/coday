package io.whozoss.agentos.plugins.bash

import java.io.File

/**
 * The shell used to run a command: its [executable] and the command printing the current directory.
 *
 * Git Bash reports directories in POSIX form (`/c/Users/me`), which is meaningless to the rest of the
 * application, so on Windows the directory is printed with `pwd -W` (`C:/Users/me`).
 */
data class BashShell(
    val executable: String,
    val pwdCommand: String = "pwd",
)

/**
 * Locates the bash to run commands with.
 *
 * - Outside Windows: `/bin/bash`.
 * - On Windows, Git Bash, the first of:
 *   1. the `AGENTOS_BASH_PATH` environment variable, when it points to an existing file;
 *   2. a standard Git for Windows install (`Program Files`, `Program Files (x86)`, per-user `Programs\Git`);
 *   3. the install owning a `git.exe` found on the `PATH`.
 *
 * `bash.exe` found on the `PATH` is deliberately ignored: `C:\Windows\System32\bash.exe` is the WSL launcher,
 * which runs Linux paths and a different filesystem.
 */
object BashShellResolver {
    const val BASH_PATH_VARIABLE = "AGENTOS_BASH_PATH"

    private const val UNIX_BASH = "/bin/bash"

    fun resolve(
        osName: String = System.getProperty("os.name").orEmpty(),
        environment: Map<String, String> = System.getenv(),
        isFile: (File) -> Boolean = File::isFile,
    ): BashShell? =
        when {
            !osName.startsWith("Windows", ignoreCase = true) -> BashShell(UNIX_BASH)
            else ->
                findGitBash(environment, isFile)
                    ?.let { BashShell(executable = it.path, pwdCommand = "pwd -W") }
        }

    fun windowsNotFoundMessage(): String =
        "Git Bash not found. Install Git for Windows (https://git-scm.com/download/win) " +
            "or set $BASH_PATH_VARIABLE to the full path of its bash.exe"

    private fun findGitBash(environment: Map<String, String>, isFile: (File) -> Boolean): File? =
        sequence {
            environment[BASH_PATH_VARIABLE]?.takeIf { it.isNotBlank() }?.let { yield(File(it)) }
            yieldAll(standardInstallCandidates(environment))
            yieldAll(candidatesFromGitOnPath(environment, isFile))
        }.firstOrNull(isFile)

    private fun standardInstallCandidates(environment: Map<String, String>): List<File> =
        listOfNotNull(
            environment["ProgramFiles"]?.let { File(it, "Git/bin/bash.exe") },
            environment["ProgramW6432"]?.let { File(it, "Git/bin/bash.exe") },
            environment["ProgramFiles(x86)"]?.let { File(it, "Git/bin/bash.exe") },
            environment["LOCALAPPDATA"]?.let { File(it, "Programs/Git/bin/bash.exe") },
        )

    /**
     * `git.exe` lives in `<root>\cmd`, `<root>\bin` or `<root>\mingw64\bin`, so the install root is one or
     * two levels above its directory.
     */
    private fun candidatesFromGitOnPath(environment: Map<String, String>, isFile: (File) -> Boolean): List<File> =
        environment["PATH"]
            .orEmpty()
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map { File(it) }
            .filter { isFile(File(it, "git.exe")) }
            .flatMap { dir ->
                listOfNotNull(dir.parentFile, dir.parentFile?.parentFile).map { File(it, "bin/bash.exe") }
            }
}
