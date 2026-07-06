package io.whozoss.agentos.prompt

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.exception.PromptResolutionException
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
        parser.resolve("hello world", namespaceId, userId) shouldBe listOf("hello world")
    }

    "bare slash is returned unchanged" {
        stubEffective()
        parser.resolve("/", namespaceId, userId) shouldBe listOf("/")
    }

    "unknown prompt name is returned unchanged" {
        stubEffective(prompt("known", listOf("content")))
        parser.resolve("/unknown", namespaceId, userId) shouldBe listOf("/unknown")
    }

    "prompt with no placeholders returns joined content" {
        stubEffective(prompt("greet", listOf("Hello!", "How are you?")))
        parser.resolve("/greet", namespaceId, userId) shouldBe listOf("Hello!", "How are you?")
    }

    "prompt name matching is case-insensitive" {
        stubEffective(prompt("MyPrompt", listOf("result")))
        parser.resolve("/myprompt", namespaceId, userId) shouldBe listOf("result")
    }

    // --- {{ARGUMENTS}} ---

    "ARGUMENTS is replaced with raw argument string" {
        stubEffective(prompt("plan", listOf("Create a plan for: {{ARGUMENTS}}")))
        parser.resolve("/plan refactor the auth module", namespaceId, userId) shouldBe
            listOf("Create a plan for: refactor the auth module")
    }

    "ARGUMENTS with empty args is replaced with empty string" {
        stubEffective(prompt("plan", listOf("Create a plan for: {{ARGUMENTS}}")))
        parser.resolve("/plan", namespaceId, userId) shouldBe listOf("Create a plan for: ")
    }

    "ARGUMENTS captures full multi-word text including quotes" {
        stubEffective(prompt("echo", listOf("{{ARGUMENTS}}")))
        parser.resolve("/echo hello world how are you", namespaceId, userId) shouldBe
            listOf("hello world how are you")
    }

    // --- Named placeholders resolved positionally ---

    "single named placeholder resolved from first positional arg" {
        stubEffective(prompt("say", listOf("You said: {{msg}}"), listOf(param("msg"))))
        parser.resolve("/say hello", namespaceId, userId) shouldBe listOf("You said: hello")
    }

    "quoted positional arg preserves spaces" {
        stubEffective(prompt("say", listOf("{{msg}}"), listOf(param("msg"))))
        parser.resolve("/say \"hello world\"", namespaceId, userId) shouldBe listOf("hello world")
    }

    "single-quoted positional arg preserves spaces" {
        stubEffective(prompt("say", listOf("{{msg}}"), listOf(param("msg"))))
        parser.resolve("/say 'hello world'", namespaceId, userId) shouldBe listOf("hello world")
    }

    "two named placeholders resolved from two positional args" {
        stubEffective(
            prompt(
                "greet",
                listOf("Hello {{name}}, you are {{age}} years old."),
                listOf(param("name"), param("age")),
            ),
        )
        parser.resolve("/greet Alice 30", namespaceId, userId) shouldBe listOf("Hello Alice, you are 30 years old.")
    }

    "missing arg falls back to parameter defaultValue" {
        stubEffective(prompt("say", listOf("You said: {{msg}}"), listOf(param("msg", "nothing"))))
        parser.resolve("/say", namespaceId, userId) shouldBe listOf("You said: nothing")
    }

    "missing arg with empty-string default resolves to empty" {
        stubEffective(prompt("say", listOf("[{{msg}}]"), listOf(param("msg", ""))))
        parser.resolve("/say", namespaceId, userId) shouldBe listOf("[]")
    }

    "partial args: first provided, second uses default" {
        stubEffective(
            prompt(
                "greet",
                listOf("{{greeting}} {{name}}"),
                listOf(param("greeting", "Hi"), param("name", "stranger")),
            ),
        )
        parser.resolve("/greet Hello", namespaceId, userId) shouldBe listOf("Hello stranger")
    }

    // --- Unresolved placeholder validation ---

    "placeholder not matching any parameter throws PromptResolutionException" {
        stubEffective(prompt("bad", listOf("{{unknown}}"), emptyList()))
        shouldThrow<PromptResolutionException> {
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
            listOf("Lang: Rust / All: Rust is great")
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
        parser.resolve("/repeat hello", namespaceId, userId) shouldBe listOf("hello and hello again")
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
        parser.resolve("/multi Alice", namespaceId, userId) shouldBe listOf("Hello Alice", "Goodbye Alice")
    }

    // --- Recursive resolution ---

    "prompt content containing /subPrompt is resolved recursively" {
        stubEffective(
            prompt("main", listOf("step 1", "/sub arg1", "step 3")),
            prompt("sub", listOf("sub-step for: {{ARGUMENTS}}")),
        )
        parser.resolve("/main", namespaceId, userId) shouldBe listOf(
            "step 1",
            "sub-step for: arg1",
            "step 3",
        )
    }

    "deeply nested prompts are resolved recursively" {
        stubEffective(
            prompt("a", listOf("/b")),
            prompt("b", listOf("/c")),
            prompt("c", listOf("leaf")),
        )
        parser.resolve("/a", namespaceId, userId) shouldBe listOf("leaf")
    }

    "same prompt can be used in sibling branches without cycle error" {
        stubEffective(
            prompt("main", listOf("/shared", "/shared")),
            prompt("shared", listOf("hello")),
        )
        parser.resolve("/main", namespaceId, userId) shouldBe listOf("hello", "hello")
    }

    "unresolved /subPrompt in content is kept as-is when not found" {
        stubEffective(
            prompt("main", listOf("step 1", "/unknown-sub", "step 3")),
        )
        parser.resolve("/main", namespaceId, userId) shouldBe listOf(
            "step 1",
            "/unknown-sub",
            "step 3",
        )
    }

    // --- Cycle detection ---

    "direct cycle with no arguments throws PromptResolutionException" {
        stubEffective(
            prompt("ping", listOf("/pong")),
            prompt("pong", listOf("/ping")),
        )
        shouldThrow<PromptResolutionException> {
            parser.resolve("/ping", namespaceId, userId)
        }.message shouldBe "Cycle detected in prompt resolution: ping \u2192 pong \u2192 ping"
    }

    "indirect cycle: A calls B which calls A with same args throws PromptResolutionException" {
        // /a → content calls /b → content calls /a (same args: none) → cycle
        stubEffective(
            prompt("a", listOf("before", "/b", "after")),
            prompt("b", listOf("/a")),
        )
        shouldThrow<PromptResolutionException> {
            parser.resolve("/a", namespaceId, userId)
        }
    }

    "same prompt with different resolved arguments is not a cycle" {
        // /router calls /worker with arg "x", then /worker with arg "y" — sibling calls, no cycle
        stubEffective(
            prompt("router", listOf("/worker one", "/worker two")),
            prompt("worker", listOf("done: {{ARGUMENTS}}")),
        )
        parser.resolve("/router", namespaceId, userId) shouldBe listOf("done: one", "done: two")
    }

    "same prompt with same arguments in ancestry is a cycle even with a different name in between" {
        // /outer(foo) → /middle → /outer(foo) → cycle
        stubEffective(
            prompt("outer", listOf("O: {{v}}", "/middle"), listOf(param("v"))),
            prompt("middle", listOf("/outer foo")),
        )
        shouldThrow<PromptResolutionException> {
            parser.resolve("/outer foo", namespaceId, userId)
        }
    }

    "depth limit throws PromptResolutionException" {
        // Create a chain of 11 prompts: p0 -> p1 -> ... -> p10
        val prompts = (0..10).map { i ->
            prompt("p$i", listOf(if (i < 10) "/p${i + 1}" else "end"))
        }
        stubEffective(*prompts.toTypedArray())
        shouldThrow<PromptResolutionException> {
            parser.resolve("/p0", namespaceId, userId)
        }.message shouldBe "Maximum prompt nesting depth (10) exceeded"
    }
})
