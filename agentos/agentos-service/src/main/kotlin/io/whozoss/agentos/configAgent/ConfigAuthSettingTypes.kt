package io.whozoss.agentos.configAgent

import com.fasterxml.jackson.annotation.JsonSubTypes
import io.whozoss.agentos.authSetting.AuthSettingDto
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * The auth setting types the REST API accepts, each with its own fields, read from the
 * [AuthSettingDto] subtypes so that the propose tool's description follows them. A field marked
 * `*` is a secret: the plan declares it in `secrets` and the user types it in the card.
 */
internal object ConfigAuthSettingTypes {
    private val commonFields = AuthSettingDto::class.memberProperties.map { it.name }.toSet()

    /** e.g. `API_KEY (apiKey*); BASIC_AUTH (username, password*); ...` */
    val description: String =
        AuthSettingDto::class.findAnnotation<JsonSubTypes>()!!.value.joinToString("; ") { type ->
            val fields =
                type.value.primaryConstructor
                    ?.parameters
                    .orEmpty()
                    .mapNotNull { it.name }
                    .filterNot { it in commonFields }
            "${type.name} (${fields.joinToString(", ") { if (it in ConfigSecretMasker.AUTH_SETTING_SECRET_FIELDS) "$it*" else it }})"
        }
}
