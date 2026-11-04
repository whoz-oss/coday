package io.whozoss.agentos.messaging.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith

class TelegramFormatterUnitSpec :
    StringSpec({

        "the characters Telegram's parser reads are escaped" {
            TelegramFormatter.toHtml("a < b && c > d") shouldBe "a &lt; b &amp;&amp; c &gt; d"
        }

        "bold, italic, strikethrough and links become their tags" {
            TelegramFormatter.toHtml("**bold** and *italic* and _also_ and ~~gone~~ and [docs](https://example.com/a?b=1&c=2)") shouldBe
                "<b>bold</b> and <i>italic</i> and <i>also</i> and <s>gone</s> and " +
                "<a href=\"https://example.com/a?b=1&amp;c=2\">docs</a>"
        }

        "an underscore inside a word is no italic" {
            TelegramFormatter.toHtml("call snake_case_name now") shouldBe "call snake_case_name now"
        }

        "nothing inside inline code is read as Markdown" {
            TelegramFormatter.toHtml("run `a **b** <c>` now") shouldBe "run <code>a **b** &lt;c&gt;</code> now"
        }

        "a fenced block keeps its language and its content verbatim" {
            TelegramFormatter.toHtml("```kotlin\nval x = a < b && **c**\n```") shouldBe
                "<pre><code class=\"language-kotlin\">val x = a &lt; b &amp;&amp; **c**</code></pre>"
        }

        "headings are bold, bullets are dots, quotes are blockquotes" {
            TelegramFormatter.toHtml("## Plan\n- first\n  * nested\n> said") shouldBe
                "<b>Plan</b>\n• first\n  • nested\n<blockquote>said</blockquote>"
        }

        "a table is kept as preformatted text: Telegram has no table" {
            TelegramFormatter.toHtml("| a | b |\n|---|---|\n| 1 | <2> |") shouldBe "<pre>| a | b |\n|---|---|\n| 1 | &lt;2&gt; |</pre>"
        }

        "a short message is one chunk" {
            TelegramFormatter.split("Hello\n\nWorld") shouldBe listOf("Hello\n\nWorld")
        }

        "a long message is cut between paragraphs, each chunk rendering under the limit" {
            val paragraph = "word ".repeat(200).trim()
            val chunks = TelegramFormatter.split(List(10) { paragraph }.joinToString("\n\n"))

            chunks.size shouldBe 4
            chunks.forEach { TelegramFormatter.toHtml(it).length shouldBeLessThanOrEqual TelegramFormatter.MAX_MESSAGE_LENGTH }
            chunks.joinToString("\n\n") shouldBe List(10) { paragraph }.joinToString("\n\n")
        }

        "a code block longer than a chunk is closed and reopened, never cut open" {
            val code = (1..400).joinToString("\n") { "line number $it of the program" }
            val chunks = TelegramFormatter.split("```text\n$code\n```")

            chunks shouldHaveSize 4
            chunks.forEach { chunk ->
                chunk shouldStartWith "```text\n"
                chunk shouldEndWith "\n```"
                TelegramFormatter.toHtml(chunk) shouldStartWith "<pre><code class=\"language-text\">"
            }
        }
    })
