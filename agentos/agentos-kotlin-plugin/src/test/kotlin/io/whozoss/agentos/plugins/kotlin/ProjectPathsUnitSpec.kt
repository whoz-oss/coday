package io.whozoss.agentos.plugins.kotlin

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.eclipse.lsp4j.Position
import java.nio.file.Files
import java.nio.file.Path

class ProjectPathsUnitSpec :
    StringSpec({
        val paths = ProjectPaths(root = Path.of("/project"))

        "relative file path is resolved against the project root" {
            paths.absolute(filePath = "engine/src/A.kt") shouldBe "/project/engine/src/A.kt"
        }

        "absolute file path is kept as is" {
            paths.absolute(filePath = "/other/B.kt") shouldBe "/other/B.kt"
        }

        "relativeOf returns the project relative path for a file inside the project" {
            paths.relativeOf(uri = "file:///project/engine/src/A.kt") shouldBe "engine/src/A.kt"
        }

        "relativeOf accepts the single slash file uri form" {
            paths.relativeOf(uri = "file:/project/engine/src/A.kt") shouldBe "engine/src/A.kt"
        }

        "relativeOf decodes percent encoded characters" {
            paths.relativeOf(uri = "file:///project/my%20dir/A.kt") shouldBe "my dir/A.kt"
        }

        "relativeOf is null for a file outside the project" {
            paths.relativeOf(uri = "file:///other/B.kt") shouldBe null
        }

        "relativeOf is null for a jar uri" {
            paths.relativeOf(uri = "jar:file:///libs/x.jar!/Foo.class") shouldBe null
        }

        "place reports the project relative path with a 1-based line" {
            paths.place(uri = "file:///project/engine/src/A.kt", position = Position(41, 3)) shouldBe "engine/src/A.kt:42"
        }

        "place flags library locations" {
            paths.place(uri = "jar:file:///libs/x.jar!/Foo.class", position = Position(0, 0)) shouldBe
                "[library] jar:file:///libs/x.jar!/Foo.class"
        }

        "checked accepts a file of the project, existing or not" {
            paths.checked(filePath = "engine/src/New.kt") shouldBe "/project/engine/src/New.kt"
        }

        "checked refuses a path that climbs out of the project" {
            shouldThrow<IllegalArgumentException> { paths.checked(filePath = "../etc/passwd") }.message shouldContain "outside the project"
        }

        "checked refuses an absolute path outside the project" {
            shouldThrow<IllegalArgumentException> { paths.checked(filePath = "/etc/passwd") }.message shouldContain "outside the project"
        }

        "checked refuses a sensitive file" {
            shouldThrow<IllegalArgumentException> { paths.checked(filePath = ".env") }.message shouldContain "sensitive"
        }

        "checked refuses a sensitive file below a directory" {
            shouldThrow<IllegalArgumentException> { paths.checked(filePath = "config/server.pem") }.message shouldContain "sensitive"
        }

        "checked refuses the git directory, whose configuration decides what Git runs" {
            shouldThrow<IllegalArgumentException> { paths.checked(filePath = ".git/config") }.message shouldContain "sensitive"
        }

        "checked refuses a symbolic link that leads out of the project" {
            val root = Files.createTempDirectory("kotlin-plugin-root")
            val outside = Files.createTempDirectory("kotlin-plugin-outside")
            Files.createSymbolicLink(root.resolve("escape"), outside)

            val error = shouldThrow<IllegalArgumentException> { ProjectPaths(root = root).checked(filePath = "escape/Secret.kt") }

            error.message shouldContain "resolves outside the project"
        }

        "checked accepts a symbolic link that stays in the project" {
            val root = Files.createTempDirectory("kotlin-plugin-root")
            val target = Files.createDirectory(root.resolve("real"))
            Files.createSymbolicLink(root.resolve("alias"), target)

            ProjectPaths(root = root).checked(filePath = "alias/A.kt") shouldBe
                root
                    .resolve("alias/A.kt")
                    .toAbsolutePath()
                    .normalize()
                    .toString()
        }
    })
