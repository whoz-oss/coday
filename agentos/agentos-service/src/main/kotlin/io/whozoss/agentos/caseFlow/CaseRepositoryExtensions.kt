package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.exception.ResourceNotFoundException
import mu.KotlinLogging
import org.springframework.dao.OptimisticLockingFailureException

private val logger = KotlinLogging.logger {}

/** Saves of one change before a concurrent write is reported to the caller. */
private const val CHANGE_ATTEMPTS = 3

/**
 * Saves [change] applied to [current], the case as last read.
 *
 * Cases are versioned, so a save based on a stale read is refused instead of overwriting a
 * concurrent one. Status changes, generated titles and deletions land on the same case
 * independently: on a refusal the case is read again, soft-deleted included, and [change] is
 * applied to that copy, up to [CHANGE_ATTEMPTS] saves.
 */
fun CaseRepository.saveChange(
    current: Case,
    change: (Case) -> Case,
): Case = saveChangeAttempt(current, change, CHANGE_ATTEMPTS)

private fun CaseRepository.saveChangeAttempt(
    current: Case,
    change: (Case) -> Case,
    attemptsLeft: Int,
): Case =
    try {
        save(change(current))
    } catch (e: OptimisticLockingFailureException) {
        if (attemptsLeft > 1) {
            logger.debug { "Case ${current.id} was written concurrently, applying the change again" }
            saveChangeAttempt(reread(current), change, attemptsLeft - 1)
        } else {
            throw e
        }
    }

private fun CaseRepository.reread(case: Case): Case =
    findByIds(listOf(case.id), withRemoved = true).firstOrNull()
        ?: throw ResourceNotFoundException("Case not found: ${case.id}")
