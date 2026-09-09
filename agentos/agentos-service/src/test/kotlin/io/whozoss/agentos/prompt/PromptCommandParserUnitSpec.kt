package io.whozoss.agentos.prompt

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.exception.PromptResolutionException
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

class PromptCommandParserUnitSpec : StringSpec({

    fun param(name: String, default: String = "") = PromptParameter(name = name, defaultValue = default)

    fun prompt(
        name: String,
        content: List<String>,
        parameters: List<PromptParameter> = emptyList(),
        agentConfigId: UUID? = null,
    ) = Prompt(
        metadata = EntityMetadata(),
        name = name,
        content = content,
        parameters = parameters,
        agentConfigId = agentConfigId,
    )

    /**
     * Helper: resolves [text] against the given effective [prompts].
     * The lambda is invoked lazily by the parser only when [text] starts with `/`.
     */
    fun resolve(text: String, vararg prompts: Prompt): List<ResolvedCommand> =
        PromptCommandParser.resolve(text) { prompts.toList() }

    /** Convenience: extract just the text strings from resolved commands. */
    fun resolveTexts(text: String, vararg prompts: Prompt): List<String> =
        resolve(text, *prompts).map { it.text }

    // --- Basic command resolution ---

    "non-slash text is returned unchanged" {
        resolve("hello world") shouldBe listOf(ResolvedCommand("hello world", null))
    }

    "bare slash is returned unchanged" {
        resolve("/") shouldBe listOf(ResolvedCommand("/", null))
    }

    "unknown prompt name is returned unchanged" {
        resolve("/unknown", prompt("known", listOf("content"))) shouldBe listOf(ResolvedCommand("/unknown", null))
    }

    "prompt with no placeholders returns joined content" {
        resolveTexts("/greet", prompt("greet", listOf("Hello!", "How are you?"))) shouldBe listOf("Hello!", "How are you?")
    }

    "prompt name matching is case-insensitive" {
        resolveTexts("/myprompt", prompt("MyPrompt", listOf("result"))) shouldBe listOf("result")
    }

    // --- {{ARGUMENTS}} ---

    "ARGUMENTS is replaced with raw argument string" {
        resolveTexts("/plan refactor the auth module", prompt("plan", listOf("Create a plan for: {{ARGUMENTS}}"))) shouldBe
            listOf("Create a plan for: refactor the auth module")
    }

    "ARGUMENTS with empty args is replaced with empty string" {
        resolveTexts("/plan", prompt("plan", listOf("Create a plan for: {{ARGUMENTS}}"))) shouldBe listOf("Create a plan for: ")
    }

    "ARGUMENTS captures full multi-word text including quotes" {
        resolveTexts("/echo hello world how are you", prompt("echo", listOf("{{ARGUMENTS}}"))) shouldBe
            listOf("hello world how are you")
    }

    // --- Named placeholders resolved positionally ---

    "single named placeholder resolved from first positional arg" {
        resolveTexts("/say hello", prompt("say", listOf("You said: {{msg}}"), listOf(param("msg")))) shouldBe listOf("You said: hello")
    }

    "quoted positional arg preserves spaces" {
        resolveTexts("/say \"hello world\"", prompt("say", listOf("{{msg}}"), listOf(param("msg")))) shouldBe listOf("hello world")
    }

    "single-quoted positional arg preserves spaces" {
        resolveTexts("/say 'hello world'", prompt("say", listOf("{{msg}}"), listOf(param("msg")))) shouldBe listOf("hello world")
    }

    "two named placeholders resolved from two positional args" {
        resolveTexts(
            "/greet Alice 30",
            prompt(
                "greet",
                listOf("Hello {{name}}, you are {{age}} years old."),
                listOf(param("name"), param("age")),
            ),
        ) shouldBe listOf("Hello Alice, you are 30 years old.")
    }

    "missing arg falls back to parameter defaultValue" {
        resolveTexts("/say", prompt("say", listOf("You said: {{msg}}"), listOf(param("msg", "nothing")))) shouldBe listOf("You said: nothing")
    }

    "missing arg with empty-string default resolves to empty" {
        resolveTexts("/say", prompt("say", listOf("[{{msg}}]"), listOf(param("msg", "")))) shouldBe listOf("[]")
    }

    "partial args: first provided, second uses default" {
        resolveTexts(
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
        resolveTexts(
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
        resolveTexts(
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
        resolveTexts(
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
        resolveTexts(
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
        resolveTexts(
            "/a",
            prompt("a", listOf("/b")),
            prompt("b", listOf("/c")),
            prompt("c", listOf("leaf")),
        ) shouldBe listOf("leaf")
    }

    "same prompt can be used in sibling branches without cycle error" {
        resolveTexts(
            "/main",
            prompt("main", listOf("/shared", "/shared")),
            prompt("shared", listOf("hello")),
        ) shouldBe listOf("hello", "hello")
    }

    "unresolved /subPrompt in content is kept as-is when not found" {
        resolveTexts(
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
        resolveTexts(
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

    // --- agentConfigId propagation ---

    "prompt with agentConfigId propagates it to each resolved line" {
        val agentId = UUID.randomUUID()
        val result = resolve(
            "/greet",
            prompt("greet", listOf("Hello!", "How are you?"), agentConfigId = agentId),
        )
        result shouldBe listOf(
            ResolvedCommand("Hello!", agentId),
            ResolvedCommand("How are you?", agentId),
        )
    }

    "prompt without agentConfigId produces ResolvedCommands with null" {
        val result = resolve(
            "/greet",
            prompt("greet", listOf("Hello!", "How are you?")),
        )
        result shouldBe listOf(
            ResolvedCommand("Hello!", null),
            ResolvedCommand("How are you?", null),
        )
    }

    "non-slash text produces ResolvedCommand with null agentConfigId" {
        resolve("plain message") shouldBe listOf(ResolvedCommand("plain message", null))
    }

    "recursive: parent agentConfigId=X, child agentConfigId=Y — each line carries its own" {
        val parentId = UUID.randomUUID()
        val childId = UUID.randomUUID()
        val result = resolve(
            "/parent",
            prompt("parent", listOf("parent line", "/child"), agentConfigId = parentId),
            prompt("child", listOf("child line"), agentConfigId = childId),
        )
        result shouldBe listOf(
            ResolvedCommand("parent line", parentId),
            ResolvedCommand("child line", childId),
        )
    }

    "recursive: parent agentConfigId=X, child agentConfigId=null — child lines have null" {
        val parentId = UUID.randomUUID()
        val result = resolve(
            "/parent",
            prompt("parent", listOf("parent line", "/child"), agentConfigId = parentId),
            prompt("child", listOf("child line"), agentConfigId = null),
        )
        result shouldBe listOf(
            ResolvedCommand("parent line", parentId),
            ResolvedCommand("child line", null),
        )
    }

    "recursive: parent agentConfigId=null, child agentConfigId=Y — child lines carry Y" {
        val childId = UUID.randomUUID()
        val result = resolve(
            "/parent",
            prompt("parent", listOf("parent line", "/child"), agentConfigId = null),
            prompt("child", listOf("child line"), agentConfigId = childId),
        )
        result shouldBe listOf(
            ResolvedCommand("parent line", null),
            ResolvedCommand("child line", childId),
        )
    }

    "unknown sub-prompt reference passes through with null agentConfigId" {
        val parentId = UUID.randomUUID()
        val result = resolve(
            "/parent",
            prompt("parent", listOf("before", "/unknown-sub", "after"), agentConfigId = parentId),
        )
        result shouldBe listOf(
            ResolvedCommand("before", parentId),
            ResolvedCommand("/unknown-sub", null),
            ResolvedCommand("after", parentId),
        )
    }
})
