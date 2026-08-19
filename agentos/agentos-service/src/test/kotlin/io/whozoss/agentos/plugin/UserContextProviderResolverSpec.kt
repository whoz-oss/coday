package io.whozoss.agentos.plugin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.sdk.scheduledPrompt.UserContextProvider
import org.pf4j.PluginManager
import java.util.UUID

/**
 * Unit spec for [UserContextProviderResolver].
 *
 * Mocks [PluginManager] directly — consistent with [io.whozoss.agentos.tool.ToolRegistryServiceUnitSpec]
 * which does the same for [io.whozoss.agentos.tool.ToolRegistryService].
 */
class UserContextProviderResolverSpec : StringSpec({

    fun makeProvider(): UserContextProvider = mockk<UserContextProvider>(relaxed = true)

    fun resolver(providers: List<UserContextProvider>): UserContextProviderResolver {
        val pluginManager = mockk<PluginManager>(relaxed = true).also {
            every { it.getExtensions(UserContextProvider::class.java) } returns providers
        }
        return UserContextProviderResolver(pluginManager)
    }

    "resolve returns null when no provider is registered" {
        resolver(emptyList()).resolve() shouldBe null
    }

    "resolve returns the single registered provider" {
        val provider = makeProvider()
        resolver(listOf(provider)).resolve() shouldBe provider
    }

    "resolve returns the first provider when multiple are registered" {
        val first = makeProvider()
        val second = makeProvider()
        resolver(listOf(first, second)).resolve() shouldBe first
    }

    "resolve returns non-null when exactly one provider is registered" {
        resolver(listOf(makeProvider())).resolve() shouldNotBe null
    }
})
