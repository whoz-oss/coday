package io.whozoss.agentos.sdk.api.namespace

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Which cases the administrators of a namespace see in their case list. Members always see only their own.
 *
 * It only drives the list the UI shows: who may read which case is decided by the permission model alone,
 * and `GET /api/cases/by-parentId/{parentId}` keeps listing every case of the namespace to an administrator.
 *
 * Published as one shared OpenAPI schema, so the generated clients have a single enum.
 */
@Schema(enumAsRef = true)
enum class AdminCaseListing {
    /** Administrators see their own cases, like members. */
    OWN,

    /** Administrators see their own cases and can switch to all the cases of the namespace. */
    SWITCH,

    /** Administrators see all the cases of the namespace. */
    ALL,
}
