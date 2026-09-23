package io.whozoss.agentos.plugins.http.file

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.engine.spec.tempdir
import io.kotest.engine.spec.tempfile
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.FileSystems

class SpecFileReaderUnitSpec : StringSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val reader = SpecFileReader()

    /** Revoking the read permission has no effect for root, and needs a POSIX filesystem. */
    val canRevokeReadPermission = "posix" in FileSystems.getDefault().supportedFileAttributeViews() &&
        System.getProperty("user.name") != "root"
    val document = "openapi: 3.0.0\ninfo: {title: Café, version: '1'}\npaths: {}\n"

    "reads a UTF-8 file" {
        val file = tempfile(suffix = ".yaml")
        file.writeText(document)
        val read = reader.read(file.absolutePath, maxBytes = 10_000).shouldBeInstanceOf<FileReadOutcome.Read>()
        read.text shouldBe document
    }

    "the stamp of a file is its last modification time and size" {
        val file = tempfile(suffix = ".yaml")
        file.writeText(document)
        reader.stamp(file.absolutePath) shouldBe
            FileStamp(lastModifiedMillis = file.lastModified(), size = file.length())
    }

    "a missing file is Failed and has no stamp" {
        val path = "${tempdir().absolutePath}/missing.yaml"
        reader.read(path, maxBytes = 10_000).shouldBeInstanceOf<FileReadOutcome.Failed>().reason shouldBe
            "document file does not exist"
        reader.stamp(path).shouldBeNull()
    }

    "a directory is not a regular file" {
        val dir = tempdir()
        reader.read(dir.absolutePath, maxBytes = 10_000).shouldBeInstanceOf<FileReadOutcome.Failed>().reason shouldBe
            "document file is not a regular file"
    }

    "a file that is not valid UTF-8 is Failed with the exception type and without the path" {
        val file = tempfile(suffix = ".yaml")
        file.writeBytes(byteArrayOf(0x6F, 0x70, 0xFF.toByte(), 0xFE.toByte()))
        val failed = reader.read(file.absolutePath, maxBytes = 10_000).shouldBeInstanceOf<FileReadOutcome.Failed>()
        failed.reason shouldBe "document file cannot be read (MalformedInputException)"
    }

    "an unreadable file is Failed".config(enabled = canRevokeReadPermission) {
        val file = tempfile(suffix = ".yaml")
        file.writeText(document)
        file.setReadable(false) shouldBe true
        try {
            val failed = reader.read(file.absolutePath, maxBytes = 10_000).shouldBeInstanceOf<FileReadOutcome.Failed>()
            failed.reason shouldBe "document file is not readable"
        } finally {
            file.setReadable(true)
        }
    }

    "a file larger than maxBytes is Failed naming both sizes" {
        val file = tempfile(suffix = ".yaml")
        file.writeText(document)
        val failed = reader.read(file.absolutePath, maxBytes = 10).shouldBeInstanceOf<FileReadOutcome.Failed>()
        failed.reason shouldContain "larger than the allowed 10 bytes"
    }
})
