package io.whozoss.agentos.plugins.kotlin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class PublishedServerUnitSpec :
    StringSpec({
        "the port, the process id and the build of a port file should parse" {
            PublishedServer.parse(text = "56300\n17912\nILS-271.2085.0\n") shouldBe PublishedServer(port = 56300, processId = 17912)
        }

        "a port file without its build line should parse" {
            PublishedServer.parse(text = "56300\n17912") shouldBe PublishedServer(port = 56300, processId = 17912)
        }

        "blanks around the numbers should be ignored" {
            PublishedServer.parse(text = "  56300 \r\n 17912 \r\n") shouldBe PublishedServer(port = 56300, processId = 17912)
        }

        "an empty file, which a server is still writing, should not parse" {
            PublishedServer.parse(text = "").shouldBeNull()
        }

        "a port file without a process id should not parse" {
            PublishedServer.parse(text = "56300\n").shouldBeNull()
        }

        "text that is not numbers should not parse" {
            PublishedServer.parse(text = "port\npid\n").shouldBeNull()
        }

        "a port outside the valid range should not parse" {
            listOf("0\n17912", "-1\n17912", "65536\n17912").forEach { text ->
                PublishedServer.parse(text = text).shouldBeNull()
            }
        }
    })
