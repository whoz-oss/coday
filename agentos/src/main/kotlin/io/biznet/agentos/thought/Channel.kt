package io.biznet.agentos.thought

import org.springframework.ai.chat.messages.Message

data class Channel(
    val cases: MutableList<Case> = mutableListOf(),
) {

    fun getMessages(): List<Message> = cases.flatMap { it.getMessages() }

    companion object {
        fun initThought(message: String): Channel = Channel(mutableListOf(Case(message)))
    }
}