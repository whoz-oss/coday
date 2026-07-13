package io.whozoss.agentos.plugins.mcp

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import java.time.Instant

/**
 * Unit tests for [McpConnectionPool] using a mock [connectionFactory] so no real
 * MCP server processes are started.
 *
 * The factory returns [PooledMcpConnection] mocks (an interface), avoiding any need
 * to subclass the final [StdioMcpConnection] class.
 *
 * Each test builds its own pool and calls [McpConnectionPool.start] /
 * [McpConnectionPool.shutdown] explicitly, keeping tests fully isolated.
 */
class McpConnectionPoolUnitSpec : StringSpec({

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Returns a config whose [McpServerConfig.configHash] is unique per [index]. */
    fun configFor(index: Int, idleTimeoutMinutes: Long = DEFAULT_IDLE_TIMEOUT_MINUTES) =
        McpServerConfig(command = "cmd-$index", idleTimeoutMinutes = idleTimeoutMinutes)

    /** Builds a mock [PooledMcpConnection] pre-wired with the given liveness and a recent [lastUsed]. */
    fun mockConnection(alive: Boolean = true): PooledMcpConnection {
        val mock = mockk<PooledMcpConnection>()
        every { mock.isAlive() } returns alive
        every { mock.close() } just runs
        every { mock.lastUsed } returns Instant.now()
        every { mock.tools } returns emptyList()
        return mock
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    "acquire creates a new connection when pool is empty" {
        val mock = mockConnection(alive = true)
        val pool = McpConnectionPool(connectionFactory = { _, _ -> mock })
        pool.start()
        try {
            val result = pool.acquire(configFor(1))
            result shouldBe mock
        } finally {
            pool.shutdown()
        }
    }

    "acquire returns the same connection for the same config" {
        val mock = mockConnection(alive = true)
        var factoryCalls = 0
        val pool = McpConnectionPool(connectionFactory = { _, _ ->
            factoryCalls++
            mock
        })
        pool.start()
        try {
            val first = pool.acquire(configFor(1))
            val second = pool.acquire(configFor(1))

            factoryCalls shouldBe 1
            first shouldBe mock
            second shouldBe mock
        } finally {
            pool.shutdown()
        }
    }

    "acquire replaces a dead connection" {
        val deadMock = mockConnection(alive = false)
        val aliveMock = mockConnection(alive = true)
        val mocks = ArrayDeque(listOf(deadMock, aliveMock))
        val pool = McpConnectionPool(connectionFactory = { _, _ -> mocks.removeFirst() })
        pool.start()
        try {
            val first = pool.acquire(configFor(1))
            first shouldBe deadMock

            // Second acquire: pool detects deadMock is dead and replaces it.
            val second = pool.acquire(configFor(1))
            verify(exactly = 1) { deadMock.close() }
            second shouldBe aliveMock
            second shouldNotBe deadMock
        } finally {
            pool.shutdown()
        }
    }

    "acquire throws IllegalStateException when pool is at capacity" {
        // Fill pool to MAX_POOL_SIZE (200) with distinct configs, each backed by its own mock.
        val pool = McpConnectionPool(connectionFactory = { _, _ -> mockConnection(alive = true) })
        pool.start()
        try {
            for (i in 1..200) {
                pool.acquire(configFor(i))
            }
            // Config #201 must be rejected.
            shouldThrow<IllegalStateException> {
                pool.acquire(configFor(201))
            }
        } finally {
            pool.shutdown()
        }
    }

    "eviction removes idle connections" {
        val idleMock = mockConnection(alive = true)
        every { idleMock.lastUsed } returns Instant.now().minusSeconds(600)

        val config = configFor(1, idleTimeoutMinutes = 1)
        val freshMock = mockConnection(alive = true)
        val mocks = ArrayDeque(listOf(idleMock, freshMock))
        val pool = McpConnectionPool(connectionFactory = { _, _ -> mocks.removeFirst() })
        pool.start()
        try {
            pool.acquire(config)

            // Trigger eviction directly (no need to wait for the scheduler).
            pool.evictIdleConnections()

            verify(exactly = 1) { idleMock.close() }

            // A subsequent acquire must create a new connection (idleMock was evicted).
            val afterEviction = pool.acquire(config)
            afterEviction shouldBe freshMock
        } finally {
            pool.shutdown()
        }
    }

    "shutdown closes all connections and clears the pool" {
        val mocks = (1..3).map { mockConnection(alive = true) }
        val mockQueue = ArrayDeque(mocks)
        val pool = McpConnectionPool(connectionFactory = { _, _ -> mockQueue.removeFirst() })
        pool.start()

        for (i in 1..3) {
            pool.acquire(configFor(i))
        }

        pool.shutdown()

        mocks.forEach { mock ->
            verify(exactly = 1) { mock.close() }
        }
    }

    "eviction does not remove connections that are still within idle timeout" {
        val aliveMock = mockConnection(alive = true)
        every { aliveMock.lastUsed } returns Instant.now()

        val config = configFor(1, idleTimeoutMinutes = 10)
        val pool = McpConnectionPool(connectionFactory = { _, _ -> aliveMock })
        pool.start()
        try {
            pool.acquire(config)

            pool.evictIdleConnections()

            verify(exactly = 0) { aliveMock.close() }

            // Pool still holds the connection — same instance returned on next acquire.
            val reused = pool.acquire(config)
            reused shouldBe aliveMock
        } finally {
            pool.shutdown()
        }
    }
})
