package io.biznet.agentos.tool

data class Functionality(
    val name: String,
    val description: String,
) {
  override fun toString(): String = """{"name": "$name", "description": "$description"}"""
}
