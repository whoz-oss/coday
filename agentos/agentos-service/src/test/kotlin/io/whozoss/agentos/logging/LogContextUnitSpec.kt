package io.whozoss.agentos.logging

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.slf4j.MDC
import java.util.UUID

class LogContextUnitSpec :
    StringSpec({
        // MDC is thread-local and a test coroutine may resume on any thread: suspending tests start
        // from an explicit empty MDCContext, blocking ones clear the MDC of their own thread.

        "withLogContext exposes its entries inside the block and skips null values" {
            withContext(MDCContext(emptyMap())) {
                val caseId = UUID.randomUUID()
                withLogContext(LogContext.CASE_ID to caseId, LogContext.USER_ID to null) {
                    MDC.get(LogContext.CASE_ID) shouldBe caseId.toString()
                    MDC.get(LogContext.USER_ID).shouldBeNull()
                }
            }
        }

        "withLogContext merges with the enclosing context and restores it afterwards" {
            withContext(MDCContext(emptyMap())) {
                withLogContext(LogContext.CASE_ID to "case-1") {
                    withLogContext(LogContext.AGENT_NAME to "dev") {
                        MDC.get(LogContext.CASE_ID) shouldBe "case-1"
                        MDC.get(LogContext.AGENT_NAME) shouldBe "dev"
                    }
                    MDC.get(LogContext.AGENT_NAME).shouldBeNull()
                    MDC.get(LogContext.CASE_ID) shouldBe "case-1"
                }
                MDC.get(LogContext.CASE_ID).shouldBeNull()
            }
        }

        "the context follows the coroutine across dispatchers" {
            withContext(MDCContext(emptyMap())) {
                withLogContext(LogContext.CASE_ID to "case-io") {
                    withContext(Dispatchers.IO) { MDC.get(LogContext.CASE_ID) } shouldBe "case-io"
                    // coroutineScope: the block of withLogContext has no CoroutineScope receiver, so a bare
                    // async would bind to the enclosing withContext scope and miss the log context.
                    coroutineScope { async(Dispatchers.Default) { MDC.get(LogContext.CASE_ID) }.await() } shouldBe "case-io"
                }
            }
        }

        "withMdc sets the context for the block and restores the previous one" {
            MDC.clear()
            MDC.put(LogContext.CASE_ID, "outer")
            withMdc(mapOf(LogContext.CASE_ID to "inner", LogContext.AGENT_NAME to "dev")) {
                MDC.get(LogContext.CASE_ID) shouldBe "inner"
                MDC.get(LogContext.AGENT_NAME) shouldBe "dev"
            }
            MDC.get(LogContext.CASE_ID) shouldBe "outer"
            MDC.get(LogContext.AGENT_NAME).shouldBeNull()
            MDC.clear()
        }

        "withMdc keeps foreign keys of the thread but drops its stale AgentOS keys" {
            MDC.clear()
            MDC.put("traceId", "trace-1")
            MDC.put(LogContext.AGENT_NAME, "stale-agent")
            withMdc(mapOf(LogContext.CASE_ID to "case-1")) {
                MDC.get("traceId") shouldBe "trace-1"
                MDC.get(LogContext.CASE_ID) shouldBe "case-1"
                MDC.get(LogContext.AGENT_NAME).shouldBeNull()
            }
            MDC.get("traceId") shouldBe "trace-1"
            MDC.get(LogContext.AGENT_NAME) shouldBe "stale-agent"
            MDC.get(LogContext.CASE_ID).shouldBeNull()
            MDC.clear()
        }

        "withMdc with a null context clears a stale MDC during the block, then restores it" {
            MDC.clear()
            MDC.put(LogContext.CASE_ID, "stale")
            withMdc(null) { MDC.get(LogContext.CASE_ID).shouldBeNull() }
            MDC.get(LogContext.CASE_ID) shouldBe "stale"
            MDC.clear()
        }

        "withMdc restores the previous context when the block throws" {
            MDC.clear()
            runCatching { withMdc(mapOf(LogContext.CASE_ID to "inner")) { error("boom") } }
            MDC.get(LogContext.CASE_ID).shouldBeNull()
        }
    })
