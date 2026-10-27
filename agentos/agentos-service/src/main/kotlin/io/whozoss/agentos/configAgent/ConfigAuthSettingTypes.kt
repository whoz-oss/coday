package io.whozoss.agentos.configAgent

import com.fasterxml.jackson.annotation.JsonSubTypes
import io.whozoss.agentos.authSetting.AuthSettingDto
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * The auth setting types the REST API accepts, each with its own fields, read from the
 * [AuthSettingDto] subtypes so that the propose tool's description and the planner follow them. A
 * field marked `*` is a secret: the plan declares it in `secrets` and the user types it in the card.
 */
internal object ConfigAuthSettingTypes {
    private val commonFields = AuthSettingDto::class.memberProperties.map { it.name }.toSet()

    /** The type-specific fields of each `authType`, in declaration order. */
    private val fieldsByType: Map<String, List<String>> =
        AuthSettingDto::class.findAnnotation<JsonSubTypes>()!!.value.associate { type ->
            type.name to
                type.value.primaryConstructor
                    ?.parameters
                    .orEmpty()
                    .mapNotNull { it.name }
                    .filterNot { it in commonFields }
        }

    /** e.g. `API_KEY (apiKey*); BASIC_AUTH (username, password*); ...` */
    val description: String =
        fieldsByType.entries.joinToString("; ") { (type, fields) ->
            "$type (${fields.joinToString(", ") { if (it in ConfigSecretMasker.AUTH_SETTING_SECRET_FIELDS) "$it*" else it }})"
        }

    /** The secret fields of [authType], or null when it is not a known type (the REST API then refuses the setting). */
    fun secretFieldsOf(authType: String?): Set<String>? =
        fieldsByType[authType?.trim()?.uppercase()]?.filter { it in ConfigSecretMasker.AUTH_SETTING_SECRET_FIELDS }?.toSet()
}
