package io.whozoss.agentos.plugins.bash

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Unit tests for [BashShellResolver]. The OS, environment and filesystem are injected so the Windows
 * lookup is exercised on any machine.
 */
class BashShellResolverUnitSpec : StringSpec({

    val windows = "Windows 11"

    fun resolveOnWindows(environment: Map<String, String>, vararg existing: String): BashShell? {
        val files = existing.map { File(it).path }.toSet()
        return BashShellResolver.resolve(windows, environment) { it.path in files }
    }

    "should use /bin/bash outside Windows" {
        BashShellResolver.resolve("Mac OS X", emptyMap()) { false } shouldBe BashShell("/bin/bash", "pwd")
        BashShellResolver.resolve("Linux", emptyMap()) { false } shouldBe BashShell("/bin/bash", "pwd")
    }

    "should prefer the AGENTOS_BASH_PATH override on Windows" {
        val custom = "D:/tools/git/bin/bash.exe"
        val shell =
            resolveOnWindows(
                mapOf("AGENTOS_BASH_PATH" to custom, "ProgramFiles" to "C:/Program Files"),
                custom,
                "C:/Program Files/Git/bin/bash.exe",
            )
        shell shouldBe BashShell(File(custom).path, "pwd -W")
    }

    "should ignore an AGENTOS_BASH_PATH that does not exist" {
        val standard = "C:/Program Files/Git/bin/bash.exe"
        val shell =
            resolveOnWindows(
                mapOf("AGENTOS_BASH_PATH" to "D:/missing/bash.exe", "ProgramFiles" to "C:/Program Files"),
                standard,
            )
        shell shouldBe BashShell(File(standard).path, "pwd -W")
    }

    "should find a standard Git for Windows install" {
        val standard = "C:/Program Files/Git/bin/bash.exe"
        resolveOnWindows(mapOf("ProgramFiles" to "C:/Program Files"), standard) shouldBe
            BashShell(File(standard).path, "pwd -W")
    }

    "should find a per-user Git for Windows install" {
        val perUser = "C:/Users/me/AppData/Local/Programs/Git/bin/bash.exe"
        resolveOnWindows(mapOf("LOCALAPPDATA" to "C:/Users/me/AppData/Local"), perUser) shouldBe
            BashShell(File(perUser).path, "pwd -W")
    }

    "should derive Git Bash from git.exe found on the PATH" {
        val root = "/dev/PortableGit"
        val shell =
            resolveOnWindows(
                mapOf("PATH" to listOf("/windows/system32", "$root/cmd").joinToString(File.pathSeparator)),
                "$root/cmd/git.exe",
                "$root/bin/bash.exe",
            )
        shell shouldBe BashShell(File("$root/bin/bash.exe").path, "pwd -W")
    }

    "should derive Git Bash from a git.exe in mingw64/bin" {
        val root = "/dev/PortableGit"
        val shell =
            resolveOnWindows(
                mapOf("PATH" to "$root/mingw64/bin"),
                "$root/mingw64/bin/git.exe",
                "$root/bin/bash.exe",
            )
        shell shouldBe BashShell(File("$root/bin/bash.exe").path, "pwd -W")
    }

    "should not pick the WSL bash.exe found on the PATH" {
        val shell =
            resolveOnWindows(
                mapOf("PATH" to "/windows/system32"),
                "/windows/system32/bash.exe",
            )
        shell shouldBe null
    }

    "should return null on Windows when no Git Bash exists" {
        resolveOnWindows(mapOf("ProgramFiles" to "C:/Program Files")) shouldBe null
    }
})
