package io.whozoss.factory.agentattempt.domain

/** First actual execution model observed for the assigned agent in this attempt's turn. */
data class ResolvedModel(val provider: String, val model: String)
