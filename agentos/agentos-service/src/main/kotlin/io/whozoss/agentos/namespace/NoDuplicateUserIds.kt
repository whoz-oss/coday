package io.whozoss.agentos.namespace

import io.whozoss.agentos.sdk.api.user.UserMembershipRole
import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import kotlin.reflect.KClass

/**
 * Rejects a [List]<[UserMembershipRole]> that contains two entries with the same [UserMembershipRole.userId].
 *
 * Applied at the controller method parameter level (requires `@Validated` on the controller class).
 * Reports a single violation message rather than per-element indices, which is more useful for
 * a batch endpoint where the client knows the full list it sent.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [NoDuplicateUserIdsValidator::class])
annotation class NoDuplicateUserIds(
    val message: String = "members list must not contain duplicate userIds",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out jakarta.validation.Payload>> = [],
)

class NoDuplicateUserIdsValidator : ConstraintValidator<NoDuplicateUserIds, List<UserMembershipRole>> {
    override fun isValid(
        value: List<UserMembershipRole>?,
        context: ConstraintValidatorContext,
    ): Boolean {
        if (value.isNullOrEmpty()) return true
        return value.groupingBy { it.userId }.eachCount().none { it.value > 1 }
    }
}
