package io.whozoss.agentos.agent

const val CONFIRMATION_ANSWER_CONFIRM: String = "Confirmer"
const val CONFIRMATION_ANSWER_REJECT: String = "Annuler"

/** Fixed two-button options surfaced to the UI for a confirmation QuestionEvent. */
val CONFIRMATION_OPTIONS: List<String> = listOf(CONFIRMATION_ANSWER_CONFIRM, CONFIRMATION_ANSWER_REJECT)
