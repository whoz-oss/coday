package io.whozoss.agentos.usage

/**
 * What generated a [UsageRecord].
 *
 * [LLM] covers every large-language-model call made for the work of a case (agent turns, side questions,
 * utility calls). Values are added here without breaking existing records, because [UsageRecord] stores the
 * enum name as a plain string; a reader must not assume [LLM] is the only one.
 */
enum class UsageSource {
    LLM,

    /**
     * A keep-alive ping of a conversation's prompt cache ([io.whozoss.agentos.caseFlow.CaseKeepAliveScheduler]):
     * an LLM call that only re-reads the cached prompt of the case while it is idle. Counts toward the case cost
     * like any other record of the case.
     */
    KEEP_ALIVE,
}
