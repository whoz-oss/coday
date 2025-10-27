package io.biznet.agentos.tools.domain

interface StandardTool {
    val name: String
    val description: String
    val inputSchema: String
    val version: String
    fun execute(input: String): String
}