# Testing Guidelines

## Stack

- **Kotest** `StringSpec` for test structure
- **MockK** for mocking
- Global config in `ProjectConfig`: sequential execution, 30 s timeout, `InstancePerLeaf` isolation

## Test Style

```kotlin
class MyServiceTest : StringSpec({
    timeout = 5000

    "should do something specific" {
        val dep = mockk<MyDep>()
        every { dep.call() } returns "result"

        val result = MyService(dep).doSomething()

        result shouldBe "expected"
        verify(exactly = 1) { dep.call() }
    }
})
```

Each test states a single, named behaviour. Test the public API of the class under test, not its internals. Mock at the boundary — never mock the class being tested.

## What Not to Test

- `InMemoryEntityRepository` subclasses — the base class is already tested
- Spring wiring / bean creation — covered by `AgentOSApplicationTest` (context load)
- Simple data classes with no logic

## Spring Integration Tests

Annotate with `@SpringBootTest` + `@ActiveProfiles("test")` and add `SpringExtension` via `override fun extensions()`.

## Coroutine Tests

For `Flow`-based code, collect with `.toList()` inside a coroutine. Set a `timeout` at the spec level to avoid hanging tests. Use `delay(100)` to yield before emitting when testing hot flows.
