package io.whozoss.agentos.git

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.slf4j.LoggerFactory
import java.util.UUID

/** The frozen settings of a binding outlive the code that wrote them: their stored form is a contract. */
class CaseResourceBindingNodeSpec : StringSpec({
    val configId = UUID.fromString("6f1c2b0e-6a8f-4f5e-9a51-2f4f3f0d9c11")
    val namespaceId = UUID.fromString("0b7d1c4e-3e5a-4c1f-8d2b-7a9e6f5c4d32")
    val serviceAuthSettingId = UUID.fromString("c2d4e6f8-1a3b-4c5d-8e7f-9a0b1c2d3e43")
    val expected = GitRepositorySettings(
        configId = configId, namespaceId = namespaceId, repositoryUrl = "https://forge.example/org/project.git",
        mainBranch = "develop", serviceAuthSettingId = serviceAuthSettingId, autoWorktreeForRootCases = true,
        setupCommand = "pnpm install --ignore-scripts",
    )

    fun node(settingsJson: String?) = CaseResourceBindingNode(
        id = UUID.randomUUID().toString(), rootCaseId = UUID.randomUUID().toString(), namespaceId = namespaceId.toString(),
        integrationConfigId = configId.toString(), status = CaseResourceStatus.REQUESTED.name, settingsJson = settingsJson,
    )

    "settings stored in the current format still read, field for field" {
        // Never edit this literal to make a change pass: rows already stored keep this shape.
        // A new field needs a default value, and a renamed one breaks every equipped family.
        val stored = """{"autoWorktreeForRootCases":true,"configId":"$configId","mainBranch":"develop",""" +
            """"namespaceId":"$namespaceId","repositoryUrl":"https://forge.example/org/project.git",""" +
            """"serviceAuthSettingId":"$serviceAuthSettingId","setupCommand":"pnpm install --ignore-scripts"}"""

        node(stored).toDomain().settings shouldBe expected
    }

    "a field this version does not know is ignored" {
        val stored = """{"configId":"$configId","namespaceId":"$namespaceId","repositoryUrl":"https://forge.example/org/project.git",""" +
            """"mainBranch":"develop","serviceAuthSettingId":"$serviceAuthSettingId","autoWorktreeForRootCases":true,""" +
            """"setupCommand":"pnpm install --ignore-scripts","submodules":true}"""

        node(stored).toDomain().settings shouldBe expected
    }

    "settings written by the node read back identically" {
        val binding = CaseResourceBinding(rootCaseId = UUID.randomUUID(), namespaceId = namespaceId, integrationConfigId = configId,
            settings = expected)

        CaseResourceBindingNode.fromDomain(binding).toDomain().settings shouldBe expected
    }

    "unreadable settings read as none, and the warning never repeats them" {
        val logger = LoggerFactory.getLogger(CaseResourceBindingNode::class.java) as Logger
        val logs = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(logs)
        try {
            listOf(
                """{"setupCommand":"echo synthetic-secret", "configId": """,
                """{"setupCommand": synthetic-secret }""",
                """{"configId":"synthetic-secret"}""",
            ).forEach { node(it).toDomain().settings shouldBe null }
            node(null).toDomain().settings shouldBe null
        } finally {
            logger.detachAppender(logs)
            logs.stop()
        }

        logs.list.count { it.level == Level.WARN } shouldBe 3
        val rendered = logs.list.joinToString("\n") {
            it.formattedMessage + (it.throwableProxy?.let(ThrowableProxyUtil::asString) ?: "")
        }
        rendered shouldNotContain "synthetic-secret"
    }
})
