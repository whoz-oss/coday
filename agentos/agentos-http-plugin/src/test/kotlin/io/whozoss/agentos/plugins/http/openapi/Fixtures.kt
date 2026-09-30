package io.whozoss.agentos.plugins.http.openapi

/** Loads an OpenAPI fixture from `src/test/resources/openapi`. */
object Fixtures {
    fun load(name: String): String =
        checkNotNull(Fixtures::class.java.getResourceAsStream("/openapi/$name")) { "Missing fixture $name" }
            .bufferedReader()
            .use { it.readText() }
}
