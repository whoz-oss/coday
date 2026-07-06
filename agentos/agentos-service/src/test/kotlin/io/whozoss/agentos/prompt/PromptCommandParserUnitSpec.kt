package io.whozoss.agentos.prompt

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

class PromptCommandParserUnitSpec : StringSpec({
    val namespaceId = UUID.randomUUID()
    val userId = UUID.randomUUID()

    fun param(name: String, default: String = "") = PromptParameter(name = name, defaultValue = default)

    fun prompt(
        name: String,
        content: List<String>,
        parameters: List<PromptParameter> = emptyList(),
    ) = Prompt(
        metadata = EntityMetadata(),
        name = name,
        content = content,
        parameters = parameters,
    )

    val promptService = mockk<PromptService>()
    val parser = PromptCommandParser(promptService)

    fun stubEffective(vararg prompts: Prompt) {
        every { promptService.findEffective(namespaceId, userId) } returns prompts.toList()
    }

    // --- Basic command resolution ---

    "non-slash text is returned unchanged" {
        stubEffective()
        parser.resolve("hello world", namespaceId, userId) shouldBe "hello world"
    }

    "bare slash is returned unchanged" {
        stubEffective()
        parser.resolve("/", namespaceId, userId) shouldBe "/"
    }

    "unknown prompt name is returned unchanged" {
        stubEffective(prompt("known", listOf("content")))
        parser.resolve("/unknown", namespaceId, userId) shouldBe "/unknown"
    }

    "prompt with no placeholders returns joined content" {
        stubEffective(prompt("greet", listOf("Hello!", "How are you?")))
        parser.resolve("/greet", namespaceId, userId) shouldBe "Hello!\n\nHow are you?"
    }

    "prompt name matching is case-insensitive" {
        stubEffective(prompt("MyPrompt", listOf("result")))
        parser.resolve("/myprompt", namespaceId, userId) shouldBe "result"
    }

    // --- {{ARGUMENTS}} ---

    "ARGUMENTS is replaced with raw argument string" {
        stubEffective(prompt("plan", listOf("Create a plan for: {{ARGUMENTS}}")))
        parser.resolve("/plan refactor the auth module", namespaceId, userId) shouldBe
            "Create a plan for: refactor the auth module"
    }

    "ARGUMENTS with empty args is replaced with empty string" {
        stubEffective(prompt("plan", listOf("Create a plan for: {{ARGUMENTS}}")))
        parser.resolve("/plan", namespaceId, userId) shouldBe "Create a plan for: "
    }

    "ARGUMENTS captures full multi-word text including quotes" {
        stubEffective(prompt("echo", listOf("{{ARGUMENTS}}")))
        parser.resolve("/echo hello world how are you", namespaceId, userId) shouldBe
            "hello world how are you"
    }

    // --- Named placeholders resolved positionally ---

    "single named placeholder resolved from first positional arg" {
        stubEffective(prompt("say", listOf("You said: {{msg}}"), listOf(param("msg"))))
        parser.resolve("/say hello", namespaceId, userId) shouldBe "You said: hello"
    }

    "quoted positional arg preserves spaces" {
        stubEffective(prompt("say", listOf("{{msg}}"), listOf(param("msg"))))
        parser.resolve("/say \"hello world\"", namespaceId, userId) shouldBe "hello world"
    }

    "single-quoted positional arg preserves spaces" {
        stubEffective(prompt("say", listOf("{{msg}}"), listOf(param("msg"))))
        parser.resolve("/say 'hello world'", namespaceId, userId) shouldBe "hello world"
    }

    "two named placeholders resolved from two positional args" {
        stubEffective(
            prompt(
                "greet",
                listOf("Hello {{name}}, you are {{age}} years old."),
                listOf(param("name"), param("age")),
            ),
        )
        parser.resolve("/greet Alice 30", namespaceId, userId) shouldBe "Hello Alice, you are 30 years old."
    }

    "missing arg falls back to parameter defaultValue" {
        stubEffective(prompt("say", listOf("You said: {{msg}}"), listOf(param("msg", "nothing"))))
        parser.resolve("/say", namespaceId, userId) shouldBe "You said: nothing"
    }

    "missing arg with empty-string default resolves to empty" {
        stubEffective(prompt("say", listOf("[{{msg}}]"), listOf(param("msg", ""))))
        parser.resolve("/say", namespaceId, userId) shouldBe "[]"
    }

    "partial args: first provided, second uses default" {
        stubEffective(
            prompt(
                "greet",
                listOf("{{greeting}} {{name}}"),
                listOf(param("greeting", "Hi"), param("name", "stranger")),
            ),
        )
        parser.resolve("/greet Hello", namespaceId, userId) shouldBe "Hello stranger"
    }

    // --- Unresolved placeholder validation ---

    "placeholder not matching any parameter throws BadRequestException" {
        stubEffective(prompt("bad", listOf("{{unknown}}"), emptyList()))
        shouldThrow<BadRequestException> {
            parser.resolve("/bad", namespaceId, userId)
        }
    }

    // --- Mixed {{ARGUMENTS}} + {{paramName}} ---

    "ARGUMENTS and named placeholder coexist" {
        stubEffective(
            prompt(
                "mix",
                listOf("Lang: {{language}} / All: {{ARGUMENTS}}"),
                listOf(param("language")),
            ),
        )
        parser.resolve("/mix Rust is great", namespaceId, userId) shouldBe
            "Lang: Rust / All: Rust is great"
    }

    // --- Same placeholder used multiple times ---

    "same placeholder used twice in content is replaced in both occurrences" {
        stubEffective(
            prompt(
                "repeat",
                listOf("{{word}} and {{word}} again"),
                listOf(param("word")),
            ),
        )
        parser.resolve("/repeat hello", namespaceId, userId) shouldBe "hello and hello again"
    }

    // --- Placeholder in multiple content lines ---

    "placeholder resolved across multiple content lines" {
        stubEffective(
            prompt(
                "multi",
                listOf("Hello {{name}}", "Goodbye {{name}}"),
                listOf(param("name")),
            ),
        )
        parser.resolve("/multi Alice", namespaceId, userId) shouldBe "Hello Alice\n\nGoodbye Alice"
    }
})
