package io.biznet.agentos.thought

import org.springframework.ai.chat.messages.Message

data class Chanel(
    val cases: MutableList<Case> = mutableListOf(),
) {

    fun getMessages(): List<Message> = cases.flatMap { it.getMessages() }

    companion object {
        fun initThought(message: String): Chanel = Chanel(mutableListOf(Case(message)))
    }
}