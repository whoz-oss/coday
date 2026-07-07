package io.whozoss.agentos.prompt

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.exception.PromptResolutionException
import io.whozoss.agentos.sdk.entity.EntityMetadata

class PromptCommandParserUnitSpec : StringSpec({

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

    /**
     * Helper: resolves [text] against the given effective [prompts].
     * The lambda is invoked lazily by the parser only when [text] starts with `/`.
     */
    fun resolve(text: String, vararg prompts: Prompt): List<String> =
        PromptCommandParser.resolve(text) { prompts.toList() }

    // --- Basic command resolution ---

    "non-slash text is returned unchanged" {
        resolve("hello world") shouldBe listOf("hello world")
    }

    "bare slash is returned unchanged" {
        resolve("/") shouldBe listOf("/")
    }

    "unknown prompt name is returned unchanged" {
        resolve("/unknown", prompt("known", listOf("content"))) shouldBe listOf("/unknown")
    }

    "prompt with no placeholders returns joined content" {
        resolve("/greet", prompt("greet", listOf("Hello!", "How are you?"))) shouldBe listOf("Hello!", "How are you?")
    }

    "prompt name matching is case-insensitive" {
        resolve("/myprompt", prompt("MyPrompt", listOf("result"))) shouldBe listOf("result")
    }

    // --- {{ARGUMENTS}} ---

    "ARGUMENTS is replaced with raw argument string" {
        resolve("/plan refactor the auth module", prompt("plan", listOf("Create a plan for: {{ARGUMENTS}}"))) shouldBe
            listOf("Create a plan for: refactor the auth module")
    }

    "ARGUMENTS with empty args is replaced with empty string" {
        resolve("/plan", prompt("plan", listOf("Create a plan for: {{ARGUMENTS}}"))) shouldBe listOf("Create a plan for: ")
    }

    "ARGUMENTS captures full multi-word text including quotes" {
        resolve("/echo hello world how are you", prompt("echo", listOf("{{ARGUMENTS}}"))) shouldBe
            listOf("hello world how are you")
    }

    // --- Named placeholders resolved positionally ---

    "single named placeholder resolved from first positional arg" {
        resolve("/say hello", prompt("say", listOf("You said: {{msg}}"), listOf(param("msg")))) shouldBe listOf("You said: hello")
    }

    "quoted positional arg preserves spaces" {
        resolve("/say \"hello world\"", prompt("say", listOf("{{msg}}"), listOf(param("msg")))) shouldBe listOf("hello world")
    }

    "single-quoted positional arg preserves spaces" {
        resolve("/say 'hello world'", prompt("say", listOf("{{msg}}"), listOf(param("msg")))) shouldBe listOf("hello world")
    }

    "two named placeholders resolved from two positional args" {
        resolve(
            "/greet Alice 30",
            prompt(
                "greet",
                listOf("Hello {{name}}, you are {{age}} years old."),
                listOf(param("name"), param("age")),
            ),
        ) shouldBe listOf("Hello Alice, you are 30 years old.")
    }

    "missing arg falls back to parameter defaultValue" {
        resolve("/say", prompt("say", listOf("You said: {{msg}}"), listOf(param("msg", "nothing")))) shouldBe listOf("You said: nothing")
    }

    "missing arg with empty-string default resolves to empty" {
        resolve("/say", prompt("say", listOf("[{{msg}}]"), listOf(param("msg", "")))) shouldBe listOf("[]")
    }

    "partial args: first provided, second uses default" {
        resolve(
            "/greet Hello",
            prompt(
                "greet",
                listOf("{{greeting}} {{name}}"),
                listOf(param("greeting", "Hi"), param("name", "stranger")),
            ),
        ) shouldBe listOf("Hello stranger")
    }

    // --- Unresolved placeholder validation ---

    "placeholder not matching any parameter throws PromptResolutionException" {
        shouldThrow<PromptResolutionException> {
            resolve("/bad", prompt("bad", listOf("{{unknown}}"), emptyList()))
        }
    }

    // --- Mixed {{ARGUMENTS}} + {{paramName}} ---

    "ARGUMENTS and named placeholder coexist" {
        resolve(
            "/mix Rust is great",
            prompt(
                "mix",
                listOf("Lang: {{language}} / All: {{ARGUMENTS}}"),
                listOf(param("language")),
            ),
        ) shouldBe listOf("Lang: Rust / All: Rust is great")
    }

    // --- Same placeholder used multiple times ---

    "same placeholder used twice in content is replaced in both occurrences" {
        resolve(
            "/repeat hello",
            prompt(
                "repeat",
                listOf("{{word}} and {{word}} again"),
                listOf(param("word")),
            ),
        ) shouldBe listOf("hello and hello again")
    }

    // --- Placeholder in multiple content lines ---

    "placeholder resolved across multiple content lines" {
        resolve(
            "/multi Alice",
            prompt(
                "multi",
                listOf("Hello {{name}}", "Goodbye {{name}}"),
                listOf(param("name")),
            ),
        ) shouldBe listOf("Hello Alice", "Goodbye Alice")
    }

    // --- Recursive resolution ---

    "prompt content containing /subPrompt is resolved recursively" {
        resolve(
            "/main",
            prompt("main", listOf("step 1", "/sub arg1", "step 3")),
            prompt("sub", listOf("sub-step for: {{ARGUMENTS}}")),
        ) shouldBe listOf(
            "step 1",
            "sub-step for: arg1",
            "step 3",
        )
    }

    "deeply nested prompts are resolved recursively" {
        resolve(
            "/a",
            prompt("a", listOf("/b")),
            prompt("b", listOf("/c")),
            prompt("c", listOf("leaf")),
        ) shouldBe listOf("leaf")
    }

    "same prompt can be used in sibling branches without cycle error" {
        resolve(
            "/main",
            prompt("main", listOf("/shared", "/shared")),
            prompt("shared", listOf("hello")),
        ) shouldBe listOf("hello", "hello")
    }

    "unresolved /subPrompt in content is kept as-is when not found" {
        resolve(
            "/main",
            prompt("main", listOf("step 1", "/unknown-sub", "step 3")),
        ) shouldBe listOf(
            "step 1",
            "/unknown-sub",
            "step 3",
        )
    }

    // --- Cycle detection ---

    "direct cycle with no arguments throws PromptResolutionException" {
        shouldThrow<PromptResolutionException> {
            resolve(
                "/ping",
                prompt("ping", listOf("/pong")),
                prompt("pong", listOf("/ping")),
            )
        }.message shouldBe "Cycle detected in prompt resolution: ping \u2192 pong \u2192 ping"
    }

    "indirect cycle: A calls B which calls A with same args throws PromptResolutionException" {
        shouldThrow<PromptResolutionException> {
            resolve(
                "/a",
                prompt("a", listOf("before", "/b", "after")),
                prompt("b", listOf("/a")),
            )
        }
    }

    "same prompt with different resolved arguments is not a cycle" {
        resolve(
            "/router",
            prompt("router", listOf("/worker one", "/worker two")),
            prompt("worker", listOf("done: {{ARGUMENTS}}")),
        ) shouldBe listOf("done: one", "done: two")
    }

    "same prompt with same arguments in ancestry is a cycle even with a different name in between" {
        shouldThrow<PromptResolutionException> {
            resolve(
                "/outer foo",
                prompt("outer", listOf("O: {{v}}", "/middle"), listOf(param("v"))),
                prompt("middle", listOf("/outer foo")),
            )
        }
    }

    "depth limit throws PromptResolutionException" {
        val prompts = (0..10).map { i ->
            prompt("p$i", listOf(if (i < 10) "/p${i + 1}" else "end"))
        }
        shouldThrow<PromptResolutionException> {
            resolve("/p0", *prompts.toTypedArray())
        }.message shouldBe "Maximum prompt nesting depth (10) exceeded"
    }
})
