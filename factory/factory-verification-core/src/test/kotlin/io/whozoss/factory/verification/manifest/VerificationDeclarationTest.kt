package io.whozoss.factory.verification.manifest

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.junit.jupiter.api.io.TempDir

class VerificationDeclarationTest {
    @TempDir lateinit var root: Path

    @Test fun `declarative resolution never executes build or discovers a parent manifest`() {
        Files.createDirectories(root.resolve("factory"))
        Files.writeString(root.resolve("factory/verification.json"),
            """{"schemaVersion":"1","verifications":{"build":{"command":"touch build.marker"}}}""")
        val target = TargetRepoVerifications.load(root)
        assertEquals(VerificationResolution.Declared("build", VerificationEntry("touch build.marker")),
            target.resolveVerification("build"))
        assertEquals(VerificationResolution.NotDeclared("other"), target.resolveVerification("other"))
        assertFalse(Files.exists(root.resolve("build.marker")))
        val child = Files.createDirectories(root.resolve("child"))
        assertEquals(VERIFICATION_MANIFEST_MISSING,
            assertFailsWith<VerificationManifestException> { TargetRepoVerifications.load(child) }.code)
    }

    @Test fun `preflight command is not part of the whitelist schema`() {
        assertEquals(VERIFICATION_MANIFEST_INVALID, assertFailsWith<VerificationManifestException> {
            VerificationManifestParser.parse(
                """{"schemaVersion":"1","verifications":{"build":{"command":"exit 0","preflightCommand":"exit 0"}}}""",
            )
        }.code)
    }
}
